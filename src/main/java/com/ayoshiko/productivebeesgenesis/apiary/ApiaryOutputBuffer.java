package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** 有界已付物品缓冲。满载拒收，由冻结批次保留余量；未知交接进入宿主保管，不自动重试。 */
public final class ApiaryOutputBuffer {

	static final int MAX_BUFFER_GROUPS = 512;

	private static final String NBT_KEY = "productivebeesgenesis_output_buffer";
	private static final String NBT_KEY_STACKS = "stacks";
	private static final String NBT_KEY_STACK_COUNT = "productivebeesgenesis_count";

	private final Deque<ItemStack> bufferedStacks = new ArrayDeque<>();

	private final Map<BufferKey, List<ItemStack>> bufferedIndex = new HashMap<>();

	private final Deque<List<ItemStack>> reusableIndexLists = new ArrayDeque<>();

	private long redistributeBackoffUntilNanos = 0L;

	private long redistributeBackoffNanos = 0L;

	private static final long INITIAL_BACKOFF_NANOS = 50_000_000L;

	private static final long MAX_BACKOFF_NANOS = 1_000_000_000L;

	private final AtomicLong outputVersion = new AtomicLong(0L);

	private static final int MAX_AE_BUFFER_GROUPS_PER_CALL = 32;

	private final TileEntityMekApiary tile;
	private final ListTag quarantined = new ListTag();
	private CompoundTag invalidRoot;
	private boolean draining;
	public int freeGroups() { return Math.max(0, MAX_BUFFER_GROUPS - getBufferedGroupCount()); }
	public boolean hasInvalidData() { return invalidRoot != null || !quarantined.isEmpty(); }
	public boolean productionBlocked() { return hasInvalidData() || bufferedStacks.size() >= MAX_BUFFER_GROUPS; }
	public synchronized int accept(ItemStack stack) { return accept(stack, false); }
	public synchronized int accept(ItemStack stack, boolean hold) {
		if (invalidRoot != null || !quarantined.isEmpty()) return 0;
		int accepted = stack.getCount() - offerOneHold(stack, true);
		if (accepted > 0) { if (hold) markHoldContentsChanged(); else markContentsChanged(); }
		return accepted;
	}
	public synchronized boolean canAccept(ItemStack stack) {
		if (productionBlocked()) return false;
		return !stack.isEmpty();
	}

	public ApiaryOutputBuffer(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	public synchronized void offer(List<ItemStack> leftovers) {
		if (leftovers == null || leftovers.isEmpty()) return;
		boolean changed = false;
		for (ItemStack stack : leftovers) {
			if (stack == null || stack.isEmpty()) continue;
			if (accept(stack) != stack.getCount()) throw new IllegalStateException("Paid output requires a frozen batch");
			changed = true;
		}
		if (!changed) return;
		markContentsChanged();
	}

	public synchronized void offer(ItemStack leftover) {
		if (leftover == null || leftover.isEmpty()) return;
		if (accept(leftover) != leftover.getCount()) throw new IllegalStateException("Paid output requires a frozen batch");
		markContentsChanged();
	}

	public synchronized List<ItemStack> offerHolding(List<ItemStack> leftovers,
			Predicate<ItemStack> holdFilter) {
		if (leftovers == null || leftovers.isEmpty()) return null;
		List<ItemStack> overflow = null;
		boolean plainChanged = false;
		boolean holdChanged = false;
		for (ItemStack stack : leftovers) {
			if (stack == null || stack.isEmpty()) continue;
			boolean hold = holdFilter != null && holdFilter.test(stack);
			int overflowCount = offerOneHold(stack, hold);
			int acceptedCount = Math.max(0, stack.getCount() - overflowCount);
			if (acceptedCount > 0) {
				if (hold) holdChanged = true;
				else plainChanged = true;
			}
			if (overflowCount > 0) {
				if (overflow == null) overflow = new ArrayList<>(4);
				overflow.add(stack.copyWithCount(overflowCount));
			}
		}
		// hold 注入不 reset AE2 退避：hold 组永远被 pushToAe 跳过，推送结果不因蜜脾入缓冲而改变；
		// 加速场景下每批产出若 reset 退避会触发一次注定无效的全量遍历（512 组快照 + 逐组判定）。
		// 版本号仍递增：直连退避依赖版本变化感知新蜜脾并立即重试离心机转移。
		if (plainChanged) {
			markContentsChanged();
		} else if (holdChanged) {
			markHoldContentsChanged();
		}
		return overflow;
	}

	private int offerOneHold(ItemStack stack, boolean hold) {
		int remaining = stack.getCount();
		BufferKey key = BufferKey.of(stack);
		List<ItemStack> candidates = bufferedIndex.get(key);
		if (candidates != null) for (ItemStack existing : candidates) {
			if (!ItemStack.isSameItemSameTags(existing, stack)) continue;
			long merged = (long) existing.getCount() + remaining;
			existing.setCount((int) Math.min(Integer.MAX_VALUE, merged));
			remaining = (int) Math.max(0L, merged - Integer.MAX_VALUE);
			if (remaining == 0) break;
		}
		if (remaining <= 0) return 0;
		if (bufferedStacks.size() + quarantined.size() >= MAX_BUFFER_GROUPS || invalidRoot != null) return remaining;

		ItemStack buffered = stack.copyWithCount(remaining);
		bufferedStacks.addLast(buffered);
		if (candidates == null) {
			candidates = borrowIndexList();
			bufferedIndex.put(key, candidates);
		}
		candidates.add(buffered);
		return 0;
	}

	private void markContentsChanged() {
		outputVersion.incrementAndGet();
		tile.onOutputBufferContentsChanged();
		tile.setChanged();
	}

	private void markHoldContentsChanged() {
		outputVersion.incrementAndGet();
		tile.setChanged();
	}

	private void removeFromIndex(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		BufferKey key = BufferKey.of(stack);
		List<ItemStack> entries = bufferedIndex.get(key);
		if (entries == null) return;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i) == stack) {
				entries.remove(i);
				break;
			}
		}
		if (entries.isEmpty()) {
			bufferedIndex.remove(key);
			entries.clear();
			reusableIndexLists.addLast(entries);
		}
	}

	private void rebuildIndex() {
		for (List<ItemStack> entries : bufferedIndex.values()) {
			entries.clear();
			reusableIndexLists.addLast(entries);
		}
		bufferedIndex.clear();
		for (ItemStack stack : bufferedStacks) {
			if (!stack.isEmpty()) {
				BufferKey key = BufferKey.of(stack);
				List<ItemStack> entries = bufferedIndex.get(key);
				if (entries == null) {
					entries = borrowIndexList();
					bufferedIndex.put(key, entries);
				}
				entries.add(stack);
			}
		}
	}

	private List<ItemStack> borrowIndexList() {
		List<ItemStack> entries = reusableIndexLists.pollFirst();
		return entries == null ? new ArrayList<>(1) : entries;
	}

	private void clearIndex() {
		for (List<ItemStack> entries : bufferedIndex.values()) {
			entries.clear();
			reusableIndexLists.addLast(entries);
		}
		bufferedIndex.clear();
	}

	public synchronized void tickRedistribute(List<? extends IInventorySlot> outputSlots) {
		if (outputSlots == null || outputSlots.isEmpty() || redistributeBackoffUntilNanos > System.nanoTime()) return;
		var custody = com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(tile);
		var result = pushToSink(stack -> com.ayoshiko.productivebeesgenesis.logistics.LocalOutputTransfer.insert(outputSlots, stack, custody), null);
		if (result.pushed() == 0) enterBackoff(); else resetBackoff();
	}

	public synchronized void resetBackoff() {
		redistributeBackoffNanos = 0L;
		redistributeBackoffUntilNanos = 0L;
	}

	public synchronized void onOutputSlotContentsChanged() {
		outputVersion.incrementAndGet();
		redistributeBackoffNanos = 0L;
		redistributeBackoffUntilNanos = 0L;
	}

	private void enterBackoff() {
		redistributeBackoffNanos = Math.min(MAX_BACKOFF_NANOS,
				Math.max(INITIAL_BACKOFF_NANOS,
						SaturatingMath.saturatingMultiply(redistributeBackoffNanos, 2)));
		redistributeBackoffUntilNanos = System.nanoTime() + redistributeBackoffNanos;
	}

	public synchronized int tryRedistributeToExternalSlots(
			List<? extends IInventorySlot> externalSlots,
			Predicate<ItemStack> validator) {
		if (externalSlots == null || externalSlots.isEmpty()) return 0;
		var custody = com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(tile);
		return pushToSink(stack -> com.ayoshiko.productivebeesgenesis.logistics.LocalOutputTransfer.insert(externalSlots, stack, custody),
				validator == null ? null : validator.negate()).pushed();
	}

	public synchronized AeBufferPushResult pushToAe(java.util.function.ToIntFunction<ItemStack> pushSingle,
			Predicate<ItemStack> holdFilter) {
		return pushToSink(pushSingle, holdFilter);
	}

	/** 扫描和外部调用共用 32 组预算，队列原地轮转；拒收或 hold 不使队尾饥饿。 */
	public synchronized AeBufferPushResult pushToSink(java.util.function.ToIntFunction<ItemStack> pushSingle,
			Predicate<ItemStack> holdFilter) {
		if (bufferedStacks.isEmpty() || draining) return AeBufferPushResult.EMPTY;
        var custody = com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(tile);
        if (custody.blocked()) return AeBufferPushResult.EMPTY;
        draining = true;
        int pushedTotal = 0, held = 0;
        boolean changed = false;
        try {
            int budget = Math.min(bufferedStacks.size(), MAX_AE_BUFFER_GROUPS_PER_CALL);
            for (int scanned = 0; scanned < budget && !custody.blocked(); scanned++) {
                ItemStack stack = bufferedStacks.peekFirst();
                if (stack == null) break;
                if (holdFilter != null && holdFilter.test(stack)) {
                    held++;
                    bufferedStacks.addLast(bufferedStacks.removeFirst());
                    continue;
                }
                int pushed;
                try {
                    pushed = pushSingle == null ? 0 : pushSingle.applyAsInt(stack.copy());
                    if (pushed < 0 || pushed > stack.getCount()) throw new IllegalStateException("Invalid buffer output receipt");
                } catch (RuntimeException error) {
                    pushed = custody.quarantineItem(stack, "buffer-callback", error);
                }
                bufferedStacks.removeFirst();
                if (pushed >= stack.getCount()) removeFromIndex(stack);
                else {
                    stack.shrink(pushed);
                    bufferedStacks.addLast(stack);
                }
                if (pushed > 0) {
                    pushedTotal = SaturatingMath.saturatingToInt((long) pushedTotal + pushed);
                    changed = true;
                }
            }
            return new AeBufferPushResult(pushedTotal, held);
        } finally {
            draining = false;
            if (changed) { outputVersion.incrementAndGet(); tile.setChanged(); }
        }
    }

	public record AeBufferPushResult(int pushed, int heldCount) {
		static final AeBufferPushResult EMPTY = new AeBufferPushResult(0, 0);
	}

	public synchronized void clear() {
		if (!bufferedStacks.isEmpty()) {
			bufferedStacks.clear();
			quarantined.clear();
			invalidRoot = null;
			clearIndex();

			tile.setChanged();
		}
	}

	public synchronized CompoundTag save(HolderLookup.Provider provider) {
		if (invalidRoot != null) return invalidRoot.copy();
		CompoundTag tag = new CompoundTag();
		tag.putInt("version", 1);
		tag.put("production", tile.productionState().save(provider));
		ListTag list = new ListTag();
		for (ItemStack stack : bufferedStacks) {
			if (!stack.isEmpty()) {
				Tag encoded = stack.copyWithCount(1).save(new CompoundTag());
				if (encoded instanceof CompoundTag stackTag) {
					stackTag.putInt(NBT_KEY_STACK_COUNT, stack.getCount());
					list.add(stackTag);
				}
			}
		}
		list.addAll(quarantined.copy());
		tag.put(NBT_KEY_STACKS, list);
		return tag;
	}

	/** 存档外层也保留原始类型；不可读取的数据装入隔离标签，不把它当作空缓冲。 */
	public synchronized void loadRaw(HolderLookup.Provider provider, Tag raw) {
		if (raw == null || raw instanceof CompoundTag) {
			load(provider, (CompoundTag) raw);
		} else {
			CompoundTag preserved = new CompoundTag();
			preserved.put("invalid_data", raw.copy());
			load(provider, preserved);
		}
	}

	public synchronized void load(HolderLookup.Provider provider, CompoundTag tag) {
		bufferedStacks.clear(); clearIndex(); quarantined.clear(); invalidRoot = null;
		tile.productionState().load(provider, new CompoundTag());
		if (tag == null) return;
		if (!java.util.Set.of("version", "production", NBT_KEY_STACKS).containsAll(tag.getAllKeys())
                || tag.contains("version") && (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1)
                || tag.contains("production") && !tag.contains("production", Tag.TAG_COMPOUND)
                || tag.contains(NBT_KEY_STACKS) && (!(tag.get(NBT_KEY_STACKS) instanceof ListTag rawList)
                    || !rawList.isEmpty() && rawList.getElementType() != Tag.TAG_COMPOUND)) {
			invalidRoot = tag.copy(); return;
		}
		tile.productionState().load(provider, tag.getCompound("production"));
		ListTag list = tag.getList(NBT_KEY_STACKS, Tag.TAG_COMPOUND);
		for (var raw : list) {
			CompoundTag entry = (CompoundTag) raw;
			try {
				if (entry.contains(NBT_KEY_STACK_COUNT) && !entry.contains(NBT_KEY_STACK_COUNT, Tag.TAG_INT))
					throw new IllegalArgumentException("Invalid buffered count type");
				ItemStack stack = java.util.Optional.of(ItemStack.of(entry)).filter(parsedStack -> !parsedStack.isEmpty()).orElseThrow();
				int count = entry.contains(NBT_KEY_STACK_COUNT) ? entry.getInt(NBT_KEY_STACK_COUNT) : stack.getCount();
				if (stack.isEmpty() || count <= 0) throw new IllegalArgumentException("Invalid buffered item");
				bufferedStacks.addLast(stack.copyWithCount(count));
			} catch (RuntimeException error) { quarantined.add(entry.copy()); }
		}
		rebuildIndex();
	}

	public synchronized void retryQuarantined(HolderLookup.Provider provider) {
		load(provider, save(provider));
		tile.setChanged();
	}

	static String nbtKey() {
		return NBT_KEY;
	}

	public synchronized int getBufferedGroupCount() {
		return bufferedStacks.size() + quarantined.size() + (invalidRoot == null ? 0 : 1);
	}

	public long getOutputVersion() {
		return outputVersion.get();
	}

	private static final class BufferKey {
		private final Object item;
		private final Object components;
		private final int hash;

		private BufferKey(Object item, Object components) {
			this.item = item;
			this.components = components;
			this.hash = System.identityHashCode(item) * 31 + components.hashCode();
		}

		static BufferKey of(ItemStack stack) {
			return new BufferKey(stack.getItem(), com.ayoshiko.productivebeesgenesis.util.StackTags.snapshot(stack));
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof BufferKey key
					&& item == key.item && components.equals(key.components);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}
}
