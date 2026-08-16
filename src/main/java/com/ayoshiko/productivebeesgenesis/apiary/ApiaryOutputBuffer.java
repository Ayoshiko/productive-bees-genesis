package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
	 * 蜂箱产物溢出缓冲区
	 * <br/>
	 * 缓存 distributeToOutput 失败的剩余 ItemStack，下次 tick 重试注入输出槽。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅缓存与重试注入，不涉及产出计算或槽位管理</li>
	 *   <li>线程安全：synchronized 保护 offer/tickRedistribute/save/load，与 NBT 同步线程互斥</li>
	 *   <li>容量上限：MAX_BUFFER_GROUPS=512，覆盖 60 蜜蜂 × 6 产物的单批最坏情况；持续阻塞时仍有 FIFO 上限保护</li>
	 *   <li>FIFO 淘汰：ArrayDeque 实现，pollFirst/addLast 均 O(1)</li>
	 * </ul>
	 * <p>
	 * F4 修复：解决 BeeProduceProcessor.distributeToOutput 输出槽满载时丢弃剩余产物的问题。
	 * 产物守恒：缓冲区内容通过 saveApiaryState 序列化到 NBT，方块破坏时随 BLOCK_ENTITY_DATA 保留。
	 */
public final class ApiaryOutputBuffer {

	/** 缓冲区容量上限（组数） — 防止输出槽长期满载时缓冲区无限增长导致 OOM */
	static final int MAX_BUFFER_GROUPS = 512;

	/** NBT key — 供 ApiaryNbtSerializer 使用 */
	private static final String NBT_KEY = "productivebeesgenesis_output_buffer";
	private static final String NBT_KEY_STACKS = "stacks";
	private static final String NBT_KEY_STACK_COUNT = "productivebeesgenesis_count";

	/** 缓冲的物品栈双端队列（按入队顺序，FIFO 重试 + FIFO 淘汰均 O(1)） */
	private final Deque<ItemStack> bufferedStacks = new ArrayDeque<>();

	/** 物品键到缓冲组的索引；Deque 仍负责 FIFO 顺序和淘汰。 */
	private final Map<BufferKey, List<ItemStack>> bufferedIndex = new HashMap<>();
	/** Reused per-key lists so repeated buffer rebuilds do not allocate short-lived ArrayLists. */
	private final Deque<List<ItemStack>> reusableIndexLists = new ArrayDeque<>();

	/** 复用 remaining 列表避免每 tick 分配 ArrayList（256× 加速场景下减少 GC 压力） */
	private final List<ItemStack> remainingBuffer = new ArrayList<>();

	/** 退避墙钟到期时间（nanoTime）— 连续失败后延迟重试，避免每 tick 无效调用 insertItem */
	private long redistributeBackoffUntilNanos = 0L;

	/** 当前退避时长（nanoTime）— 指数递增，50ms 起步、1s 封顶 */
	private long redistributeBackoffNanos = 0L;

	/** 退避初始时长 — 50ms（墙钟对 tick 加速免疫，加速下真实时间恒定） */
	private static final long INITIAL_BACKOFF_NANOS = 50_000_000L;

	/** 退避上限 — 1s，平衡响应速度与 CPU 开销 */
	private static final long MAX_BACKOFF_NANOS = 1_000_000_000L;

	/** 模块2.3.1：缓冲区满丢弃计数器 — 统计近5分钟丢弃次数，用于日志聚合（AtomicLong 保证线程安全） */
	private final AtomicLong discardedCount = new AtomicLong(0);

	/**
	 * 输出内容版本号 — 缓冲区入队或成功注入输出槽时递增。
	 * <br/>
	 * 供 {@code TileEntityMekApiary.productivebeesgenesis$outputContentsVersion()} 返回，
	 * 驱动 Ejector 冷却 Mixin 在产物变化后立即重试弹出（对齐离心机 FactoryPbContextDelegate 版本号机制）。
	 */
	private final AtomicLong outputVersion = new AtomicLong(0L);

	/**
	 * 预扫描槽位状态数组复用 — 与 {@link BeeProduceProcessor#distributeToOutput} 直写模式一致，
	 * 避免每 tick 调用 insertItem 时内部重复查询 getLimit。
	 * 槽位数不变时复用，仅清空引用。
	 */
	private ItemStack[] reusableRedistStacks = new ItemStack[0];
	private int[] reusableRedistCounts = new int[0];
	private int[] reusableRedistLimits = new int[0];
	private int redistributeEmptyCursor;

	/**
	 * 代码审查修复：外部槽位（离心机输入槽）独立预扫描数组
	 * <br/>
	 * 原 tryRedistributeToExternalSlots 与 tickRedistribute 共享 reusableRedistStacks，
	 * 但两者槽位数不同（蜂箱9槽 vs 离心机19槽），导致每 tick 重复分配 3×2=6 个数组。
	 * 256× 加速下显著增加 GC 压力。独立数组确保各自复用，零扩容。
	 */
	private ItemStack[] reusableExternalStacks = new ItemStack[0];
	private int[] reusableExternalCounts = new int[0];
	private int[] reusableExternalLimits = new int[0];
	private int externalEmptyCursor;

	private static final int MAX_AE_BUFFER_GROUPS_PER_CALL = 32;
	private ItemStack[] reusableAePushStacks = new ItemStack[0];
	private int aePushCursor;

	/** 所属方块实体（用于 setChanged） */
	private final TileEntityMekApiary tile;

	public ApiaryOutputBuffer(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	/**
	 * 尝试注入剩余产物到缓冲区。
	 * 缓冲区满时丢弃最旧组并记录 WARN（模块2.3.1：使用 LogThrottle 5分钟节流避免刷屏）。
	 * <p>
	 * 相同物品和组件优先合并到现有缓冲组；数量以 int 保留，持久化时由自定义数量字段编码，
	 * 不需要为超量堆叠创建成千上万个临时 ItemStack。
	 *
	 * @param leftovers distributeToOutput 未成功插入的剩余产物列表
	 */
	public synchronized void offer(List<ItemStack> leftovers) {
		if (leftovers == null || leftovers.isEmpty()) return;
		boolean changed = false;
		for (ItemStack stack : leftovers) {
			changed |= offerOne(stack);
		}
		if (!changed) return;
		markContentsChanged();
	}

	/** Single-stack path used by defensive transport rollback without allocating a wrapper list. */
	public synchronized void offer(ItemStack leftover) {
		if (!offerOne(leftover)) return;
		markContentsChanged();
	}

	private boolean offerOne(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		int remaining = stack.getCount();
		BufferKey key = BufferKey.of(stack);
		List<ItemStack> candidates = bufferedIndex.get(key);
		if (candidates != null) for (ItemStack existing : candidates) {
			if (!ItemStack.isSameItemSameComponents(existing, stack)) continue;
			long merged = (long) existing.getCount() + remaining;
			existing.setCount((int) Math.min(Integer.MAX_VALUE, merged));
			remaining = (int) Math.max(0L, merged - Integer.MAX_VALUE);
			if (remaining == 0) break;
		}
		if (remaining <= 0) return true;
		if (bufferedStacks.size() >= MAX_BUFFER_GROUPS) {
			ItemStack evicted = bufferedStacks.pollFirst();
			removeFromIndex(evicted);
			discardedCount.incrementAndGet();
			LogThrottle.warnWithCooldown("apiary_output_buffer_full", 300_000L,
					"ApiaryOutputBuffer 已满（{}），丢弃最旧产物组，近5分钟累计丢弃 {} 组",
					MAX_BUFFER_GROUPS, discardedCount.get());
			// Eviction can pool this key's final list, so resolve it again before appending.
			candidates = bufferedIndex.get(key);
		}
		ItemStack buffered = stack.copyWithCount(remaining);
		bufferedStacks.addLast(buffered);
		if (candidates == null) {
			candidates = borrowIndexList();
			bufferedIndex.put(key, candidates);
		}
		candidates.add(buffered);
		return true;
	}

	private void markContentsChanged() {
		outputVersion.incrementAndGet();
		tile.onOutputBufferContentsChanged();
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

	/**
	 * 每 tick 调用：尝试将缓冲区内的物品重新注入输出槽。
	 * <br/>
	 * 性能优化（Spark 分析 v2.0.2）：
	 * <ol>
	 *   <li><b>退避机制</b>：输出槽全满时进入 nanoTime 墙钟指数退避（50ms→1s），
	 *       避免每 tick 无效调用 insertItem；墙钟对 tick 加速免疫，退避期内直接返回。</li>
	 *   <li><b>预扫描直写</b>：与 {@link BeeProduceProcessor#distributeToOutput} 一致，
	 *       先一次遍历获取所有槽位的 stack/count/limit，再用 setStack 替代 insertItem，
	 *       将 getLimit 查询从 N(缓冲栈)×M(输出槽) 次降为 M 次。</li>
	 * </ol>
	 * 注入成功的物品从缓冲区移除，失败的保留至下 tick。
	 *
	 * @param outputSlots 蜂箱输出槽列表
	 */
	public synchronized void tickRedistribute(List<? extends IInventorySlot> outputSlots) {
		if (bufferedStacks.isEmpty() || outputSlots == null || outputSlots.isEmpty()) return;

		// 退避检查 — 墙钟退避（nanoTime），tick 加速下依然按真实时间延迟重试
		if (redistributeBackoffUntilNanos > System.nanoTime()) {
			return;
		}

		int slotCount = outputSlots.size();
		// 预扫描槽位状态（与 BeeProduceProcessor.distributeToOutput 直写模式一致）
		if (reusableRedistStacks.length != slotCount) {
			reusableRedistStacks = new ItemStack[slotCount];
			reusableRedistCounts = new int[slotCount];
			reusableRedistLimits = new int[slotCount];
		} else {
			Arrays.fill(reusableRedistStacks, null);
		}

		boolean hasSpace = false;
		for (int i = 0; i < slotCount; i++) {
			ItemStack current = outputSlots.get(i).getStack();
			reusableRedistStacks[i] = current;
			if (current.isEmpty()) {
				reusableRedistCounts[i] = 0;
				reusableRedistLimits[i] = 0;
				hasSpace = true;
			} else {
				int count = current.getCount();
				int limit = outputSlots.get(i).getLimit(current);
				reusableRedistCounts[i] = count;
				reusableRedistLimits[i] = limit;
				if (count < limit) hasSpace = true;
			}
		}

		// 输出槽全满 — 进入墙钟退避（指数递增），避免每 tick 无效重试
		if (!hasSpace) {
			enterBackoff();
			return;
		}

		// 直写分发 — setStack 替代 insertItem，避免 getLimit 重复查询
		remainingBuffer.clear();
		boolean changed = false;
		for (ItemStack stack : bufferedStacks) {
			if (stack.isEmpty()) {
				changed = true;
				continue;
			}
			int remaining = stack.getCount();
			int originalCount = remaining;
			// Merge matching stacks on either page before claiming any empty slot.
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				ItemStack slotStack = reusableRedistStacks[i];
				if (slotStack.isEmpty() || slotStack.getItem() != stack.getItem()
						|| !ItemStack.isSameItemSameComponents(slotStack, stack)) continue;
				int space = reusableRedistLimits[i] - reusableRedistCounts[i];
				if (space <= 0) continue;
				int canFit = Math.min(remaining, space);
				outputSlots.get(i).setStack(slotStack.copyWithCount(reusableRedistCounts[i] + canFit));
				ItemStack actual = outputSlots.get(i).getStack();
				int actualCount = actual.isEmpty() ? 0 : actual.getCount();
				int actualGrown = Math.max(0, actualCount - reusableRedistCounts[i]);
				reusableRedistStacks[i] = actual;
				reusableRedistCounts[i] = actualCount;
				remaining -= actualGrown;
			}
			int emptyStart = RoundRobinSlotTraversal.normalize(redistributeEmptyCursor, slotCount);
			for (int offset = 0; offset < slotCount && remaining > 0; offset++) {
				int i = RoundRobinSlotTraversal.index(emptyStart, offset, slotCount);
				ItemStack slotStack = reusableRedistStacks[i];
				if (slotStack.isEmpty()) {
					// 空槽：查询 limit 并填入
					int limit = outputSlots.get(i).getLimit(stack);
					if (limit <= 0) continue;
					int canFit = Math.min(remaining, limit);
					outputSlots.get(i).setStack(stack.copyWithCount(canFit));
					// 回读 actual stack 防止 slot 内部截断
					ItemStack actual = outputSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					reusableRedistStacks[i] = actual;
					reusableRedistCounts[i] = actualCount;
					reusableRedistLimits[i] = limit;
					remaining -= actualCount;
					if (actualCount > 0) {
						redistributeEmptyCursor = RoundRobinSlotTraversal.advance(i, slotCount);
					}
				} else if (slotStack.getItem() == stack.getItem()
						&& ItemStack.isSameItemSameComponents(slotStack, stack)) {
					// 同类型槽：叠加
					int space = reusableRedistLimits[i] - reusableRedistCounts[i];
					if (space <= 0) continue;
					int canFit = Math.min(remaining, space);
					outputSlots.get(i).setStack(slotStack.copyWithCount(reusableRedistCounts[i] + canFit));
					ItemStack actual = outputSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					int actualGrown = Math.max(0, actualCount - reusableRedistCounts[i]);
					reusableRedistStacks[i] = actual;
					reusableRedistCounts[i] = actualCount;
					remaining -= actualGrown;
				}
			}
			if (remaining > 0) {
				if (remaining < originalCount) {
					stack.setCount(remaining);
					changed = true;
				}
				remainingBuffer.add(stack);
			} else {
				changed = true;
			}
		}

		if (changed) {
			bufferedStacks.clear();
			bufferedStacks.addAll(remainingBuffer);
			rebuildIndex();
			outputVersion.incrementAndGet();
			tile.setChanged();
		}

		// 退避策略：部分失败时进入墙钟退避（指数递增），全部成功时重置
		if (!remainingBuffer.isEmpty()) {
			enterBackoff();
		} else {
			redistributeBackoffNanos = 0L;
			redistributeBackoffUntilNanos = 0L;
		}
	}

	/**
	 * 模块5：重置退避计数器 — 供 ApiaryDirectEjectHandler 成功转移后调用
	 * <br/>
	 * 当直连弹出成功转移缓冲区物品到离心机后，主动重置退避，使下一次 {@link #tickRedistribute}
	 * 立即尝试将剩余缓冲区产物注入蜂箱输出槽（避免墙钟指数退避延迟：50ms 起步、倍增、1s 封顶）。
	 * <p>
	 * 设计原则：暴露最小接口，不暴露内部退避状态字段。
	 */
	public synchronized void resetBackoff() {
		redistributeBackoffNanos = 0L;
		redistributeBackoffUntilNanos = 0L;
	}

	/** Invalidates transport backoff/version state whenever a physical output slot changes. */
	public synchronized void onOutputSlotContentsChanged() {
		outputVersion.incrementAndGet();
		redistributeBackoffNanos = 0L;
		redistributeBackoffUntilNanos = 0L;
	}

	/** 进入墙钟指数退避（50ms 起步、倍增、1s 封顶） */
	private void enterBackoff() {
		redistributeBackoffNanos = Math.min(MAX_BACKOFF_NANOS,
				Math.max(INITIAL_BACKOFF_NANOS,
						SaturatingMath.saturatingMultiply(redistributeBackoffNanos, 2)));
		redistributeBackoffUntilNanos = System.nanoTime() + redistributeBackoffNanos;
	}

	/**
	 * 模块5：尝试将缓冲区物品直接插入到外部槽位（如离心机输入槽）
	 * <br/>
	 * 当蜂箱输出槽已通过直连弹出清空、缓冲区仍有积压时，复用离心机输入槽剩余空间
	 * 直接转移缓冲区物品，绕过"缓冲区→蜂箱输出槽→离心机"的两跳路径，解决缓冲区持续积压问题。
	 * <p>
	 * 与 {@link #tickRedistribute} 区别：
	 * <ul>
	 *   <li>不应用退避机制：直连弹出已确认离心机相邻且可能有空间，无需退避</li>
	 *   <li>不修改退避计数器：由调用方根据返回值决定是否调用 {@link #resetBackoff()}</li>
	 *   <li>支持物品验证：通过 validator 过滤非离心配方输入物品（如蜂蜡等），保留在缓冲区</li>
	 * </ul>
	 * <p>
	 * 性能：使用独立预扫描数组 {@link #reusableExternalStacks}，与 tickRedistribute 的
	 * {@link #reusableRedistStacks} 分离，避免离心机输入槽位数（19）与蜂箱输出槽数（9）不一致
	 * 时反复扩容导致的 3×2=6 个数组重新分配。
	 * ArrayDeque.peekFirst O(1) 查询由调用方在调用前通过 {@link #getBufferedGroupCount} 短路完成。
	 *
	 * @param externalSlots 外部槽位列表（如离心机输入槽）
	 * @param validator     物品有效性验证器（null 表示不过滤），保留无效物品在缓冲区
	 * @return 实际转移的物品总数（0 表示未转移）
	 */
	public synchronized int tryRedistributeToExternalSlots(
			List<? extends IInventorySlot> externalSlots,
			Predicate<ItemStack> validator) {
		if (bufferedStacks.isEmpty() || externalSlots == null || externalSlots.isEmpty()) return 0;
		int slotCount = externalSlots.size();
		// 使用独立预扫描数组（与 tickRedistribute 分离，避免槽位数不同时反复扩容）
		if (reusableExternalStacks.length != slotCount) {
			reusableExternalStacks = new ItemStack[slotCount];
			reusableExternalCounts = new int[slotCount];
			reusableExternalLimits = new int[slotCount];
		} else {
			Arrays.fill(reusableExternalStacks, null);
		}

		boolean hasSpace = false;
		for (int i = 0; i < slotCount; i++) {
			ItemStack current = externalSlots.get(i).getStack();
			reusableExternalStacks[i] = current;
			if (current.isEmpty()) {
				reusableExternalCounts[i] = 0;
				reusableExternalLimits[i] = 0;
				hasSpace = true;
			} else {
				int count = current.getCount();
				int limit = externalSlots.get(i).getLimit(current);
				reusableExternalCounts[i] = count;
				reusableExternalLimits[i] = limit;
				if (count < limit) hasSpace = true;
			}
		}

		// 外部槽位全满 — 直接返回，避免无效遍历缓冲区
		if (!hasSpace) return 0;

		// 直写分发 — 与 tickRedistribute 一致的 setStack 直写策略
		remainingBuffer.clear();
		boolean changed = false;
		int totalTransferred = 0;
		for (ItemStack stack : bufferedStacks) {
			if (stack.isEmpty()) {
				changed = true;
				continue;
			}
			// 模块5：验证物品是否为外部槽位有效输入，无效物品保留在缓冲区由 tickRedistribute 处理
			if (validator != null && !validator.test(stack)) {
				remainingBuffer.add(stack);
				continue;
			}
			int remaining = stack.getCount();
			int originalCount = remaining;
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				ItemStack slotStack = reusableExternalStacks[i];
				if (slotStack.isEmpty() || slotStack.getItem() != stack.getItem()
						|| !ItemStack.isSameItemSameComponents(slotStack, stack)) continue;
				int space = reusableExternalLimits[i] - reusableExternalCounts[i];
				if (space <= 0) continue;
				int canFit = Math.min(remaining, space);
				externalSlots.get(i).setStack(slotStack.copyWithCount(reusableExternalCounts[i] + canFit));
				ItemStack actual = externalSlots.get(i).getStack();
				int actualCount = actual.isEmpty() ? 0 : actual.getCount();
				int actualGrown = Math.max(0, actualCount - reusableExternalCounts[i]);
				reusableExternalStacks[i] = actual;
				reusableExternalCounts[i] = actualCount;
				remaining -= actualGrown;
				totalTransferred = SaturatingMath.saturatingToInt(
						SaturatingMath.saturatingAdd(totalTransferred, actualGrown));
			}
			int emptyStart = RoundRobinSlotTraversal.normalize(externalEmptyCursor, slotCount);
			for (int offset = 0; offset < slotCount && remaining > 0; offset++) {
				int i = RoundRobinSlotTraversal.index(emptyStart, offset, slotCount);
				ItemStack slotStack = reusableExternalStacks[i];
				if (slotStack.isEmpty()) {
					// 空槽：查询 limit 并填入
					int limit = externalSlots.get(i).getLimit(stack);
					if (limit <= 0) continue;
					int canFit = Math.min(remaining, limit);
					externalSlots.get(i).setStack(stack.copyWithCount(canFit));
					// 回读 actual stack 防止 slot 内部截断
					ItemStack actual = externalSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					reusableExternalStacks[i] = actual;
					reusableExternalCounts[i] = actualCount;
					reusableExternalLimits[i] = limit;
					int transferred = actualCount;
					remaining -= transferred;
					totalTransferred = SaturatingMath.saturatingToInt(
						SaturatingMath.saturatingAdd(totalTransferred, transferred));
					if (actualCount > 0) {
						externalEmptyCursor = RoundRobinSlotTraversal.advance(i, slotCount);
					}
				} else if (slotStack.getItem() == stack.getItem()
						&& ItemStack.isSameItemSameComponents(slotStack, stack)) {
					// 同类型槽：叠加
					int space = reusableExternalLimits[i] - reusableExternalCounts[i];
					if (space <= 0) continue;
					int canFit = Math.min(remaining, space);
					externalSlots.get(i).setStack(slotStack.copyWithCount(reusableExternalCounts[i] + canFit));
					ItemStack actual = externalSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					int actualGrown = Math.max(0, actualCount - reusableExternalCounts[i]);
					reusableExternalStacks[i] = actual;
					reusableExternalCounts[i] = actualCount;
					remaining -= actualGrown;
					totalTransferred = SaturatingMath.saturatingToInt(
						SaturatingMath.saturatingAdd(totalTransferred, actualGrown));
				}
			}
			if (remaining > 0) {
				if (remaining < originalCount) {
					stack.setCount(remaining);
					changed = true;
				}
				remainingBuffer.add(stack);
			} else {
				changed = true;
			}
		}

		if (changed) {
			bufferedStacks.clear();
			bufferedStacks.addAll(remainingBuffer);
			rebuildIndex();
			outputVersion.incrementAndGet();
			tile.setChanged();
		}
		return totalTransferred;
	}

	/**
	 * 模块6：将缓冲区物品直接推送到 AE2 网络（绕过输出槽中转）
	 * <br/>
	 * 当输出槽被待离心蜜脾占满、缓冲区里积压了非蜜脾物品或多余蜜脾时，
	 * 直接调用回调逐组推送，避免等待输出槽腾出空间（墙钟退避，加速免疫）。
	 * <p>
	 * 回调返回该组实际被接收的数量（0=完全拒绝）；部分接收时剩余数量保留在缓冲区。
	 * 与 {@link #tryRedistributeToExternalSlots} 共用 remainingBuffer，调用前已 clear。
	 *
	 * @param pushSingle 单组推送回调（返回实际接收数量，异常时按 0 处理）
	 * @return 实际推送总量；0 表示未推送
	 */
	public synchronized int pushToAe(java.util.function.ToIntFunction<ItemStack> pushSingle) {
		if (bufferedStacks.isEmpty()) return 0;
		remainingBuffer.clear();
		int groupCount = bufferedStacks.size();
		if (reusableAePushStacks.length < groupCount) {
			reusableAePushStacks = new ItemStack[groupCount];
		}
		int snapshotIndex = 0;
		for (ItemStack buffered : bufferedStacks) {
			reusableAePushStacks[snapshotIndex++] = buffered;
		}
		int start = RoundRobinSlotTraversal.normalize(aePushCursor, groupCount);
		int attemptLimit = Math.min(groupCount, MAX_AE_BUFFER_GROUPS_PER_CALL);
		int attemptedSurvivors = 0;
		int totalPushed = 0;
		boolean changed = false;
		for (int offset = 0; offset < groupCount; offset++) {
			ItemStack stack = reusableAePushStacks[
					RoundRobinSlotTraversal.index(start, offset, groupCount)];
			if (stack.isEmpty()) {
				changed = true;
				continue;
			}
			if (offset >= attemptLimit) {
				remainingBuffer.add(stack);
				continue;
			}
			int pushed = 0;
			try {
				// A callback must never be able to claim more than this stack contains.
				// Clamping here keeps the buffer and the returned accounting consistent
				// even when an integration reports an invalid amount.
				pushed = Math.min(stack.getCount(), Math.max(0,
						pushSingle == null ? 0 : pushSingle.applyAsInt(stack)));
			} catch (RuntimeException e) {
				// 单物品推送异常隔离：不影响其余缓冲物品，但需记录日志便于定位
				pushed = 0;
				LogThrottle.warn("apiary_buffer_push_ae",
						"缓冲区物品推送 AE2 异常，该物品保留待重试: {}", stack, e);
			}
			if (pushed <= 0) {
				remainingBuffer.add(stack);
				attemptedSurvivors++;
				continue;
			}
			totalPushed = SaturatingMath.saturatingToInt(
				SaturatingMath.saturatingAdd(totalPushed, pushed));
			changed = true;
			int remaining = stack.getCount() - pushed;
			if (remaining > 0) {
				stack.setCount(remaining);
				remainingBuffer.add(stack);
				attemptedSurvivors++;
			}
		}
		if (changed) {
			bufferedStacks.clear();
			bufferedStacks.addAll(remainingBuffer);
			rebuildIndex();
			outputVersion.incrementAndGet();
			tile.setChanged();
			aePushCursor = RoundRobinSlotTraversal.normalize(
					attemptedSurvivors, remainingBuffer.size());
		} else {
			aePushCursor = RoundRobinSlotTraversal.index(start, attemptLimit, groupCount);
		}
		return totalPushed;
	}

	/**
	 * 清空缓冲区（不掉落）。
	 * <br/>
	 * 供 {@link MekApiaryBlock#getDrops} 和 {@link MekApiaryBlock#onRemove} 在保存 buffer NBT 到 BLOCK_ENTITY_DATA 后调用，
	 * 防御性清空槽位，避免未来引入新的 popResource 路径导致物品爆出。
	 */
	public synchronized void clear() {
		if (!bufferedStacks.isEmpty()) {
			bufferedStacks.clear();
			clearIndex();
			aePushCursor = 0;
			tile.setChanged();
		}
	}

	/**
	 * NBT 序列化 — 供 ApiaryNbtSerializer 调用
	 * <p>
	 * 超量堆叠使用 count=1 的合法模板编码，并把真实 int 数量写入独立字段。
	 * 这避免按 99 拆分巨大数量造成保存尖峰，同时 load 仍兼容没有该字段的旧条目。
	 */
	public synchronized CompoundTag save(HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();
		if (bufferedStacks.isEmpty()) return tag;
		ListTag list = new ListTag();
		for (ItemStack stack : bufferedStacks) {
			if (!stack.isEmpty()) {
				Tag encoded = stack.copyWithCount(1).save(provider);
				if (encoded instanceof CompoundTag stackTag) {
					stackTag.putInt(NBT_KEY_STACK_COUNT, stack.getCount());
					list.add(stackTag);
				}
			}
		}
		tag.put(NBT_KEY_STACKS, list);
		return tag;
	}

	/** NBT 反序列化 — 供 ApiaryNbtSerializer 调用（向后兼容：旧存档无此字段时跳过） */
	public synchronized void load(HolderLookup.Provider provider, CompoundTag tag) {
		bufferedStacks.clear();
		clearIndex();
		aePushCursor = 0;
		if (tag == null) return;
		if (tag.contains(NBT_KEY_STACKS, Tag.TAG_LIST)) {
			ListTag list = tag.getList(NBT_KEY_STACKS, Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag stackTag = list.getCompound(i);
				ItemStack stack = ItemStack.parse(provider, stackTag).orElse(ItemStack.EMPTY);
				if (!stack.isEmpty()) {
					int storedCount = stackTag.getInt(NBT_KEY_STACK_COUNT);
					if (storedCount > 0) stack.setCount(storedCount);
					bufferedStacks.addLast(stack);
				}
			}
		}
		rebuildIndex();
	}

	/** NBT key（供 ApiaryNbtSerializer 使用） */
	static String nbtKey() {
		return NBT_KEY;
	}

	/** 客户端同步用：返回缓冲区当前组数 */
	public synchronized int getBufferedGroupCount() {
		return bufferedStacks.size();
	}

	/** 输出内容版本号（见 {@link #outputVersion}） */
	public long getOutputVersion() {
		return outputVersion.get();
	}

	/** Immutable item/component key used only for the in-memory aggregation index. */
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
			return new BufferKey(stack.getItem(), stack.getComponents());
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
