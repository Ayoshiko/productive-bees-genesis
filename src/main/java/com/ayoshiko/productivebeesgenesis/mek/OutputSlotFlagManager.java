package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

/**
	 * 工厂离心机输出槽标志位批量/增量/延迟管理器
	 * <p>
	 * 原实现：每次 insertItem 触发 {@link IContentsListener}， listener 中调用全量
	 * {@code updateOutputSlotFlags()} 扫描所有进程，复杂度 O(processes) / 每次插入。
	 * 在 256 倍时间手杖下，一个进程一次 completePbRecipe 可能产生数十个物品插入事件，
	 * 导致 O(processes × inserts) 的级联开销（热力图中表现为 BasicInventorySlot.insertItem
	 * → onContentsChanged → updateOutputSlotFlags → isSlotFull → getLimit 占比 8~13%）。
	 * <p>
	 * 本管理器改为：
	 * <ul>
	 *   <li>批量模式（begin/end）下 listener 只标记 dirty，不扫描；</li>
	 *   <li>批量结束时只重新计算受影响的那一个进程，O(1)；</li>
	 *   <li>非批量外部插入/提取（SFM/AE2/漏斗）也只标记 dirty，延迟到下次读取标志位时才全量扫描，
	 *       将 SFM 连续 N 次 extractItem 的 O(N × processes) 降为 O(processes)。</li>
	 * </ul>
	 * 同时维护每进程 {@code hasItems} 与 {@code full} 状态，通过计数器 O(1) 给出全局标志。
	 */
public final class OutputSlotFlagManager {

	private final PbRecipeContext context;
	private final boolean[] processHasItems;
	private final boolean[] processFull;
	private int hasItemsProcessCount;
	private int fullProcessCount;
	private int batchDepth;
	private boolean dirty;
	/** Number of listener callbacks expected from the manager-owned writes in this batch. */
	private int expectedListenerEvents;

	/**
	 * 每进程输出槽物品数量（主+副1+副2）
	 * <br/>
	 * Step 5: 供 {@link #outputItemCount()} O(1) 读取，替代 Ejector Mixin 中
	 * O(processes×3) 遍历的 {@code countOutputItems}。在 {@link #updateProcessInternal}
	 * / {@link #updateProcessAggregate} 中更新，{@link #updateAll} 中维护总量。
	 */
	private final long[] processItemCount;
	/** 所有进程输出槽物品总数（processItemCount 之和） */
	private long outputItemCount;

	/**
	 * 每槽位上限缓存（identity 短路）
	 * <br/>
	 * {@code slot.getLimit(stack)} 在 owo 派生组件下会触发昂贵的 DataComponentMap 查询，
	 * 而输出槽中的栈引用在多数插入操作中保持不变（仅 count 变化）。
	 * 通过缓存 {@code stack == cachedStack} 时的上限，避免每次标志位更新都重新计算。
	 * 索引 = process * 3 + slotIndex（0=主输出，1=副输出1，2=副输出2）。
	 */
	private final ItemStack[] cachedLimitStacks;
	private final int[] cachedLimits;

	/**
	 * 每槽位状态缓存（Task 7 增量更新）
	 * <br/>
	 * 由 {@link #updateSlotOnly} 维护，{@link #updateProcessAggregate} 聚合时复用，
	 * 避免对未变更槽位重复调用 {@code slot.getStack()} / {@code slot.getLimit()}。
	 * 索引 = process * 3 + slotIndex。
	 */
	private final boolean[] slotHasItems;
	private final boolean[] slotFull;
	private final long[] slotItemCount;
	/** 每槽位是否已被本次 batch 内 updateSlotOnly 标记为已知状态 */
	private final boolean[] slotKnown;

	public OutputSlotFlagManager(PbRecipeContext context) {
		this.context = context;
		int processes = context.processes();
		this.processHasItems = new boolean[processes];
		this.processFull = new boolean[processes];
		this.processItemCount = new long[processes];
		this.cachedLimitStacks = new ItemStack[processes * 3];
		this.cachedLimits = new int[processes * 3];
		this.slotHasItems = new boolean[processes * 3];
		this.slotFull = new boolean[processes * 3];
		this.slotItemCount = new long[processes * 3];
		this.slotKnown = new boolean[processes * 3];
	}

	/** 是否有任意输出槽含物品 */
	public boolean hasOutputItems() {
		flushDirty();
		return hasItemsProcessCount > 0;
	}

	/** 是否有任意进程的所有物品输出槽已满 */
	public boolean outputSlotsFull() {
		flushDirty();
		return fullProcessCount > 0;
	}

	/** 指定进程的所有物品输出槽是否已满 */
	public boolean outputSlotsFull(int process) {
		flushDirty();
		return process >= 0 && process < processFull.length && processFull[process];
	}

	/**
	 * 所有输出槽的物品总数（O(1) 读取，dirty 时触发一次全量刷新）
	 * <br/>
	 * Step 5: 供 Ejector Mixin 替代 O(processes×3) 遍历的 countOutputItems。
	 * 在 {@link #updateProcessInternal} / {@link #updateProcessAggregate} / {@link #updateAll} 中维护。
	 */
	public long outputItemCount() {
		flushDirty();
		return outputItemCount;
	}

	/** 输出槽内容变化时调用（由 IContentsListener 回调） */
	public void onSlotChanged() {
		// Output slots notify synchronously. During our own begin/end batch the caller
		// immediately records the changed slot through updateSlotOnly, so a full scan
		// would discard the incremental fast path. Changes outside a batch still use
		// dirty to cover SFM/AE2/hopper mutations that do not identify a process.
		if (batchDepth == 0) {
			dirty = true;
		} else if (expectedListenerEvents > 0) {
			// The caller declared this slot write immediately before set/grow. Consume
			// exactly one synchronous callback without losing the incremental path.
			expectedListenerEvents--;
		} else {
			// Listener callbacks do not identify their process. An undeclared callback
			// is therefore an external/unknown mutation and requires a full refresh.
			dirty = true;
		}
	}

	/** 开始批量输出插入；嵌套调用安全 */
	public void beginBatch() {
		if (batchDepth == 0) {
			// 进入批量模式前，先消费非批量模式积累的 dirty，避免 endBatch 仅更新单进程时丢失其他进程的外部变更
			if (dirty) {
				dirty = false;
				updateAll();
			}
			// Task 7：清空 slotKnown 标志，本次 batch 内由 updateSlotOnly 重新标记已知槽位
			java.util.Arrays.fill(slotKnown, false);
			expectedListenerEvents = 0;
		}
		batchDepth++;
	}

	/**
	 * 结束批量输出插入
	 *
	 * @param process 发生变化的进程索引
	 * @return true 表示本批次确实更新了标志位（调用方需要执行 sorting/unpause）
	 */
	public boolean endBatch(int process) {
		// 防御 batchDepth 下溢：未配对调用 endBatch 时保持为 0，避免标志位永久失效
		if (batchDepth <= 0) {
			batchDepth = 0;
			return false;
		}
		if (--batchDepth == 0) {
			if (dirty || expectedListenerEvents > 0) {
				dirty = false;
				// External changes during a batch are unidentified, so retain the
				// conservative full scan for correctness.
				updateAll();
				return true;
			}
			// A planner batch may mutate slots through a reusable plan and rely solely
			// on the synchronous slot listener. It still belongs to the process passed
			// to endBatch, so aggregate that process without scanning every lane.
			if (hasKnownSlots(process)) {
				updateProcessAggregate(process);
				return true;
			}
		}
		return false;
	}

	/** Declares one manager-owned slot mutation before invoking setStack/growStack. */
	public void expectSlotChange() {
		if (batchDepth > 0) expectedListenerEvents++;
	}

	private boolean hasKnownSlots(int process) {
		if (process < 0 || process >= processHasItems.length) return false;
		int offset = process * 3;
		return slotKnown[offset] || slotKnown[offset + 1] || slotKnown[offset + 2];
	}

	/** 若 dirty 标志被设置，执行一次全量扫描并清除标志 */
	private void flushDirty() {
		if (dirty) {
			dirty = false;
			updateAll();
		}
	}

	/**
	 * 仅重算单个槽位的状态缓存（Task 7 增量更新）
	 * <br/>
	 * 由 {@link PbRecipeCompleter#planAndExecute} 在每次 setStack/growStack 后调用，
	 * 标记该槽位为已知状态，{@link #endBatch} 时 {@link #updateProcessAggregate}
	 * 复用缓存避免重复扫描该槽位。
	 * <p>
	 * 同时维护 identity 缓存（{@link #cachedLimitStacks} / {@link #cachedLimits}），
	 * 与 {@link #isFullCached} 共用同一套缓存逻辑。
	 *
	 * @param process  进程索引
	 * @param slotIdx  槽位索引（0=主输出，1=副输出1，2=副输出2）
	 * @param slot     输出槽（可能为 null）
	 */
	public void updateSlotOnly(int process, int slotIdx, IInventorySlot slot) {
		if (process < 0 || process >= processHasItems.length) return;
		if (slotIdx < 0 || slotIdx >= 3) return;
		int cacheIdx = process * 3 + slotIdx;
		if (slot == null) {
			// null 槽位视为"已满"（AND 聚合时不阻塞 processFull），与 isFullCached 处理一致
			slotHasItems[cacheIdx] = false;
			slotFull[cacheIdx] = true;
			slotItemCount[cacheIdx] = 0;
		} else {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				slotHasItems[cacheIdx] = false;
				slotFull[cacheIdx] = false;
				slotItemCount[cacheIdx] = 0;
			} else {
				slotHasItems[cacheIdx] = true;
				slotItemCount[cacheIdx] = stack.getCount();
				// 复用 isFullCached 的 identity 缓存逻辑
				if (stack == cachedLimitStacks[cacheIdx]) {
					slotFull[cacheIdx] = stack.getCount() >= cachedLimits[cacheIdx];
				} else {
					int limit = slot.getLimit(stack);
					cachedLimitStacks[cacheIdx] = stack;
					cachedLimits[cacheIdx] = limit;
					slotFull[cacheIdx] = stack.getCount() >= limit;
				}
			}
		}
		slotKnown[cacheIdx] = true;
	}

	/** 全量扫描并初始化计数器（初始化时或降级回退用） */
	public void updateAll() {
		hasItemsProcessCount = 0;
		fullProcessCount = 0;
		outputItemCount = 0;
		for (int i = 0; i < processHasItems.length; i++) {
			updateProcessInternal(i);
			if (processHasItems[i]) hasItemsProcessCount++;
			if (processFull[i]) fullProcessCount++;
			outputItemCount += processItemCount[i];
		}
	}

	/** 全量扫描单个进程的 per-slot 状态（不维护全局计数器，由调用方自行维护） */
	private void updateProcessInternal(int process) {
		IInventorySlot primary = context.primaryOutputSlot(process);
		IInventorySlot secondary = context.secondaryOutputSlot(process);
		IInventorySlot tertiary = context.tertiaryOutputSlot(process);

		// 审查问题修复：primary 通常非 null，但为防御未来工厂变体返回 null（与 secondary/tertiary 一致），统一 null 检查
		ItemStack primaryStack = primary == null ? ItemStack.EMPTY : primary.getStack();
		ItemStack secondaryStack = secondary == null ? ItemStack.EMPTY : secondary.getStack();
		// tertiary 也可能为 null（部分工厂未配置第三输出槽），与 secondary 处理保持一致
		ItemStack tertiaryStack = tertiary == null ? ItemStack.EMPTY : tertiary.getStack();

		boolean primaryEmpty = primaryStack.isEmpty();
		boolean secondaryEmpty = secondaryStack.isEmpty();
		boolean tertiaryEmpty = tertiaryStack.isEmpty();

		processHasItems[process] = !primaryEmpty || !secondaryEmpty || !tertiaryEmpty;
		// primary/tertiary 为 null 时视为"已满"（不影响 processFull 判断），与 secondary 处理逻辑一致
		processFull[process] = (primary == null || isFullCached(process, 0, primary))
				&& (secondary == null || isFullCached(process, 1, secondary))
				&& (tertiary == null || isFullCached(process, 2, tertiary));

		// 累加本进程三槽位的物品数量（空槽为 0）
		long count = 0;
		if (!primaryEmpty) count += primaryStack.getCount();
		if (!secondaryEmpty) count += secondaryStack.getCount();
		if (!tertiaryEmpty) count += tertiaryStack.getCount();
		processItemCount[process] = count;
	}

	/**
	 * 聚合 per-slot 状态到 per-process 状态（Task 7 增量更新）
	 * <br/>
	 * 在 {@link #endBatch} 中调用：
	 * 对已通过 {@link #updateSlotOnly} 标记为已知的槽位直接复用 per-slot 缓存，
	 * 未标记的槽位走原始全量扫描路径（与 {@link #isFullCached} 一致的 identity 缓存）。
	 * 聚合后清除本进程的 slotKnown 标志，下次 batch 重新标记。
	 * <p>
	 * 全局计数器（hasItemsProcessCount/fullProcessCount/outputItemCount）维护逻辑
	 * 与 {@link #updateAll} 中聚合后增量更新一致，保证全局状态正确性。
	 *
	 * @param process 进程索引
	 */
	private void updateProcessAggregate(int process) {
		boolean oldHasItems = processHasItems[process];
		boolean oldFull = processFull[process];
		long oldItemCount = processItemCount[process];

		boolean pHasItems = false;
		boolean pFull = true; // AND 聚合，初始为 true
		long pCount = 0;

		IInventorySlot primary = context.primaryOutputSlot(process);
		IInventorySlot secondary = context.secondaryOutputSlot(process);
		IInventorySlot tertiary = context.tertiaryOutputSlot(process);
		IInventorySlot[] slots = {primary, secondary, tertiary};

		for (int slotIdx = 0; slotIdx < 3; slotIdx++) {
			IInventorySlot slot = slots[slotIdx];
			int cacheIdx = process * 3 + slotIdx;
			if (slot == null) {
				// null 槽视为"已满"（AND 聚合不阻塞），不影响 pHasItems/pCount
				continue;
			}
			if (slotKnown[cacheIdx]) {
				// 复用 per-slot 缓存
				pHasItems |= slotHasItems[cacheIdx];
				pFull &= slotFull[cacheIdx];
				pCount += slotItemCount[cacheIdx];
			} else {
				// 未知槽位走原始路径（与 isFullCached 一致）
				ItemStack stack = slot.getStack();
				if (stack.isEmpty()) {
					pFull = false; // 空槽未满
				} else {
					pHasItems = true;
					pCount += stack.getCount();
					if (stack == cachedLimitStacks[cacheIdx]) {
						if (stack.getCount() < cachedLimits[cacheIdx]) pFull = false;
					} else {
						int limit = slot.getLimit(stack);
						cachedLimitStacks[cacheIdx] = stack;
						cachedLimits[cacheIdx] = limit;
						if (stack.getCount() < limit) pFull = false;
					}
				}
			}
		}

		processHasItems[process] = pHasItems;
		processFull[process] = pFull;
		processItemCount[process] = pCount;

		// 维护全局计数器增量（与 updateAll 一致）
		if (oldHasItems != pHasItems) {
			hasItemsProcessCount += pHasItems ? 1 : -1;
		}
		if (oldFull != pFull) {
			fullProcessCount += pFull ? 1 : -1;
		}
		outputItemCount += pCount - oldItemCount;

		// 清除本进程的 slotKnown 标志（下次 batch 重新标记）
		slotKnown[process * 3 + 0] = false;
		slotKnown[process * 3 + 1] = false;
		slotKnown[process * 3 + 2] = false;
	}

	/**
	 * 检查槽位是否已满，带上限 identity 缓存
	 *
	 * @param process  进程索引
	 * @param slotIdx  槽位索引（0=主输出，1=副输出1，2=副输出2）
	 * @param slot     输出槽
	 */
	private boolean isFullCached(int process, int slotIdx, IInventorySlot slot) {
		ItemStack stack = slot.getStack();
		if (stack.isEmpty()) {
			return false;
		}
		int cacheIdx = process * 3 + slotIdx;
		if (stack == cachedLimitStacks[cacheIdx]) {
			return stack.getCount() >= cachedLimits[cacheIdx];
		}
		int limit = slot.getLimit(stack);
		cachedLimitStacks[cacheIdx] = stack;
		cachedLimits[cacheIdx] = limit;
		return stack.getCount() >= limit;
	}
}
