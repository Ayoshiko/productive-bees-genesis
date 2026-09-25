package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 基因小食补货的服务端独占状态；不引用 AE2，模板和已提取物品分别保存。 */
public final class GeneTreatRestockState {

	/** 存档、拆卸和合成升级共用的独立状态键。 */
	public static final String NBT_KEY = "productivebeesgenesis_gene_treat_restock";
	public static final int MAX_PENDING_STACKS = 64;
	private boolean enabled;
	private boolean suspended;
	private boolean extractionUnknown;
	private boolean deliveryUnknown;
	private CompoundTag preservedInvalidData;
	private ItemStack template = ItemStack.EMPTY;
	/** 仅派生自只读模板，不持有网络或世界；Object 保持 AE2 可选依赖隔离。 */
	private Object extractionKey;
	private final List<ItemStack> pending = new ArrayList<>();
	private long nextAttempt = Long.MIN_VALUE;
	private long lastAttempt = Long.MIN_VALUE;
	private int failures;

	/** 返回玩家设置的补货开关。 */
	public boolean isEnabled() { return enabled; }

	/** 普通异常可由开关恢复；资产交付未知或损坏数据保持隔离。 */
	public boolean isSuspended() {
		return suspended || extractionUnknown || deliveryUnknown || preservedInvalidData != null;
	}

	/** 仅用于容器下行同步，客户端不参与服务端补货决策。 */
	public void setClientSuspended(boolean value) { suspended = value; }

	/** 设置开关；关闭时清除匹配模板，但不清除已付费物品。 */
	public void setEnabled(boolean value) {
		if (enabled == value) return;
		enabled = value;
		suspended = false;
		if (!value) {
			template = ItemStack.EMPTY;
			extractionKey = null;
		}
		nextAttempt = Long.MIN_VALUE;
		failures = 0;
	}

	/** 更新有效小食模板；空槽保留上一次完整组件快照。 */
	public boolean observe(ItemStack stack) {
		if (!enabled || isSuspended() || !ApiarySlotManager.isGeneTreat(stack)
				|| ItemStack.isSameItemSameComponents(template, stack)) return false;
		template = stack.copyWithCount(1);
		extractionKey = null;
		nextAttempt = Long.MIN_VALUE;
		failures = 0;
		return true;
	}

	/** 返回独立模板，调用者不能修改内部快照。 */
	public ItemStack template() { return template.copy(); }

	/** 只允许补货桥缓存当前模板对应的不可变物品键。 */
	public Object extractionKey() { return extractionKey; }
	public void cacheExtractionKey(Object key) { extractionKey = key; }

	/** 存在已提取物品时禁止再次提取。 */
	public boolean hasPending() { return !pending.isEmpty(); }

	/** 网络成功返回后立即登记所有权，再尝试放入本地槽。 */
	public void acceptExtracted(ItemStack stack) {
		if (stack.isEmpty()) return;
		if (preservedInvalidData != null || pending.size() >= MAX_PENDING_STACKS
				|| !ApiarySlotManager.isGeneTreat(stack) || stack.getCount() > stack.getMaxStackSize()) {
			throw new IllegalStateException("Invalid or excessive gene-treat restock items");
		}
		pending.add(stack.copy());
	}

	/** 将已提取物品交付本地槽；不向网络回送，也不生成掉落实体。 */
	public boolean deliverPending(BasicInventorySlot slot) {
		if (isSuspended()) return false;
		boolean changed = false;
		for (int i = 0; i < pending.size();) {
			ItemStack stack = pending.get(i);
			ItemStack before = slot.getStack().copy();
			RuntimeException failure = null;
			try {
				slot.insertItem(stack.copy(), Action.EXECUTE, AutomationType.INTERNAL);
			} catch (RuntimeException e) {
				failure = e;
			}
			// 本地槽归服务端独占；MEK 在通知监听器前已修改库存，异常时也按实际差额记账。
			ItemStack after = slot.getStack();
			int inserted = ItemStack.matches(before, after) ? 0
					: (before.isEmpty() || ItemStack.isSameItemSameComponents(before, stack))
					&& ItemStack.isSameItemSameComponents(after, stack)
					? after.getCount() - before.getCount() : -1;
			if (inserted < 0 || inserted > stack.getCount()) {
				deliveryUnknown = true;
				throw new IllegalStateException("Gene-treat slot changed unexpectedly; pending items quarantined", failure);
			}
			if (inserted > 0) changed = true;
			if (inserted == stack.getCount()) pending.remove(i);
			else { stack.shrink(inserted); i++; }
			if (failure != null) {
				suspended = true;
				throw failure;
			}
		}
		return changed;
	}

	/** 每五个真实 tick 最多请求一次，空库存指数退避到 200 tick。 */
	public boolean tryBegin(long tick, long phase) {
		if (!enabled || isSuspended() || template.isEmpty() || hasPending()
				|| tick == lastAttempt || tick < nextAttempt || Math.floorMod(tick + phase, 5) != 0) return false;
		lastAttempt = tick;
		nextAttempt = tick + 5;
		return true;
	}

	/** 仅用于已确认的成功或零提取，不用于异常结果。 */
	public void complete(long tick, boolean success) {
		failures = success ? 0 : Math.min(6, failures + 1);
		nextAttempt = tick + Math.min(200, 5L << failures);
	}

	/** 已知资产归属的普通异常暂停，允许玩家排除原因后通过开关恢复。 */
	public void suspend() { suspended = true; }

	/** 外部提取结果未知时持久化隔离；切换开关不能解除或猜测已扣除的数量。 */
	public void quarantineExtraction() { extractionUnknown = true; }

	/** 写入完整状态；模板不是资产，不作为库存参与合成。 */
	public CompoundTag save(HolderLookup.Provider provider) {
		if (preservedInvalidData != null) return preservedInvalidData.copy();
		CompoundTag tag = new CompoundTag();
		tag.putInt("version", 1);
		tag.putBoolean("enabled", enabled);
		tag.putBoolean("suspended", suspended);
		tag.putBoolean("extraction_unknown", extractionUnknown);
		tag.putBoolean("delivery_unknown", deliveryUnknown);
		if (!template.isEmpty()) tag.put("template", template.save(provider));
		ListTag items = new ListTag();
		for (ItemStack stack : pending) items.add(stack.save(provider));
		tag.put("pending", items);
		return tag;
	}

	/** 根字段类型错误也必须保留原始数据，不能由 getCompound 静默转换为空状态。 */
	public void loadRoot(CompoundTag root, HolderLookup.Provider provider) {
		Tag raw = root.get(NBT_KEY);
		if (raw == null || raw instanceof CompoundTag) {
			load(raw == null ? new CompoundTag() : (CompoundTag) raw, provider);
		} else {
			CompoundTag quarantined = new CompoundTag();
			quarantined.put("invalid_root", raw.copy());
			load(quarantined, provider);
		}
	}

	/** 损坏或未知版本的数据原样隔离保存，不能被后续空状态覆盖。 */
	public void load(CompoundTag tag, HolderLookup.Provider provider) {
		extractionKey = null;
		try {
			loadValidated(tag, provider);
			preservedInvalidData = null;
		} catch (RuntimeException e) {
			preservedInvalidData = tag.copy();
			enabled = tag.getBoolean("enabled");
			suspended = true;
			template = ItemStack.EMPTY;
			pending.clear();
			ProductiveBeesGenesis.LOGGER.error("Invalid saved gene-treat restock state; original data preserved", e);
		}
	}

	private void loadValidated(CompoundTag tag, HolderLookup.Provider provider) {
		validateStructure(tag);
		List<ItemStack> restored = new ArrayList<>();
		ListTag items = tag.getList("pending", Tag.TAG_COMPOUND);
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = ItemStack.parse(provider, items.getCompound(i)).orElseThrow(
					() -> new IllegalArgumentException("Invalid saved gene-treat restock item"));
			if (!ApiarySlotManager.isGeneTreat(stack) || stack.getCount() > stack.getMaxStackSize()) {
				throw new IllegalArgumentException("Invalid saved gene-treat restock stack");
			}
			restored.add(stack);
		}
		ItemStack restoredTemplate = tag.contains("template", Tag.TAG_COMPOUND)
				? ItemStack.parse(provider, tag.getCompound("template")).orElseThrow(
						() -> new IllegalArgumentException("Invalid saved gene-treat template")) : ItemStack.EMPTY;
		if (!restoredTemplate.isEmpty() && (!ApiarySlotManager.isGeneTreat(restoredTemplate)
				|| restoredTemplate.getCount() != 1)) throw new IllegalArgumentException("Invalid gene-treat template");
		enabled = tag.getBoolean("enabled");
		suspended = tag.getBoolean("suspended");
		extractionUnknown = tag.getBoolean("extraction_unknown");
		deliveryUnknown = tag.getBoolean("delivery_unknown");
		template = restoredTemplate;
		pending.clear();
		pending.addAll(restored);
		nextAttempt = Long.MIN_VALUE;
		lastAttempt = Long.MIN_VALUE;
		failures = 0;
	}

	/** 合成时首输入继承设置，所有输入的已提取物品逐项保留；超出边界拒绝合成。 */
	public static CompoundTag mergeSaved(List<CompoundTag> states) {
		CompoundTag merged = states.isEmpty() ? new CompoundTag() : states.getFirst().copy();
		ListTag allPending = new ListTag();
		boolean suspended = false;
		for (CompoundTag state : states) {
			validateStructure(state);
			if (state.getBoolean("extraction_unknown") || state.getBoolean("delivery_unknown")) {
				throw new IllegalArgumentException("Unresolved restock transfer");
			}
			suspended |= state.getBoolean("suspended");
			ListTag items = state.getList("pending", Tag.TAG_COMPOUND);
			if (items.size() > MAX_PENDING_STACKS - allPending.size()) {
				throw new IllegalArgumentException("Too many pending gene-treat stacks to merge");
			}
			for (Tag item : items) allPending.add(item.copy());
		}
		merged.putInt("version", 1);
		merged.putBoolean("suspended", suspended);
		merged.put("pending", allPending);
		return merged;
	}

	private static void validateStructure(CompoundTag tag) {
		if (tag.contains("invalid_root")
				|| (tag.contains("version") && (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1))
				|| (tag.contains("enabled") && !tag.contains("enabled", Tag.TAG_BYTE))
				|| (tag.contains("suspended") && !tag.contains("suspended", Tag.TAG_BYTE))
				|| (tag.contains("extraction_unknown") && !tag.contains("extraction_unknown", Tag.TAG_BYTE))
				|| (tag.contains("delivery_unknown") && !tag.contains("delivery_unknown", Tag.TAG_BYTE))
				|| (tag.contains("template") && !tag.contains("template", Tag.TAG_COMPOUND))
				|| (tag.contains("pending") && !(tag.get("pending") instanceof ListTag))) {
			throw new IllegalArgumentException("Unsupported gene-treat restock data");
		}
		if (tag.get("pending") instanceof ListTag items
				&& (items.size() > MAX_PENDING_STACKS || (!items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND))) {
			throw new IllegalArgumentException("Invalid gene-treat pending list");
		}
	}

	/** 已保存拆卸/升级快照后，撤销旧方块持有的所有资产。 */
	public void clearAfterTransfer() {
		extractionKey = null;
		pending.clear();
		template = ItemStack.EMPTY;
		enabled = false;
		suspended = false;
		deliveryUnknown = false;
		extractionUnknown = false;
		preservedInvalidData = null;
	}
}
