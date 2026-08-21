package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.SameTickFailureGate;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
	 * 蜂箱→离心机直连快速弹出通道
	 * <br/>
	 * 当蜂箱按物品侧面配置（输出面）指向的相邻方块是离心机时，直接将蜂箱输出槽中的蜜脾转移到离心机输入槽；
	 * 未配置任何输出面时回退到任意相邻离心机（兼容旧存档）。
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
	 *   <li>跨机器路由：支持一台蜂箱直连多台离心机，round-robin 起播点轮转实现负载均衡</li>
	 * </ol>
	 * <p>
	 * 性能优化（vs 旧版）：
	 * <ul>
	 *   <li>预扫描数组复用：避免每次 targetSlot.getStack() API 调用，19 输入槽场景节省 19 次调用</li>
	 *   <li>类型缓存查找：复用原生索引缓冲，仅遍历同类型槽位，避免列表与装箱分配</li>
	 *   <li>按负载排序：第二轮按剩余空间降序，避免前几个空槽被优先填满，实现负载均衡</li>
	 *   <li>虚拟栈合并：9 个相同类型输出槽合并为 1 个虚拟栈，循环次数 9 → 1</li>
	 *   <li>短路优化：单类型物品 ≤ 单槽空间时，O(1) 直接插入，跳过两轮遍历</li>
	 * </ul>
	 * <p>
	 * 线程安全：服务端单线程调用，无需同步。
	 */
class ApiaryDirectEjectHandler {

	/** Internal machine-to-machine transfers must bypass the external storage admission policy. */
	static final AutomationType TARGET_INSERT_AUTOMATION = AutomationType.INTERNAL;

	/** 所属蜂箱方块实体 */
	private final TileEntityMekApiary apiary;

	/** 跨机器轮转起始索引 — 用于多台离心机间的负载均衡 */
	private int roundRobinIndex = 0;
	/** Rotating physical source slot for fair partial transfers across output pages. */
	private int outputSourceCursor = 0;

	/** 直连目标扫描器（缓存目标列表 + 配置签名失效） */
	private final ApiaryDirectEjectTargets targets;


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

	/** 同类输出槽合并器（虚拟栈复用，避免每 tick 分配） */
	private final ApiaryOutputMerger outputMerger = new ApiaryOutputMerger();


	/**
	 * 模块5：连续转移失败计数器 — 达阈值后进入墙钟指数退避
	 * <br/>
	 * 服务端单线程访问，无需原子操作。
	 */
	private int consecutiveEjectFailures = 0;

	/** 模块5：连续失败阈值 — 达到后进入墙钟退避（满缓存深度优化） */
	private static final int EJECT_FAILURE_THRESHOLD = 20;

	/**
	 * 直连墙钟退避到期时间（nanoTime）— 连续失败后延迟重试
	 * <br/>
	 * 满缓存深度优化：离心机输入槽持续阻塞时，旧逻辑（计数达阈值后重置）实际每 tick
	 * 仍执行完整重试（预扫描+合并+虚拟栈分配+缓冲区转移），空闲满缓存场景 CPU 浪费。
	 * 墙钟退避对 tick 加速免疫（加速器同刻多次调用不会打穿退避）。
	 */
	private long ejectBackoffUntilNanos = 0L;

	/** 当前直连退避时长 — 指数递增，250ms 起步、2s 封顶（与周期兜底间隔对齐） */
	private long ejectBackoffNanos = 0L;

	/** 进入退避时记录的输出版本 — 版本变化（新产出/槽位/缓冲区变化）立即解除退避 */
	private long ejectBackoffVersion = -1L;

	private static final long INITIAL_EJECT_BACKOFF_NANOS = 250_000_000L;
	private static final long MAX_EJECT_BACKOFF_NANOS = 2_000_000_000L;

	/**
	 * 模块5：复用的离心机输入槽列表 — 避免每 tick 分配 ArrayList
	 * <br/>
	 * 用于把 IMekCentrifugeTile.getInputSlot 逐个收集为 List 传给 ApiaryOutputBuffer。
	 * ArrayList.clear() 不缩容，跨 tick 复用零扩容。
	 */
	private final List<IInventorySlot> bufferInputSlots = new ArrayList<>(8);

	/** 最多缓存的离心机可处理性结果，固定容量防止高混养场景无界增长。 */
	private static final int PROCESSABILITY_CACHE_CAPACITY = 64;
	private final ItemStack[] processabilityStacks = new ItemStack[PROCESSABILITY_CACHE_CAPACITY];
	private final long[] processabilityTargetMasks = new long[PROCESSABILITY_CACHE_CAPACITY];
	/**
	 * 掩码缓存对应的直连目标列表引用 — targets 重建（拓扑变化：离心机增删/侧面配置/朝向变化）时失效。
	 * <br/>
	 * 跨 tick 缓存（原每 gameTick 清空）：离心机优先的 hold 判定被 AE2 推送路径
	 * 高频调用（每输出槽每 tick），isValidInput 内部走配方查找，跨 tick 缓存消除重复调用。
	 */
	private List<ApiaryDirectEjectTargets.Target> maskTargetsRef = null;
	/** 掩码缓存对应的配方版本 — /reload 或数据包重载时失效（蜜脾配方变更会改变可处理性） */
	private long maskRecipeVersion = Long.MIN_VALUE;
	private int processabilityCacheSize;
	private final SameTickFailureGate failedTransferGate = new SameTickFailureGate();

	/**
	 * 构造函数
	 *
	 * @param apiary 所属蜂箱方块实体
	 */
	ApiaryDirectEjectHandler(TileEntityMekApiary apiary) {
		this.apiary = apiary;
		this.targets = new ApiaryDirectEjectTargets(apiary);
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

	/** 侧面路由或直连开关改变后立即丢弃拓扑和可处理性缓存，并解除直连退避。 */
	void onRoutingConfigChanged() {
		targets.clearCache();
		clearProcessabilityCache();
		failedTransferGate.clear();
		clearEjectBackoff();
		markEjectDirty();
	}

	/**
	 * 是否存在相邻离心机可处理该产物（纯拓扑+配方判定，不依赖直连开关）。
	 * <br/>
	 * 供离心机优先判定（{@link TileEntityMekApiary#shouldHoldForCentrifuge}）使用：
	 * 直连开关关闭时蜜脾仍不回 AE，等待 Ejector/管道/玩家收取；
	 * 直连弹出本身的开关检查保留在 {@link #tryDirectEject} 入口。
	 */
	boolean canAnyTargetProcess(ItemStack stack) {
		Level level = apiary.getLevel();
		if (stack == null || stack.isEmpty() || level == null || level.isClientSide) {
			return false;
		}
		List<ApiaryDirectEjectTargets.Target> targetList = targets.findDirectEjectTargets(level);
		return acceptedTargetMask(stack, targetList) != 0L;
	}

	/**
	 * Returns whether a recipe-compatible adjacent centrifuge has room right now. This is
	 * intentionally separate from {@link #canAnyTargetProcess(ItemStack)}: the latter is a
	 * stable recipe/topology query used for routing, while AE2 output arbitration must not hold
	 * honeycomb forever when every compatible input lane is full.
	 */
	boolean canAnyTargetAccept(ItemStack stack) {
		Level level = apiary.getLevel();
		if (stack == null || stack.isEmpty() || level == null || level.isClientSide) return false;
		List<ApiaryDirectEjectTargets.Target> targetList = targets.findDirectEjectTargets(level);
		long acceptedTargets = acceptedTargetMask(stack, targetList);
		if (acceptedTargets == 0L) return false;
		for (int targetIndex = 0; targetIndex < targetList.size() && targetIndex < Long.SIZE; targetIndex++) {
			if ((acceptedTargets & (1L << targetIndex)) == 0L) continue;
			ApiaryDirectEjectTargets.Target target = targetList.get(targetIndex);
			try {
				target.preScanInputSlots();
				int slotCount = target.inputSlotCount;
				if (target.inputSlotManager.prepareSameTypeSlots(stack, slotCount) > 0
						|| target.inputSlotManager.prepareEmptySlotsSortedByRemainingDesc(stack, slotCount) > 0) {
					return true;
				}
			} catch (Exception | LinkageError e) {
				LogThrottle.warn("apiary_hold_capacity_check",
						"离心机输入容量判定异常，按无空间处理: {}", stack.getItem(), e);
			}
		}
		return false;
	}

	/**
	 * Returns a bit mask of adjacent centrifuges accepting this item.
	 * <br/>
	 * 跨 tick 缓存：失效条件为 targets 列表重建（拓扑变化）或配方版本变更，
	 * 输入槽内容物不影响"可处理性"（那是配方层面判定），无需按刻失效。
	 */
	private long acceptedTargetMask(ItemStack stack,
			List<ApiaryDirectEjectTargets.Target> targetList) {
		if (targetList != maskTargetsRef
				|| maskRecipeVersion != ProductiveBeesGenesis.RECIPE_VERSION.get()) {
			clearProcessabilityCache();
			maskTargetsRef = targetList;
			maskRecipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		}
		for (int i = 0; i < processabilityCacheSize; i++) {
			if (ItemStack.isSameItemSameComponents(processabilityStacks[i], stack)) {
				return processabilityTargetMasks[i];
			}
		}
		long acceptedTargets = 0L;
		int targetCount = Math.min(targetList.size(), Long.SIZE);
		for (int i = 0; i < targetCount; i++) {
			try {
				if (targetList.get(i).centrifuge.productivebeesgenesis$isValidInput(stack)) {
					acceptedTargets |= 1L << i;
				}
			} catch (Exception | LinkageError e) {
				// 跨方块实体调用防御：离心机侧异常按"不可处理"降级，不阻断蜂箱 tick
				LogThrottle.warn("apiary_hold_input_check",
						"离心机可处理性判定异常，按不可处理降级: {}", stack.getItem(), e);
			}
		}
		if (processabilityCacheSize < PROCESSABILITY_CACHE_CAPACITY) {
			processabilityStacks[processabilityCacheSize] = stack.copyWithCount(1);
			processabilityTargetMasks[processabilityCacheSize] = acceptedTargets;
			processabilityCacheSize++;
		}
		return acceptedTargets;
	}

	private void clearProcessabilityCache() {
		for (int i = 0; i < processabilityCacheSize; i++) {
			processabilityStacks[i] = null;
		}
		processabilityCacheSize = 0;
	}

	/**
	 * 判断是否应该执行直连弹出检测
	 * <br/>
	 * 脏标记驱动（产出后立即检测）为主要触发源；周期性刷新（每 2 秒兜底）处理 NBT 加载产物、
	 * 离心机后放置等边界场景。墙钟退避（满缓存连续失败后）期间跳过检测，
	 * 输出版本变化（新产出/槽位/缓冲区变化）时立即解除。
	 *
	 * @param outputVersion 当前缓冲区输出版本
	 * @return true 表示应执行检测
	 */
	private boolean shouldCheck(long outputVersion) {
		long now = System.nanoTime();
		if (now < ejectBackoffUntilNanos && outputVersion == ejectBackoffVersion) {
			return false;
		}
		return needsEjectCheck || (now - lastCacheRefreshNanos >= PERIODIC_REFRESH_INTERVAL_NANOS);
	}

	/** 进入直连墙钟指数退避（250ms 起步、倍增、2s 封顶），版本变化时立即解除 */
	private void enterEjectBackoff(long outputVersion) {
		consecutiveEjectFailures = 0;
		ejectBackoffNanos = Math.min(MAX_EJECT_BACKOFF_NANOS,
				Math.max(INITIAL_EJECT_BACKOFF_NANOS,
						SaturatingMath.saturatingMultiply(ejectBackoffNanos, 2)));
		ejectBackoffUntilNanos = System.nanoTime() + ejectBackoffNanos;
		ejectBackoffVersion = outputVersion;
	}

	/** 清除直连退避（成功转移或配置变化后调用） */
	private void clearEjectBackoff() {
		ejectBackoffNanos = 0L;
		ejectBackoffUntilNanos = 0L;
	}

	/**
	 * 产出直连转移 — 产出阶段蜜脾快速通道（跳过蜂箱输出槽中转）
	 * <br/>
	 * 离心机优先 + 直连开启时，{@link BeeProduceProcessor#processBatchProduce} 在分发输出槽前调用：
	 * 蜜脾直接写入相邻离心机输入槽，不再"先写输出槽 → tryDirectEject 弹出"两跳中转。
	 * <ul>
	 *   <li>输出槽保留给非蜜脾产物，降低输出满触发蜜蜂停工（WAITING_OUTPUT）的概率</li>
	 *   <li>离心机输入槽也满时，剩余蜜脾回落输出槽 → 缓冲区 → 直连重试链路（渐进降级）</li>
	 *   <li>输出满阻塞蜜蜂的防溢出语义不受影响（见 BeeSlotTickProcessor 设计注释）</li>
	 * </ul>
	 * <p>
	 * 与 {@link #tryDirectEject} 的区别：低频路径（flush 间隔 ~20 tick，非每 tick），
	 * 源为临时物品列表而非输出槽，故直接 insertItem（内部自动处理同类型堆叠/空槽）
	 * 而非预扫描直写 — 低频场景无需极致优化，保持实现简洁（SRP：与每 tick 高频路径分离）。
	 * 复用 {@link #acceptedTargetMask} 跨 tick 缓存过滤不可处理物品，round-robin 多机负载均衡。
	 * <p>
	 * 性能：getStack 预判跳过类型不匹配的非空槽，避免 insertItem 内部组件比较浪费；
	 * 异常按"该槽拒收"降级隔离（与 acceptedTargetMask 防御一致）。
	 *
	 * @param stacks 产出物品列表（元素会被原地扣减 count）
	 * @return 未能转移的剩余列表（新列表，供输出槽分发）；无可转移时返回原列表
	 */
	List<ItemStack> transferProducedStacks(List<ItemStack> stacks) {
		Level level = apiary.getLevel();
		if (stacks == null || stacks.isEmpty() || level == null || level.isClientSide) {
			return stacks;
		}
		List<ApiaryDirectEjectTargets.Target> targetList = targets.findDirectEjectTargets(level);
		if (targetList.isEmpty()) return stacks;

		List<ItemStack> remaining = null;
		boolean transferredAny = false;
		int start = roundRobinIndex % targetList.size();
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) continue;
			int originalCount = stack.getCount();
			long acceptedTargets = acceptedTargetMask(stack, targetList);
			if (acceptedTargets != 0L) {
				for (int t = 0; t < targetList.size() && !stack.isEmpty(); t++) {
					int targetIndex = (start + t) % targetList.size();
					if ((acceptedTargets & (1L << targetIndex)) == 0L) continue;
					transferStackToTargetInputs(targetList.get(targetIndex), stack);
				}
			}
			if (stack.getCount() < originalCount) {
				// 部分或全部转移成功均视为离心机可接收
				transferredAny = true;
			}
			if (!stack.isEmpty()) {
				if (remaining == null) remaining = new ArrayList<>(stacks.size());
				remaining.add(stack);
			}
		}
		roundRobinIndex = (start + 1) % targetList.size();
		if (transferredAny) {
			// 离心机确认可接收 — 复位直连失败计数与退避，让 tryDirectEject 尽快跟进
			consecutiveEjectFailures = 0;
			clearEjectBackoff();
			failedTransferGate.clear();
		}
		return remaining == null ? stacks : remaining;
	}

	/**
	 * 将单个物品栈转移到指定离心机的输入槽（直写，原地扣减 stack）。
	 * <br/>
	 * getStack 预判跳过类型不匹配的非空槽；insertItem 自动处理同类型堆叠与空槽填入。
	 */
	private void transferStackToTargetInputs(ApiaryDirectEjectTargets.Target target, ItemStack stack) {
		int slotCount = Math.max(0, target.centrifuge.productivebeesgenesis$getInputSlotCount());
		for (int i = 0; i < slotCount && !stack.isEmpty(); i++) {
			IInventorySlot slot = target.centrifuge.productivebeesgenesis$getInputSlot(i);
			if (slot == null) continue;
			ItemStack inSlot = slot.getStack();
			// 预判：类型不匹配的非空槽直接跳过，避免 insertItem 内部组件比较开销
			if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, stack)) continue;
			ItemStack remainder;
			try {
				// This is an internal transfer between two machines owned by this mod. EXTERNAL is
				// reserved for capability/storage-bus callers and activates the factory admission
				// policy, which intentionally limits one item type to one lane per game tick.
				remainder = slot.insertItem(stack, Action.EXECUTE, TARGET_INSERT_AUTOMATION);
			} catch (Exception | LinkageError e) {
				// 单槽异常隔离：按该槽拒收处理，继续尝试下一槽（与 acceptedTargetMask 防御层级统一）
				LogThrottle.warn("apiary_produced_direct_insert",
						"产出直连插入离心机输入槽异常，跳过该槽: {}", stack.getItem(), e);
				continue;
			}
			int accepted = stack.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
			if (accepted > 0) {
				stack.shrink(accepted);
			}
		}
	}

	/**
	 * 执行直连弹出（Task 3/4 优化版 + 模块5 缓冲区转移 + 满缓存墙钟退避）
	 * <br/>
	 * 查找相邻的离心机，将蜂箱输出槽中的有效离心配方输入物品直接转移到离心机输入槽。
	 * 只处理离心机配方输入物品（蜜脾等），蜂笼等其他物品仍由 Ejector 处理。
	 * <p>
	 * 模块5 逻辑：
	 * <ul>
	 *   <li>缓冲区直连转移：输出槽转移后，从 ApiaryOutputBuffer 转移物品到离心机输入槽剩余空间</li>
	 *   <li>退避重置：成功转移后调用 {@link ApiaryOutputBuffer#resetBackoff()} 立即下 tick 重试</li>
	 *   <li>满缓存墙钟退避：连续 20 tick 无法转移时进入指数退避（250ms→2s），
	 *       期间跳过完整重试；输出版本变化（新产出/槽位/缓冲区变化）立即解除，
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
		if (!apiary.isDirectEjectEnabled()) {
			needsEjectCheck = false;
			consecutiveEjectFailures = 0;
			clearEjectBackoff();
			failedTransferGate.clear();
			return false;
		}

		long outputVersion = apiary.getOutputBuffer().getOutputVersion();
		if (!shouldCheck(outputVersion)) return false;
		long gameTick = level.getGameTime();
		if (failedTransferGate.shouldSkip(gameTick, outputVersion)) return false;

		// needsEjectCheck 重置移到方法末尾，部分转移失败时保持脏标记下 tick 立即重试
		lastCacheRefreshNanos = System.nanoTime();

		// 查找直连目标：优先按侧面配置的输出面路由；未配置任何输出面时回退到任意相邻离心机
		List<ApiaryDirectEjectTargets.Target> targetList = this.targets.findDirectEjectTargets(level);
		if (targetList.isEmpty()) {
			if (hasNonEmptyOutputSlot(apiary.getOutputSlots())
					|| apiary.getOutputBuffer().getBufferedGroupCount() > 0) {
				failedTransferGate.recordFailure(gameTick, outputVersion);
			}
			needsEjectCheck = false;
			return true;
		}
		// Snapshot each centrifuge once per batch. The previous product x target scan repeated all 19
		// input-slot reads for every mixed product type, which scaled poorly under accelerated ticks.
		for (ApiaryDirectEjectTargets.Target target : targetList) {
			target.preScanInputSlots();
		}

		List<BasicInventorySlot> outputSlots = apiary.getOutputSlots();

		// Task 4: 合并蜂箱输出槽中相同类型的物品为虚拟栈
		mergeOutputSlotsByType(outputSlots);

		// 模块5：统计本次转移的物品总数（含输出槽+缓冲区），用于决定是否重置退避/递增失败计数
		int transferredCount = 0;

		// 对每个虚拟栈执行跨机器两轮分配（round-robin 起播点实现多台离心机负载均衡）
		int start = roundRobinIndex % targetList.size();
		roundRobinIndex = (start + 1) % targetList.size();
		for (int vIdx = 0; vIdx < outputMerger.size(); vIdx++) {
			ItemStack virtualStack = outputMerger.getVirtualStack(vIdx);
			if (virtualStack.isEmpty()) continue;

			int originalCount = virtualStack.getCount();
			long acceptedTargets = acceptedTargetMask(virtualStack, targetList);
			for (int t = 0; t < targetList.size() && !virtualStack.isEmpty(); t++) {
				int targetIndex = (start + t) % targetList.size();
				if ((acceptedTargets & (1L << targetIndex)) == 0L) continue;
				ApiaryDirectEjectTargets.Target target = targetList.get(targetIndex);
				CentrifugeInputSlotManager inputSlotManager = target.inputSlotManager;
				int inputSlotCount = target.inputSlotCount;
				if (inputSlotCount <= 0) continue;

				// 第一轮：填满同类型非空输入槽（堆叠合并优先）
				int sameTypeSlotCount = inputSlotManager.prepareSameTypeSlots(virtualStack, inputSlotCount);
				for (int order = 0; order < sameTypeSlotCount; order++) {
					if (virtualStack.isEmpty()) break;
					int inputIdx = inputSlotManager.getSameTypeSlotIndex(order);
					virtualStack = tryTransferToInputFromVirtual(
							inputSlotManager, virtualStack, vIdx, inputIdx, false);
				}
				if (virtualStack.isEmpty()) break;

				// 第二轮：使用空输入槽（按剩余空间降序，负载均衡）
				int emptySlotCount = inputSlotManager.prepareEmptySlotsSortedByRemainingDesc(
						virtualStack, inputSlotCount);
				for (int order = 0; order < emptySlotCount; order++) {
					if (virtualStack.isEmpty()) break;
					int inputIdx = inputSlotManager.getEmptySlotIndex(order);
					virtualStack = tryTransferToInputFromVirtual(
							inputSlotManager, virtualStack, vIdx, inputIdx, true);
				}
			}
			// 模块5：累计本次虚拟栈转移的物品数
			transferredCount = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(
					transferredCount, originalCount - virtualStack.getCount()));
		}

		// 模块5：从 ApiaryOutputBuffer 转移物品到所有直连离心机输入槽剩余空间
		// 解决缓冲区持续积压问题（输出槽满载时产物被困缓冲区）
		transferredCount = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(
				transferredCount, tryEjectFromBuffers(targetList)));

		// 模块5：根据转移结果更新退避与失败计数
		ApiaryOutputBuffer outputBuffer = apiary.getOutputBuffer();
		int bufferedGroupCount = outputBuffer.getBufferedGroupCount();
		if (transferredCount > 0) {
			// 成功转移 — 重置缓冲区退避，立即下 tick 重试注入蜂箱输出槽
			outputBuffer.resetBackoff();
			consecutiveEjectFailures = 0;
			clearEjectBackoff();
			failedTransferGate.clear();
		} else if (hasNonEmptyOutputSlot(outputSlots) || bufferedGroupCount > 0) {
			// 有物品但未转移成功 — 递增失败计数，达阈值后进入墙钟指数退避
			// （满缓存深度优化：空闲满缓存从每 tick 全量重试降为退避周期一次；
			//   输出版本变化由 shouldCheck 立即解除退避，新产物零延迟响应）
			consecutiveEjectFailures++;
			if (consecutiveEjectFailures >= EJECT_FAILURE_THRESHOLD) {
				enterEjectBackoff(outputBuffer.getOutputVersion());
			}
			failedTransferGate.recordFailure(gameTick, outputBuffer.getOutputVersion());
		} else {
			failedTransferGate.clear();
		}

		// 检查所有输出槽与缓冲区是否已空，部分失败时保持脏标记下 tick 立即重试
		needsEjectCheck = hasNonEmptyOutputSlot(outputSlots) || bufferedGroupCount > 0;

		return true;
	}

	/**
	 * 模块5：从 ApiaryOutputBuffer 转移物品到所有直连离心机输入槽
	 * <br/>
	 * 当蜂箱输出槽已通过直连弹出清空、缓冲区仍有积压时，复用各离心机输入槽剩余空间
	 * 直接转移缓冲区物品，绕过"缓冲区→蜂箱输出槽→离心机"的两跳路径。
	 * <p>
	 * 性能：
	 * <ul>
	 *   <li>缓冲区为空时通过 getBufferedGroupCount() O(1) 短路（synchronized 但开销极低）</li>
	 *   <li>委托 {@link ApiaryOutputBuffer#tryRedistributeToExternalSlots}，内部使用预扫描直写</li>
	 *   <li>bufferInputSlots 复用，避免每次转移分配 ArrayList</li>
	 * </ul>
	 *
	 * @param targets 直连离心机目标列表
	 * @return 实际转移的物品总数
	 */
	private int tryEjectFromBuffers(
			List<ApiaryDirectEjectTargets.Target> targets) {
		ApiaryOutputBuffer outputBuffer = apiary.getOutputBuffer();
		// O(1) 短路：缓冲区为空时直接返回，避免无效的输入槽收集与 synchronized 调用
		if (outputBuffer.getBufferedGroupCount() <= 0) return 0;

		int total = 0;
		for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
			ApiaryDirectEjectTargets.Target target = targets.get(targetIndex);
			int inputSlotCount = target.inputSlotCount;
			if (inputSlotCount <= 0) continue;

			// 收集该离心机输入槽列表（复用 bufferInputSlots，clear 不缩容）
			bufferInputSlots.clear();
			for (int i = 0; i < inputSlotCount; i++) {
				IInventorySlot slot = target.inputSlotManager.getInputSlot(i);
				if (slot != null) {
					bufferInputSlots.add(slot);
				}
			}
			if (bufferInputSlots.isEmpty()) continue;

			// Reuse the same target mask cache as generated and slotted products. This avoids repeating
			// recipe-manager validation for buffer groups already seen earlier in the production batch.
			long targetBit = 1L << targetIndex;
			total = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(total,
					outputBuffer.tryRedistributeToExternalSlots(
							bufferInputSlots,
							stack -> (acceptedTargetMask(stack, targets) & targetBit) != 0L)));
		}
		return total;
	}

	/**
	 * Task 4: 合并蜂箱输出槽中相同类型的物品为虚拟栈
	 * <br/>
	 * 避免后续循环中重复处理同类型物品。例如 3 个输出槽各有 64 个相同蜜脾，
	 * 合并为 1 个虚拟栈（count=192），一次分配到输入槽，减少 extract/insert 调用次数。
	 * <p>
	 * 注意：虚拟栈仅用于计算分配，实际物品仍需从原始输出槽 extractItem。
	 * {@link ApiaryOutputMerger#getSources(int)} 记录每个虚拟栈的所有源输出槽。
	 *
	 * @param outputSlots 蜂箱输出槽列表
	 */
	private void mergeOutputSlotsByType(List<BasicInventorySlot> outputSlots) {
		outputMerger.clear();
		int slotCount = outputSlots.size();
		int start = RoundRobinSlotTraversal.normalize(outputSourceCursor, slotCount);
		outputSourceCursor = RoundRobinSlotTraversal.advance(start, slotCount);
		for (int offset = 0; offset < slotCount; offset++) {
			outputMerger.add(outputSlots.get(RoundRobinSlotTraversal.index(start, offset, slotCount)));
		}
	}


	/**
	 * Task 3/4: 从虚拟栈向指定输入槽转移物品
	 * <br/>
	 * 虚拟栈的 count 可能超过单个输入槽的剩余空间，需要循环提取直到虚拟栈耗尽或槽位满。
	 * 实际物品从原始输出槽（outputMerger.getSources(virtualIdx)）按顺序 extractItem。
	 * <p>
	 * Task 3 优化：使用预扫描数组值（inputSlotManager.getInputStack/getInputCount/getInputLimit），
	 * 避免每次 targetSlot.getStack() API 调用。转移完成后调用 updateSlotAfterTransfer 同步缓存。
	 *
	 * @param virtualStack 虚拟栈（会被修改 count）
	 * @param virtualIdx  虚拟栈在 outputMerger 中的索引
	 * @param inputIdx    输入槽索引
	 * @param requireEmpty true=只接受空槽（第二轮），false=只接受同类型非空槽（第一轮）
	 * @return 更新后的虚拟栈（可能为空）
	 */
	private ItemStack tryTransferToInputFromVirtual(
			CentrifugeInputSlotManager inputSlotManager, ItemStack virtualStack,
			int virtualIdx, int inputIdx, boolean requireEmpty) {

		if (virtualStack.isEmpty()) return virtualStack;

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
		final IInventorySlot targetSlot = inputSlotManager.getInputSlot(inputIdx);
		if (targetSlot == null) {
			return virtualStack;
		}
		// Reserve only the amount the destination accepts before touching source slots.
		ItemStack simulatedRemainder = targetSlot.insertItem(
				virtualStack.copyWithCount(transferable), Action.SIMULATE, TARGET_INSERT_AUTOMATION);
		int simulatedAccepted = transferable - (simulatedRemainder.isEmpty() ? 0 : simulatedRemainder.getCount());
		if (simulatedAccepted <= 0) return virtualStack;

		// Task 4: 从原始输出槽循环提取（虚拟栈合并了多个输出槽）
		List<BasicInventorySlot> sources = outputMerger.getSources(virtualIdx);
		int remainingToExtract = simulatedAccepted;
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
		ItemStack toInsert = virtualStack.copyWithCount(totalExtracted);
		ItemStack remaining;
		try {
			remaining = targetSlot.insertItem(toInsert, Action.EXECUTE, TARGET_INSERT_AUTOMATION);
		} catch (RuntimeException e) {
			remaining = toInsert;
		}
		int rejectedByTarget = remaining.isEmpty() ? 0 : remaining.getCount();
		int bufferedCount = 0;

		// 防御性检查：若有剩余，放回原始输出槽（理论上不会发生，因为已计算 availableSpace）
		if (!remaining.isEmpty()) {
			for (int i = 0; i < sources.size() && !remaining.isEmpty(); i++) {
				remaining = sources.get(i).insertItem(remaining, Action.EXECUTE, AutomationType.EXTERNAL);
			}
			if (!remaining.isEmpty()) {
				bufferedCount = remaining.getCount();
				apiary.getOutputBuffer().offer(remaining);
			}
		}

		// 记账：E=提取总数, R=目标拒收, B=未能归还源槽而进入缓冲区的数量
		// 插入 = E-R, 归还 = R-B, 缓冲 = B；三者之和 = E，无物品凭空消失。
		int removedFromSources = netRemovedFromSources(totalExtracted, rejectedByTarget, bufferedCount);
		// Restored items remain represented by the virtual stack; inserted and buffered items do not.
		virtualStack.shrink(removedFromSources);

		// Task 3: 同步预扫描数组状态（避免下次循环读取脏数据）
		ItemStack newTargetStack = targetSlot.getStack();
		int newTargetCount = newTargetStack.getCount();
		inputSlotManager.updateSlotAfterTransfer(inputIdx, newTargetStack, newTargetCount);
		return virtualStack;
	}

	static int netRemovedFromSources(int extracted, int rejectedByTarget, int buffered) {
		int safeExtracted = Math.max(0, extracted);
		int safeRejected = Math.min(safeExtracted, Math.max(0, rejectedByTarget));
		int safeBuffered = Math.min(safeRejected, Math.max(0, buffered));
		return safeExtracted - (safeRejected - safeBuffered);
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

}
