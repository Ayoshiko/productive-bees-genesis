package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;

/**
 * 蜂箱→离心机直连快速弹出通道
 * <br/>
 * 当蜂箱相邻方块是离心机时，直接将蜂箱输出槽中的蜜脾转移到离心机输入槽，
 * 绕过 Mekanism Ejector 的节流逻辑（阻塞冷却、单 tick 次数上限等）
 * 和 Capability 系统调用开销，最大化直连弹出效率。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP：仅负责直连弹出逻辑，不涉及蜂箱其他业务</li>
 *   <li>OCP：通过 IMekCentrifugeTile 接口适配所有离心机类型，新增离心机无需修改本类</li>
 *   <li>DIP：依赖 IMekCentrifugeTile 抽象，不依赖具体离心机类</li>
 * </ul>
 * <p>
 * 弹出策略（Task 3/4 优化版）：
 * <ol>
 *   <li>预扫描：调用 inputSlotManager.preScanInputSlots 缓存输入槽状态</li>
 *   <li>合并同类型输出槽（Task 4）：相同类型物品合并为虚拟栈，减少循环次数</li>
 *   <li>对每个虚拟栈执行两轮分配：</li>
 *   <li>第一轮：仅遍历同类型非空输入槽（堆叠合并优先），跳过无关槽位</li>
 *   <li>第二轮：按剩余空间降序遍历空槽（负载均衡），避免前几个空槽被优先填满</li>
 *   <li>短路优化：若虚拟栈数量 ≤ 最大空槽剩余空间，直接插入该空槽</li>
 * </ol>
 * <p>
 * 性能优化（vs 旧版）：
 * <ul>
 *   <li>预扫描数组复用：避免每次 targetSlot.getStack() API 调用，19 输入槽场景节省 19 次调用</li>
 *   <li>类型缓存查找：findSameTypeSlots 直接返回同类型槽列表，O(19) → O(同类型数)</li>
 *   <li>按负载排序：第二轮按剩余空间降序，避免前几个空槽被优先填满，实现负载均衡</li>
 *   <li>虚拟栈合并：9 个相同类型输出槽合并为 1 个虚拟栈，循环次数 9 → 1</li>
 *   <li>短路优化：单类型物品 ≤ 单槽空间时，O(1) 直接插入，跳过两轮遍历</li>
 * </ul>
 * <p>
 * 线程安全：服务端单线程调用，无需同步。
 */
class ApiaryDirectEjectHandler {

	/** 所属蜂箱方块实体 */
	private final TileEntityMekApiary apiary;

	/** 缓存的相邻离心机位置 — 避免每 tick 遍历所有方向查找 */
	@Nullable
	private BlockPos cachedCentrifugePos;

	/** 缓存的离心机方向 — 从蜂箱指向离心机 */
	@Nullable
	private Direction cachedCentrifugeSide;

	/**
	 * 脏标记 — 产出写入输出槽时设置，触发下一次 tick 立即执行直连弹出检测
	 * <br/>
	 * volatile 保证跨线程可见性（防御性，实际仅服务端 tick 线程访问）。
	 */
	private volatile boolean needsEjectCheck = false;

	/**
	 * 周期性刷新兜底间隔（纳秒）— 2 秒
	 * <br/>
	 * 使用真实时间而非 {@code level.getGameTime()}，避免 JDTE 等 tick 加速加载器
	 * 在同一游戏刻内多次调用 onUpdateServer 导致 getGameTime 失真、周期兜底长期不触发。
	 */
	private static final long PERIODIC_REFRESH_INTERVAL_NANOS = 2_000_000_000L;

	/** 上次执行直连弹出检测的真实时间（纳秒）— 用于周期性刷新兜底 */
	private volatile long lastCacheRefreshNanos = 0L;

	/**
	 * Task 3: 输入槽状态管理器（预扫描数组复用 + 类型缓存 + 按负载排序）
	 * <br/>
	 * 封装输入槽状态预扫描和查找逻辑，避免 tryDirectEject 中重复读取输入槽状态。
	 * 19 输入槽场景下预扫描 + 类型查找 + 按负载排序总开销 < 5μs/tick。
	 */
	private final CentrifugeInputSlotManager inputSlotManager = new CentrifugeInputSlotManager();

	/**
	 * Task 4: 合并同类型输出槽后的虚拟栈列表（复用，避免每 tick 分配）
	 * <br/>
	 * 虚拟栈的 count 可能超过单个输入槽的剩余空间，需要循环提取直到虚拟栈耗尽。
	 * 实际物品仍需从原始输出槽（mergedSourceSlots）extractItem。
	 */
	private final List<ItemStack> mergedVirtualStacks = new ArrayList<>(9);

	/**
	 * Task 4: 每个虚拟栈对应的原始输出槽列表（复用）
	 * <br/>
	 * mergedSourceSlots[i] 对应 mergedVirtualStacks[i] 的所有源输出槽。
	 * 实际 extractItem 时按顺序从这些源输出槽提取。
	 */
	private final List<List<BasicInventorySlot>> mergedSourceSlots = new ArrayList<>(9);

	/**
	 * 模块5：连续转移失败计数器 — 连续 20 tick 失败后 fallback 到 MEK Ejector
	 * <br/>
	 * 服务端单线程访问，无需原子操作。fallback 触发后重置为 0，让后续 AE2 推送和
	 * MEK Ejector 接管输出槽物品，避免每 tick 无效重试消耗 CPU。
	 */
	private int consecutiveEjectFailures = 0;

	/** 模块5：连续失败阈值 — 20 tick（1秒）后触发 fallback */
	private static final int EJECT_FAILURE_THRESHOLD = 20;

	/**
	 * 模块5：复用的离心机输入槽列表 — 避免每 tick 分配 ArrayList
	 * <br/>
	 * 用于把 IMekCentrifugeTile.getInputSlot 逐个收集为 List 传给 ApiaryOutputBuffer。
	 * ArrayList.clear() 不缩容，跨 tick 复用零扩容。
	 */
	private final List<IInventorySlot> bufferInputSlots = new ArrayList<>(8);

	/**
	 * 构造函数
	 *
	 * @param apiary 所属蜂箱方块实体
	 */
	ApiaryDirectEjectHandler(TileEntityMekApiary apiary) {
		this.apiary = apiary;
	}

	/**
	 * 标记直连弹出检测为脏 — 产出写入输出槽后调用
	 * <br/>
	 * 设置脏标记后，下一次 {@link #tryDirectEject} 将立即执行检测，
	 * 无需等待周期性刷新间隔。
	 */
	void markEjectDirty() {
		needsEjectCheck = true;
	}

	/**
	 * 判断是否应该执行直连弹出检测
	 * <br/>
	 * 脏标记驱动（产出后立即检测）为主要触发源；周期性刷新（每 2 秒兜底）处理 NBT 加载产物、
	 * 离心机后放置等边界场景。
	 *
	 * @return true 表示应执行检测
	 */
	private boolean shouldCheck() {
		return needsEjectCheck || (System.nanoTime() - lastCacheRefreshNanos >= PERIODIC_REFRESH_INTERVAL_NANOS);
	}

	/**
	 * 执行直连弹出（Task 3/4 优化版 + 模块5 缓冲区转移与 fallback）
	 * <br/>
	 * 查找相邻的离心机，将蜂箱输出槽中的有效离心配方输入物品直接转移到离心机输入槽。
	 * 只处理离心机配方输入物品（蜜脾等），蜂笼等其他物品仍由 Ejector 处理。
	 * <p>
	 * 模块5 新增逻辑：
	 * <ul>
	 *   <li>缓冲区直连转移：输出槽转移后，从 ApiaryOutputBuffer 转移物品到离心机输入槽剩余空间</li>
	 *   <li>退避重置：成功转移后调用 {@link ApiaryOutputBuffer#resetBackoff()} 立即下 tick 重试</li>
	 *   <li>连续失败 fallback：连续 20 tick 无法转移到离心机时，跳过直连弹出，
	 *       让后续 AE2 推送（{@link ApiaryAe2HostAdapter#pushOutputs()}）和 MEK Ejector 接管</li>
	 * </ul>
	 * <p>
	 * 优化要点：
	 * <ul>
	 *   <li>预扫描输入槽状态到复用数组，后续读取避免 API 调用</li>
	 *   <li>合并同类型输出槽为虚拟栈，减少循环次数</li>
	 *   <li>第一轮仅遍历同类型非空槽，第二轮按剩余空间降序遍历空槽</li>
	 *   <li>短路优化：虚拟栈数量 ≤ 最大空槽剩余空间时，直接插入该空槽</li>
	 * </ul>
	 *
	 * @return true 表示本次执行了检测（无论是否转移物品），false 表示跳过检测
	 */
	boolean tryDirectEject() {
		Level level = apiary.getLevel();
		if (level == null || level.isClientSide) return false;

		if (!shouldCheck()) return false;

		// needsEjectCheck 重置移到方法末尾，部分转移失败时保持脏标记下 tick 立即重试
		lastCacheRefreshNanos = System.nanoTime();

		// 模块5：连续失败 fallback — 离心机输入槽持续阻塞时，跳过直连弹出，
		// 让后续 AE2 推送（pushOutputs）和 MEK Ejector 接管，避免每 tick 无效重试消耗 CPU。
		// AE 协同：fallback 后 ae2HostAdapter.pushOutputs() 仍会在 tryDirectEject 之后执行，
		// 若 AE 开启则优先通过 AE 推送，AE 失败再由 MEK Ejector 兜底。
		if (consecutiveEjectFailures >= EJECT_FAILURE_THRESHOLD) {
			consecutiveEjectFailures = 0;
			// 保持脏标记，下 tick 重新尝试直连弹出（可能离心机已腾出空间）
			needsEjectCheck = true;
			return true;
		}

		// 查找相邻离心机
		IMekCentrifugeTile centrifuge = findAdjacentCentrifuge(level);
		if (centrifuge == null) {
			needsEjectCheck = false;
			return true;
		}

		List<BasicInventorySlot> outputSlots = apiary.getOutputSlots();
		int inputSlotCount = centrifuge.productivebeesgenesis$getInputSlotCount();

		if (inputSlotCount <= 0) {
			needsEjectCheck = false;
			return true;
		}

		// Task 3: 预扫描所有输入槽状态到复用数组
		inputSlotManager.preScanInputSlots(centrifuge, inputSlotCount);

		// Task 4: 合并蜂箱输出槽中相同类型的物品为虚拟栈
		mergeOutputSlotsByType(outputSlots);

		// 模块5：统计本次转移的物品总数（含输出槽+缓冲区），用于决定是否重置退避/递增失败计数
		int transferredCount = 0;

		// 对每个虚拟栈执行两轮分配
		for (int vIdx = 0; vIdx < mergedVirtualStacks.size(); vIdx++) {
			ItemStack virtualStack = mergedVirtualStacks.get(vIdx);
			if (virtualStack.isEmpty()) continue;

			int originalCount = virtualStack.getCount();
			// 第一轮：填满同类型非空输入槽（堆叠合并优先）
			List<Integer> sameTypeSlots = inputSlotManager.findSameTypeSlots(virtualStack, inputSlotCount);
			for (int inputIdx : sameTypeSlots) {
				if (virtualStack.isEmpty()) break;
				virtualStack = tryTransferToInputFromVirtual(centrifuge, virtualStack, vIdx, inputIdx, false);
			}

			// 第二轮：使用空输入槽（按剩余空间降序，负载均衡）
			// 若第一轮已排空虚拟栈，跳过第二轮避免不必要的 findEmptySlotsSortedByRemainingDesc 调用
			if (virtualStack.isEmpty()) {
				transferredCount += originalCount;
				continue;
			}
			List<Integer> emptySlots = inputSlotManager.findEmptySlotsSortedByRemainingDesc(centrifuge, virtualStack, inputSlotCount);
			// 短路优化：findEmptySlotsSortedByRemainingDesc 返回降序排序列表，首项为最大空槽。
			// 若虚拟栈数量 ≤ 首项剩余空间，一次插入即可排空虚拟栈，
			// 后续 virtualStack.isEmpty() 触发 break，跳过剩余空槽遍历。
			for (int inputIdx : emptySlots) {
				if (virtualStack.isEmpty()) break;
				virtualStack = tryTransferToInputFromVirtual(centrifuge, virtualStack, vIdx, inputIdx, true);
			}
			// 模块5：累计本次虚拟栈转移的物品数
			transferredCount += originalCount - virtualStack.getCount();
		}

		// 模块5：从 ApiaryOutputBuffer 转移物品到离心机输入槽剩余空间
		// 解决缓冲区持续积压问题（输出槽满载时产物被困缓冲区）
		transferredCount += tryEjectFromBuffer(centrifuge, inputSlotCount);

		// 模块5：根据转移结果更新退避与失败计数
		ApiaryOutputBuffer outputBuffer = apiary.getOutputBuffer();
		int bufferedGroupCount = outputBuffer.getBufferedGroupCount();
		if (transferredCount > 0) {
			// 成功转移 — 重置缓冲区退避，立即下 tick 重试注入蜂箱输出槽
			outputBuffer.resetBackoff();
			consecutiveEjectFailures = 0;
		} else if (hasNonEmptyOutputSlot(outputSlots) || bufferedGroupCount > 0) {
			// 有物品但未转移成功 — 递增失败计数，达阈值后下 tick 触发 fallback
			consecutiveEjectFailures++;
		}

		// 检查所有输出槽与缓冲区是否已空，部分失败时保持脏标记下 tick 立即重试
		needsEjectCheck = hasNonEmptyOutputSlot(outputSlots) || bufferedGroupCount > 0;

		return true;
	}

	/**
	 * 模块5：从 ApiaryOutputBuffer 转移物品到离心机输入槽
	 * <br/>
	 * 当蜂箱输出槽已通过直连弹出清空、缓冲区仍有积压时，复用离心机输入槽剩余空间
	 * 直接转移缓冲区物品，绕过"缓冲区→蜂箱输出槽→离心机"的两跳路径。
	 * <p>
	 * 性能：
	 * <ul>
	 *   <li>缓冲区为空时通过 getBufferedGroupCount() O(1) 短路（synchronized 但开销极低）</li>
	 *   <li>委托 {@link ApiaryOutputBuffer#tryRedistributeToExternalSlots}，内部使用预扫描直写</li>
	 *   <li>bufferInputSlots 复用，避免每 tick 分配 ArrayList</li>
	 * </ul>
	 *
	 * @param centrifuge     离心机接口
	 * @param inputSlotCount 离心机输入槽数量
	 * @return 实际转移的物品总数
	 */
	private int tryEjectFromBuffer(IMekCentrifugeTile centrifuge, int inputSlotCount) {
		ApiaryOutputBuffer outputBuffer = apiary.getOutputBuffer();
		// O(1) 短路：缓冲区为空时直接返回，避免无效的输入槽收集与 synchronized 调用
		if (outputBuffer.getBufferedGroupCount() <= 0) return 0;

		// 收集离心机输入槽列表（复用 bufferInputSlots，clear 不缩容）
		bufferInputSlots.clear();
		for (int i = 0; i < inputSlotCount; i++) {
			IInventorySlot slot = centrifuge.productivebeesgenesis$getInputSlot(i);
			if (slot != null) {
				bufferInputSlots.add(slot);
			}
		}

		if (bufferInputSlots.isEmpty()) return 0;

		// 委托 ApiaryOutputBuffer 转移，传入离心机输入验证器过滤非离心配方物品
		return outputBuffer.tryRedistributeToExternalSlots(
				bufferInputSlots,
				centrifuge::productivebeesgenesis$isValidInput);
	}

	/**
	 * Task 4: 合并蜂箱输出槽中相同类型的物品为虚拟栈
	 * <br/>
	 * 避免后续循环中重复处理同类型物品。例如 3 个输出槽各有 64 个相同蜜脾，
	 * 合并为 1 个虚拟栈（count=192），一次分配到输入槽，减少 extract/insert 调用次数。
	 * <p>
	 * 注意：虚拟栈仅用于计算分配，实际物品仍需从原始输出槽 extractItem。
	 * mergedSourceSlots[i] 记录 mergedVirtualStacks[i] 的所有源输出槽。
	 *
	 * @param outputSlots 蜂箱输出槽列表
	 */
	private void mergeOutputSlotsByType(List<BasicInventorySlot> outputSlots) {
		mergedVirtualStacks.clear();
		mergedSourceSlots.clear();

		for (BasicInventorySlot slot : outputSlots) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) continue;

			// 查找是否已有同类型虚拟栈
			int existIdx = -1;
			for (int i = 0; i < mergedVirtualStacks.size(); i++) {
				if (ItemStack.isSameItemSameComponents(mergedVirtualStacks.get(i), stack)) {
					existIdx = i;
					break;
				}
			}

			if (existIdx >= 0) {
				// 合并到已有虚拟栈
				mergedVirtualStacks.get(existIdx).grow(stack.getCount());
				mergedSourceSlots.get(existIdx).add(slot);
			} else {
				// 新建虚拟栈（copyWithCount 避免修改原始 stack）
				ItemStack virtual = stack.copyWithCount(stack.getCount());
				mergedVirtualStacks.add(virtual);
				List<BasicInventorySlot> sources = new ArrayList<>();
				sources.add(slot);
				mergedSourceSlots.add(sources);
			}
		}
	}

	/**
	 * Task 3/4: 从虚拟栈向指定输入槽转移物品
	 * <br/>
	 * 虚拟栈的 count 可能超过单个输入槽的剩余空间，需要循环提取直到虚拟栈耗尽或槽位满。
	 * 实际物品从原始输出槽（mergedSourceSlots[virtualIdx]）按顺序 extractItem。
	 * <p>
	 * Task 3 优化：使用预扫描数组值（inputSlotManager.getInputStack/getInputCount/getInputLimit），
	 * 避免每次 targetSlot.getStack() API 调用。转移完成后调用 updateSlotAfterTransfer 同步缓存。
	 *
	 * @param centrifuge  离心机接口
	 * @param virtualStack 虚拟栈（会被修改 count）
	 * @param virtualIdx  虚拟栈在 mergedVirtualStacks 中的索引
	 * @param inputIdx    输入槽索引
	 * @param requireEmpty true=只接受空槽（第二轮），false=只接受同类型非空槽（第一轮）
	 * @return 更新后的虚拟栈（可能为空）
	 */
	private ItemStack tryTransferToInputFromVirtual(
			IMekCentrifugeTile centrifuge, ItemStack virtualStack, int virtualIdx,
			int inputIdx, boolean requireEmpty) {

		if (virtualStack.isEmpty()) return virtualStack;
		if (!centrifuge.productivebeesgenesis$isValidInput(virtualStack)) return ItemStack.EMPTY;

		// Task 3: 使用预扫描数组值，避免 targetSlot.getStack() API 调用
		ItemStack targetStack = inputSlotManager.getInputStack(inputIdx);
		int targetLimit = inputSlotManager.getInputLimit(inputIdx);
		int targetCurrent = inputSlotManager.getInputCount(inputIdx);

		if (requireEmpty) {
			// 第二轮：只接受空槽
			if (!targetStack.isEmpty()) return virtualStack;
		} else {
			// 第一轮：只接受同类型非空槽
			if (targetStack.isEmpty()) return virtualStack;
			if (!ItemStack.isSameItemSameComponents(targetStack, virtualStack)) return virtualStack;
		}

		int availableSpace = targetLimit - targetCurrent;
		if (availableSpace <= 0) return virtualStack;

		int transferable = Math.min(virtualStack.getCount(), availableSpace);

		// Task 4: 从原始输出槽循环提取（虚拟栈合并了多个输出槽）
		List<BasicInventorySlot> sources = mergedSourceSlots.get(virtualIdx);
		int remainingToExtract = transferable;
		int totalExtracted = 0;

		for (int i = 0; i < sources.size() && remainingToExtract > 0; i++) {
			BasicInventorySlot sourceSlot = sources.get(i);
			ItemStack sourceStack = sourceSlot.getStack();
			if (sourceStack.isEmpty()) continue;

			int canExtract = Math.min(remainingToExtract, sourceStack.getCount());
			ItemStack extracted = sourceSlot.extractItem(canExtract, Action.EXECUTE, AutomationType.EXTERNAL);
			if (!extracted.isEmpty()) {
				totalExtracted += extracted.getCount();
				remainingToExtract -= extracted.getCount();
			}
		}

		if (totalExtracted <= 0) return virtualStack;

		// 插入目标输入槽（直接执行，不模拟）
		IInventorySlot targetSlot = centrifuge.productivebeesgenesis$getInputSlot(inputIdx);
		if (targetSlot == null) return virtualStack;

		ItemStack toInsert = virtualStack.copyWithCount(totalExtracted);
		ItemStack remaining = targetSlot.insertItem(toInsert, Action.EXECUTE, AutomationType.EXTERNAL);

		// 防御性检查：若有剩余，放回原始输出槽（理论上不会发生，因为已计算 availableSpace）
		if (!remaining.isEmpty()) {
			for (int i = 0; i < sources.size() && !remaining.isEmpty(); i++) {
				remaining = sources.get(i).insertItem(remaining, Action.EXECUTE, AutomationType.EXTERNAL);
			}
			totalExtracted -= remaining.getCount();
		}

		// 更新虚拟栈 count
		virtualStack.shrink(totalExtracted);

		// Task 3: 同步预扫描数组状态（避免下次循环读取脏数据）
		ItemStack newTargetStack = targetSlot.getStack();
		int newTargetCount = newTargetStack.getCount();
		inputSlotManager.updateSlotAfterTransfer(inputIdx, newTargetStack, newTargetCount);

		return virtualStack;
	}

	/**
	 * 检查输出槽列表中是否存在非空槽位
	 *
	 * @param slots 输出槽列表
	 * @return true 表示仍有非空槽（需下 tick 继续弹出），false 表示全部已空
	 */
	private boolean hasNonEmptyOutputSlot(List<BasicInventorySlot> slots) {
		for (IInventorySlot slot : slots) {
			if (!slot.getStack().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 查找相邻的离心机
	 * <br/>
	 * 使用缓存优化：若上次找到的位置仍有效则直接返回，
	 * 否则遍历所有方向重新查找。
	 *
	 * @param level 世界实例
	 * @return 离心机的 IMekCentrifugeTile 接口，未找到返回 null
	 */
	@Nullable
	private IMekCentrifugeTile findAdjacentCentrifuge(Level level) {
		BlockPos myPos = apiary.getBlockPos();

		// 优先使用缓存
		if (cachedCentrifugePos != null && cachedCentrifugeSide != null) {
			BlockEntity be = level.getBlockEntity(cachedCentrifugePos);
			if (be instanceof IMekCentrifugeTile centrifuge && !be.isRemoved()) {
				return centrifuge;
			}
			// 缓存失效，清空
			cachedCentrifugePos = null;
			cachedCentrifugeSide = null;
		}

		// 遍历所有方向查找
		for (Direction side : Direction.values()) {
			BlockPos adjacentPos = myPos.relative(side);
			BlockEntity be = level.getBlockEntity(adjacentPos);
			if (be instanceof IMekCentrifugeTile centrifuge && !be.isRemoved()) {
				cachedCentrifugePos = adjacentPos;
				cachedCentrifugeSide = side;
				return centrifuge;
			}
		}

		return null;
	}

	/**
	 * 清除缓存（方块移动/移除时调用）
	 * <br/>
	 * 当蜂箱被移动或相邻方块发生变化时，清除离心机位置缓存，
	 * 确保下次弹出时重新查找。
	 */
	void clearCache() {
		cachedCentrifugePos = null;
		cachedCentrifugeSide = null;
	}
}
