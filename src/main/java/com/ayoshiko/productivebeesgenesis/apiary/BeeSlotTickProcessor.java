package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
	 * 蜜蜂槽位 tick 处理器 — 从 {@link ApiaryTickHandler} 拆分，负责蜜蜂槽位的服务端 tick 处理逻辑。
	 * <br/>
	 * 职责：遍历蜜蜂槽推进生产计时、累积产出按 EntityType 分组批量刷新（Task 16）、
	 * 统一提取能量（v9-P3 try-finally）、配置缓存（每 100 tick 刷新）。
	 * <p>
	 * 由 {@link ApiaryTickHandler} 在每服务端 tick 委托调用，蜂笼输入由 {@link CageTickProcessor}
	 * 在本处理器之前执行，确保蜜蜂从蜂笼转移到蜂槽后再推进生产逻辑。
	 * <p>
	 * 线程安全：方块实体在服务端单线程执行，AtomicInteger 提供防御性原子计数。
	 *
	 * @since 1.0.0
	 */
class BeeSlotTickProcessor {

	/**
	 * 批量产出刷新间隔（tick）
	 * <br/>
	 * 每 10 tick（0.5秒）刷新一次累积的产出，将多次小产出合并为一次批量处理，
	 * 显著降低高频场景下的容器操作与状态切换开销。
	 * Task 3: 从 20 tick（1秒）降至 10 tick（0.5秒），降低非加速下产出→弹出的感知延迟。
	 */
	private static final int BATCH_FLUSH_INTERVAL = 10;

	/**
	 * 累积产出提前刷新阈值的判定已移至 {@link ApiaryFlushPolicy}。
	 * <br/>
	 * 基础阈值 {@link ApiaryFlushPolicy#BASE_ACCUMULATION_THRESHOLD} 按本刻批量倍率放大：
	 * Spark 分析显示满升级场景 MSPT max=54.6ms，主因是每 10 tick 一次的批量 flush
	 * 瞬间处理数百次累积产出，故用累积量提前触发把大批量拆小；但 CREATIVE 升级下
	 * 单只蜜蜂单刻即累积 tickMultiplier 次产出，固定阈值会让提前 flush 每刻触发，
	 * 反而把 flush 的固定开销放大 10 倍。
	 */

	/** 所属方块实体引用 */
	private final TileEntityMekApiary tile;

	/** 槽位管理器引用 */
	private final ApiarySlotManager slotManager;

	/** 产出处理器引用 */
	private final BeeProduceProcessor produceProcessor;

	/** 升级处理器引用 */
	private final ApiaryUpgradeHandler upgradeHandler;

	/** 喂食器管理器引用 — 花朵检查 */
	private final FeederSlotManager feederManager;

	/** 转化处理器引用 — 物品转化与方块转化（均以饲养板 BlockItem 为转化目标） */
	private final ApiaryConversionProcessor conversionProcessor;

	/** O(1) 激活状态计数器 — 封装每槽位 CAS 守卫与 workingCount 增量维护 */
	private final ApiaryBeeActivationCounter activationCounter;

	/** 蜜蜂槽位处理异常日志冷却器（tick 模式） */
	private final LogThrottle slotErrorThrottle;

	/** 配置缓存 — basic 配置值（每 100 tick 刷新一次） */
	private final ApiaryConfigCache configCache;

	/**
	 * 累积产出计数器（Task 16.2）
	 * <br/>
	 * 统计自上次批量刷新以来累积的产出次数（所有蜜蜂合计）。
	 * 使用 AtomicInteger 提供防御性原子计数，便于跨线程状态查询。
	 */
	private final AtomicInteger accumulatedProgress = new AtomicInteger(0);

	/** 自上次批量刷新以来的 tick 计数（仅服务端 tick 线程访问） */
	private int tickCounter = 0;

	/** 当前 tick 的批量倍率（Tick 加速检测器设置，默认 1 表示无加速） — 由 ApiaryTickHandler 在 tick 入口设置，使用后自动重置 */
	private int tickMultiplier = 1;

	/** 每批蜜蜂遍历的起点，避免能量紧张时固定由前部槽位占满预算。 */
	private int beeSlotRotationIndex = 0;

	/**
	 * 每只蜜蜂累积的待产出次数（Task 16.2）
	 * <br/>
	 * 索引与 {@link ApiarySlotManager#getBeeSlots()} 数组一一对应。
	 * 仅服务端 tick 线程读写，无需同步。
	 * 在构造时按蜜蜂槽位数初始化，避免运行时扩容。
	 */
	private final int[] pendingProductions;

	/**
	 * 每只蜜蜂上次解析 beeData 的引用（用于 BeeTypeKey 缓存）。
	 * <br/>
	 * {@link BeeSlot#setBeeData} 在 NBT 实质变化时才会更新引用，因此引用相等可作为缓存命中依据。
	 */
	private final CompoundTag[] lastCheckedBeeData;

	/**
	 * 每只蜜蜂缓存的 BeeTypeKey（避免每 tick 重复解析 NBT 字符串）。
	 */
	private final ResourceLocation[] cachedBeeTypeKeys;

	/** 每只蜜蜂缓存的行为与天气耐受基因，仅在 beeData 引用变化后重新解析。 */
	private final BeeWorkConditionEvaluator.WorkTraits[] cachedBeeWorkTraits;

	/**
	 * 主循环预算的蜜蜂类型键数组 — 供 flushPendingProductions 复用，避免重复调用 resolveBeeTypeKeyForSlot。
	 * <br/>
	 * 在 {@link #tick()} 主循环中每只非空蜜蜂写入一次（与 {@link #cachedBeeTypeKeys} 同步更新），
	 * {@link #flushPendingProductions} 直接读取本数组，跳过 resolveBeeTypeKeyForSlot 的二次调用。
	 * 仅服务端 tick 线程访问，无需同步。
	 */
	private final ResourceLocation[] beeTypeKeyBySlot;

	/** Reused eligibility snapshot for fair partial-batch energy allocation. */
	private final boolean[] runnableBeeSlots;

	/** Reusable open-addressed groups for pending production slots. */
	private final PendingProductionGroup[] pendingProductionGroups;
	private final int[] pendingProductionGroupTable;
	private int activePendingProductionGroupCount;

	/**
	 * 构造蜜蜂槽位 tick 处理器
	 *
	 * @param tile              所属方块实体
	 * @param slotManager       槽位管理器
	 * @param produceProcessor  产出处理器
	 * @param upgradeHandler    升级处理器
	 * @param feederManager     喂食器管理器（花朵检查）
	 * @param activationCounter 激活状态计数器（与 {@link ApiaryTickHandler} 共享同一实例）
	 * @param slotErrorThrottle 异常日志冷却器（与 {@link ApiaryTickHandler} 共享同一实例）
	 * @param conversionProcessor 转化处理器（饲养板 BlockItem 物品/方块转化）
	 */
	BeeSlotTickProcessor(TileEntityMekApiary tile, ApiarySlotManager slotManager,
			BeeProduceProcessor produceProcessor, ApiaryUpgradeHandler upgradeHandler,
			FeederSlotManager feederManager, ApiaryBeeActivationCounter activationCounter,
			LogThrottle slotErrorThrottle, ApiaryConversionProcessor conversionProcessor) {
		this.tile = tile;
		this.slotManager = slotManager;
		this.produceProcessor = produceProcessor;
		this.upgradeHandler = upgradeHandler;
		this.feederManager = feederManager;
		this.activationCounter = activationCounter;
		this.slotErrorThrottle = slotErrorThrottle;
		this.configCache = ApiaryConfigCache.create();
		this.conversionProcessor = conversionProcessor;
		this.pendingProductions = new int[slotManager.getBeeSlotCount()];
		this.lastCheckedBeeData = new CompoundTag[slotManager.getBeeSlotCount()];
		this.cachedBeeTypeKeys = new ResourceLocation[slotManager.getBeeSlotCount()];
		this.cachedBeeWorkTraits = new BeeWorkConditionEvaluator.WorkTraits[slotManager.getBeeSlotCount()];
		this.beeTypeKeyBySlot = new ResourceLocation[slotManager.getBeeSlotCount()];
		this.runnableBeeSlots = new boolean[slotManager.getBeeSlotCount()];
		this.pendingProductionGroups = new PendingProductionGroup[slotManager.getBeeSlotCount()];
		for (int i = 0; i < pendingProductionGroups.length; i++) {
			pendingProductionGroups[i] = new PendingProductionGroup();
		}
		int tableSize = 1;
		while (tableSize < Math.max(2, pendingProductionGroups.length * 2)) tableSize <<= 1;
		this.pendingProductionGroupTable = new int[tableSize];
	}

	/**
	 * 设置本 tick 的批量倍率（由 ApiaryTickHandler 在 tick 入口设置）。
	 * <br/>
	 * 加速模组场景下使用上一 gameTick 的最终 multiplier 作为本 gameTick 的批量倍率，
	 * 在产出次数累积时乘以此倍率，实现 N 倍产出跳过 N-1 次重复 tick 处理。
	 *
	 * @param multiplier 加速倍率，范围 [1, 1024]
	 */
	void setTickMultiplier(int multiplier) {
		this.tickMultiplier = Math.max(1, multiplier);
	}

	/**
	 * 遍历蜜蜂槽处理生产逻辑（Task 16 优化版）
	 * <br/>
	 * 流程：红石检查 → 计算批量刷新周期 → 遍历 BeeSlot（空槽跳过/花朵/输出/能量检查/推进计时/完成累积/设置状态）
	 * → 批量刷新周期到达时按 EntityType 分组批量产出 → 统一提取能量。
	 * <p>
	 * 累积补偿原理：高速度升级下 adjustedMinTicks 可能为 1，直接产出会导致每 tick N 次 insert 调用。
	 * 改为累积 20 tick 后批量处理，将 20×N 次插入合并为按物品种类数的少量插入。
	 * <p>
	 * v9-P3 修复：try-finally 确保循环中途异常时已累积的能量仍被扣除，
	 * 避免 ticksInHive 已推进、pendingEnergyCost 已累积但能量未扣除的不一致状态。
	 */
	void tick() {
		// Task 6：读取批量倍率（由 ApiaryTickHandler 设置），读取后立即重置字段
		// 使用局部变量避免循环内多次读取字段，且保证即使 tick 中途 return 字段也已重置
		int tickMultiplier = this.tickMultiplier;
		this.tickMultiplier = 1;

		// 红石检查 — 关闭时所有蜜蜂失活（CAS 守卫保证幂等，已失活槽位为 no-op）
		if (!tile.canFunction()) {
			activationCounter.deactivateAll();
			return;
		}

		// 驱动 ApiaryUpgradeCache 100-tick 自动刷新（基于内部计数器，JDTE 适配）
		// 必须在首次调用 upgradeHandler.getXxx() 之前执行，确保缓存守卫正确递增
		upgradeHandler.tickRefresh();
		Level level = slotManager.getLevel();

		// 预加载转化配方索引（幂等，仅首次全量遍历）— 供花朵有效性判定中的转化原料检查使用
		BeeConversionQueries.ensureLoaded(level);

		var energyContainer = tile.accessor().productivebeesgenesis$getEnergyContainer();
		// 防御性 null 检查 — 极少数情况下（如方块实体早期构造期或测试环境）energyContainer 可能为 null
		// 此时直接失活所有蜜蜂并返回，避免 NPE 中断 tick 处理
		if (energyContainer == null) {
			activationCounter.deactivateAll();
			return;
		}
		float timeMultiplier = upgradeHandler.getTimeMultiplier();
		// CREATIVE 升级状态循环外读取一次（Spark 优化：原在 advance 内每 tick 每蜜蜂查询，
		// 每次触发 ApiaryUpgradeCache 的 AtomicLong 自增；49 槽创造工厂每秒 980 次冗余原子操作）
		boolean hasCreativeUpgrade = upgradeHandler.hasCreativeUpgrade();
		// MachineEnergyContainer 已按 Mekanism 官方公式实时应用速度/能量升级，
		// 直接读取可同时保留普通蜂箱配置和各工厂等级的基础能耗。
		long beeEnergyCost = ApiaryEnergyMath.calculateBeeEnergyCost(
				energyContainer.getEnergyPerTick(), hasCreativeUpgrade);
		// Task 1.2：STACK 升级产出次数倍率 — 循环外计算一次，所有蜜蜂共享
		int stackProductionCount = upgradeHandler.getStackProductionCount();

		// 批量刷新周期判断 — 累积量阈值提前触发，避免满升级时单次 flush 量过大导致 MSPT 尖刺。
		// 阈值随本刻批量倍率放大（见 ApiaryFlushPolicy）：CREATIVE 升级下单只蜜蜂单刻就累积
		// tickMultiplier 次产出，固定 64 阈值会让提前 flush 每刻触发，把 flush 固定开销 ×10。
		tickCounter++;
		boolean shouldFlush = ApiaryFlushPolicy.shouldFlush(tickCounter, BATCH_FLUSH_INTERVAL,
				accumulatedProgress.get(), tickMultiplier);
		if (shouldFlush) tickCounter = 0;

		BeeSlot[] beeSlots = slotManager.getBeeSlots();

		// 刷新配置缓存（每 100 tick 一次，参考离心机 TileComponentEjectorCooldownMixin）
		if (level != null) {
			configCache.refresh(level.getGameTime());
		}

		// 缓存输出空间状态 — 避免循环内重复调用 isOutputFull()（O(N×M) → O(N+M)）
		// 输出槽对所有蜜蜂共享，状态在一次 tick 内一致
		// 设计语义：输出满阻塞蜜蜂是虚拟缓冲区防溢出的核心 — 高倍加速混养场景下
		// 若蜜蜂持续产出，多蜂种产物会溢出输出格与缓冲区造成丢失（用户反馈的"无限产物"感知
		// 正是缓冲区积压消退过程）。产出直连（transferProducedStacks）从源头减少蜜脾占用输出槽，
		// 降低触发本阻塞的概率，而非移除阻塞本身。
		boolean outputFull = slotManager.isOutputFull();
		// Commit any fluid suffix from the previous batch before advancing bee progress.
		// Keeping production blocked while it remains pending prevents an unbounded long buffer.
		boolean pendingFluidBlocked = !produceProcessor.flushPendingFluid();

		// 花朵有效性缓存：使用 BeeSlot 内部 volatile 字段直接缓存 — 比 per-tick HashMap 更便宜：
		//   - cache hit: 2 次 volatile 读（cachedFlowerValidTick、cachedFlowerValid）≈ 10ns
		//   - cache miss: 1 次 LinkedHashMap.get + 2 次 volatile 写
		// 同 tick 内同种蜜蜂 cache hit，避免重复调用 hasValidFlower。
		// 使用 long 类型避免 long-running 服务器上 getGameTime() 溢出（int 上限约 3.4 年游戏时间）
		long currentTick = (level != null) ? level.getGameTime() : 0L;
		boolean checkBeeGenes = level != null && BalanceConfig.apiaryBeeGenesAffectWork();
		boolean fixedTime = checkBeeGenes && level.dimensionType().hasFixedTime();
		boolean night = checkBeeGenes && level.isNight();
		boolean raining = checkBeeGenes && level.isRaining();
		boolean thundering = checkBeeGenes && level.isThundering();
		Arrays.fill(runnableBeeSlots, false);
		int runnableBeeCount = 0;
		int slotStart = beeSlots.length == 0 ? 0 : Math.floorMod(beeSlotRotationIndex, beeSlots.length);
		for (int slotOffset = 0; slotOffset < beeSlots.length; slotOffset++) {
			int i = (slotStart + slotOffset) % beeSlots.length;
			BeeSlot slot = beeSlots[i];
			if (slot.isEmpty()) {
				clearBeeDataCache(i);
				activationCounter.onBeeDeactivated(i);
				continue;
			}

			ResourceLocation beeTypeKey = resolveBeeTypeKeyForSlot(slot, i);
			beeTypeKeyBySlot[i] = beeTypeKey;
			if (beeTypeKey == null) {
				slot.setState(BeeState.WAITING_FLOWER);
				activationCounter.onBeeDeactivated(i);
				continue;
			}
			if (checkBeeGenes) {
				BeeState blockingState = BeeWorkConditionEvaluator.blockingState(
						resolveBeeWorkTraitsForSlot(slot, i),
						fixedTime, night, raining, thundering);
				if (blockingState != null) {
					slot.setState(blockingState);
					activationCounter.onBeeDeactivated(i);
					continue;
				}
			}
			if (!ApiaryFlowerValidation.check(slot, beeTypeKey, currentTick, feederManager)) {
				slot.setState(BeeState.WAITING_FLOWER);
				activationCounter.onBeeDeactivated(i);
				continue;
			}
			if (outputFull || pendingFluidBlocked) {
				// 输出满/流体后缀阻塞 — 虚拟缓冲区防溢出语义（见上方设计注释）
				slot.setState(BeeState.WAITING_OUTPUT);
				activationCounter.onBeeDeactivated(i);
				continue;
			}
			runnableBeeSlots[i] = true;
			runnableBeeCount++;
		}

		ApiaryEnergyMath.BeeTickAllocation energyAllocation = ApiaryEnergyMath.allocateBeeTicks(
				energyContainer.getEnergy(), beeEnergyCost, runnableBeeCount, tickMultiplier);
		int extraTickBeesRemaining = energyAllocation.beesWithExtraTick();
		long pendingEnergyCost = 0L;

		// v9-P3 修复：try-finally 确保循环中途异常时已累积的能量仍被扣除，
		// 避免 ticksInHive 已推进、pendingEnergyCost 已累积但能量未扣除的不一致状态
		try {
		for (int slotOffset = 0; slotOffset < beeSlots.length; slotOffset++) {
			int i = (slotStart + slotOffset) % beeSlots.length;
			BeeSlot slot = beeSlots[i];
			if (!runnableBeeSlots[i]) {
				continue;
			}

			int allocatedTicks = energyAllocation.ticksPerBee();
			if (extraTickBeesRemaining > 0) {
				allocatedTicks++;
				extraTickBeesRemaining--;
			}
			if (allocatedTicks <= 0) {
				slot.setState(BeeState.WAITING_ENERGY);
				activationCounter.onBeeDeactivated(i);
				continue;
			}

			// 推进计时 — 委托 ApiaryProgressAdvancer（纯代码移动：计算完成周期、更新进度、累积待产出次数）
			pendingEnergyCost = SaturatingMath.saturatingAdd(pendingEnergyCost,
					ApiaryProgressAdvancer.advance(slot, i, allocatedTicks, currentTick,
					timeMultiplier, hasCreativeUpgrade, beeEnergyCost, stackProductionCount, configCache.getProcessingTime(),
					upgradeHandler, pendingProductions, accumulatedProgress));
			// 设置工作状态 — CAS 守卫仅在工作状态转换 0→1 时递增计数器
			slot.setState(BeeState.WORKING);
			activationCounter.onBeeActivated(i);
		}

		// 批量刷新累积产出（Task 16.1 + 16.2 核心优化）
		if (shouldFlush && accumulatedProgress.get() > 0) {
			try {
				flushPendingProductions(beeSlots, level);
			} catch (Exception e) {
				// 节流记录错误日志，避免高频异常刷屏（复用 slotErrorThrottle，与外层异常处理一致）
				final Exception cause = e;
				// 统一使用 ms 时间源，避免 tick/ms 双模式混用导致节流失效（Task 15）
				slotErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
					ProductiveBeesGenesis.LOGGER.error("批量刷新累积产出时异常"
							+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), cause);
				});
			} finally {
				// 即使异常，flushPendingProductions 可能已部分写入输出槽，仍需标记直连弹出
				tile.markDirectEjectDirty();
			}
		}

		} finally {
		// 统一提取能量 — 减少 IO 调用次数（v9-P3：finally 确保异常时也扣除已累积能量）
		if (pendingEnergyCost > 0) {
			energyContainer.extract(pendingEnergyCost, Action.EXECUTE, AutomationType.INTERNAL);
		}
		if (beeSlots.length > 0) {
			beeSlotRotationIndex = (slotStart + 1) % beeSlots.length;
		}
		}
	}

	/**
	 * 刷新累积产出 — 按 EntityType 分组批量处理（Task 16.1 + 16.2）
	 * <br/>
	 * 分组原理：同类型蜜蜂（相同 EntityType）共享一次配方查询结果，
	 * 避免每只蜜蜂重复查询配方缓存。组内所有蜜蜂的累积产出合并后一次性分发。
	 * <p>
	 * 流程：
	 * <ol>
	 *   <li>遍历蜜蜂槽，将 pendingProductions[i] > 0 的槽位按 EntityType 分组</li>
	 *   <li>对每个 EntityType 组：查询一次配方（缓存命中 O(1)）</li>
	 *   <li>调用 {@link BeeProduceProcessor#processBatchProduce} 批量产出</li>
	 *   <li>清空 pendingProductions 与 accumulatedProgress</li>
	 * </ol>
	 *
	 * @param beeSlots 蜜蜂槽数组
	 * @param level    世界实例（配方查询用）
	 */
	private void flushPendingProductions(BeeSlot[] beeSlots, Level level) {
		if (level == null) {
			// 无世界实例时清空累积，防止数据残留
			clearPendingProductions();
			return;
		}

		// 按蜜蜂类型键（ResourceLocation）分组蜜蜂槽索引（Task 16.1）
		// 使用 beeTypeKey 而非 EntityType，确保 ConfigurableBee 按具体类型（如 productivebees:iron）分组
		// 仅服务器 tick 线程访问，无需并发容器
		// 固定容量开放寻址分组，避免每次 flush 重建 HashMap 节点和装箱槽位索引。
		// Task 5：复用主循环预算的 beeTypeKeyBySlot[i]，避免重复调用 resolveBeeTypeKeyForSlot
		resetPendingProductionGroups();
		for (int i = 0; i < beeSlots.length && i < pendingProductions.length; i++) {
			if (pendingProductions[i] <= 0) continue;
			BeeSlot slot = beeSlots[i];
			if (slot.isEmpty()) {
				pendingProductions[i] = 0;
				continue;
			}
			ResourceLocation beeTypeKey = beeTypeKeyBySlot[i];
			if (beeTypeKey == null) {
				pendingProductions[i] = 0;
				continue;
			}
			findPendingProductionGroup(beeTypeKey).slotIndices.add(i);
		}

		// 输出空间检查 — 刷新前再次检查，避免产物丢失
		if (slotManager.isOutputFull()) {
			// 输出已满，保留 pendingProductions 待下次刷新（不丢失累积产出）
			return;
		}

		// 对每个蜜蜂类型键组批量处理
		// finally 确保异常时也清零 pendingProductions，防止下次 flush 重复分发导致产出翻倍
		// 异常时未分发的产出会丢失，但优于产出翻倍
		try {
			for (int groupIndex = 0; groupIndex < activePendingProductionGroupCount; groupIndex++) {
				PendingProductionGroup group = pendingProductionGroups[groupIndex];
				ResourceLocation typeKey = group.typeKey;

				// 花朵校验：喂食槽无匹配花朵（含转化原料花朵）时清零该组 pending 并跳过 flush，
				// 避免"蜜蜂无有效花朵时仍产出/吸蜜"导致产出异常
				if (!feederManager.hasValidFlower(typeKey)) {
					for (int position = 0; position < group.slotIndices.size(); position++) {
						int idx = group.slotIndices.get(position);
						if (idx >= 0 && idx < pendingProductions.length) pendingProductions[idx] = 0;
					}
					continue;
				}

				// 转化处理（在产出前执行）：饲养板 BlockItem 物品/方块转化（PB item_conversion / block_conversion 适配）
				// pollinates=false 的转化周期会扣减 pendingProductions，该周期不产出蜜脾（对齐 PB hasConverted 语义）
				conversionProcessor.processGroupConversions(typeKey, level, beeSlots,
						group.slotIndices, pendingProductions);

				// 同组共享一次配方查询（缓存命中 O(1)）
				// 模块 2+3：getCachedProduce 返回 Map<ItemStack, ChancedOutput>（配方原始数据，不执行概率检查）
				// 模块 1：传入 feederManager 支持 lumber_bee/quarry_bee/dye_bee 从喂食槽推断产物
				boolean feederDependentProduce = MultiFlowerBeeAdapter.isMultiFlowerBee(typeKey);
				Map<ItemStack, ChancedOutput> produceList = feederDependentProduce
						? Map.of()
						: produceProcessor.getCachedProduce(typeKey, level, feederManager);
				if (produceList.isEmpty() && !feederDependentProduce
						&& !PBConstants.WANNA_TYPE.equals(typeKey)) continue;

				// 复用 pendingProductions 数组，processBatchProduce 内部按索引读取
				// Bug 10: 传入 level 用于万象创世随机蜜脾生成
				// Bug 3: 仅传入当前组的槽位索引，避免混养串组
				// F4: 传入 outputBuffer，输出槽满载时剩余产物送入缓冲区下 tick 重试
				produceProcessor.processBatchProduce(beeSlots, pendingProductions, group.slotIndices,
						typeKey, produceList, slotManager, feederManager, tile.getBlockPos(),
						level, tile.getOutputBuffer());
			}
		} finally {
			// 清零所有累积计数（含 accumulatedProgress），异常时也执行，防止产出翻倍
			clearPendingProductions();
		}
	}

	private void resetPendingProductionGroups() {
		Arrays.fill(pendingProductionGroupTable, 0);
		activePendingProductionGroupCount = 0;
	}

	private PendingProductionGroup findPendingProductionGroup(ResourceLocation typeKey) {
		int hash = typeKey.hashCode();
		int tableMask = pendingProductionGroupTable.length - 1;
		int bucket = (hash ^ (hash >>> 16)) & tableMask;
		while (true) {
			int encodedGroup = pendingProductionGroupTable[bucket];
			if (encodedGroup == 0) {
				PendingProductionGroup group = pendingProductionGroups[activePendingProductionGroupCount];
				group.reset(typeKey, pendingProductions.length);
				pendingProductionGroupTable[bucket] = ++activePendingProductionGroupCount;
				return group;
			}
			PendingProductionGroup group = pendingProductionGroups[encodedGroup - 1];
			if (group.typeKey.equals(typeKey)) return group;
			bucket = (bucket + 1) & tableMask;
		}
	}

	private static final class PendingProductionGroup {
		private ResourceLocation typeKey;
		private final OrderedSlotIndex slotIndices = new OrderedSlotIndex();

		void reset(ResourceLocation typeKey, int slotCount) {
			this.typeKey = typeKey;
			slotIndices.reset(slotCount);
		}
	}

	/**
	 * 清空所有累积产出计数（防御性清理）
	 */
	private void clearPendingProductions() {
		for (int i = 0; i < pendingProductions.length; i++) {
			pendingProductions[i] = 0;
		}
		accumulatedProgress.set(0);
	}

	/**
	 * 解析指定蜜蜂槽的类型键，使用引用相等缓存避免每 tick 重复解析 NBT 字符串。
	 * <br/>
	 * {@link BeeSlot} 仅在蜜蜂实质变化时更新 {@code beeData} 引用，因此引用相等可安全作为缓存键。
	 *
	 * @param slot  蜜蜂槽
	 * @param index 槽位索引
	 * @return 蜜蜂类型键，解析失败返回 null
	 */
	private ResourceLocation resolveBeeTypeKeyForSlot(BeeSlot slot, int index) {
		CompoundTag beeData = slot.getBeeData();
		if (beeData == lastCheckedBeeData[index] && cachedBeeTypeKeys[index] != null) {
			return cachedBeeTypeKeys[index];
		}
		ResourceLocation key = BeeNbtHelper.resolveBeeTypeKey(beeData);
		lastCheckedBeeData[index] = beeData;
		cachedBeeTypeKeys[index] = key;
		cachedBeeWorkTraits[index] = null;
		return key;
	}

	/** 复用槽位 beeData 引用缓存，保证工作相关基因只在蜜蜂变化时解析一次。 */
	private BeeWorkConditionEvaluator.WorkTraits resolveBeeWorkTraitsForSlot(BeeSlot slot, int index) {
		CompoundTag beeData = slot.getBeeData();
		if (beeData != lastCheckedBeeData[index]) {
			resolveBeeTypeKeyForSlot(slot, index);
		}
		BeeWorkConditionEvaluator.WorkTraits traits = cachedBeeWorkTraits[index];
		if (traits == null) {
			traits = BeeWorkConditionEvaluator.readTraits(beeData);
			cachedBeeWorkTraits[index] = traits;
		}
		return traits;
	}

	/** 清空空槽的 NBT 引用缓存，避免长期保留已取出的蜜蜂数据。 */
	private void clearBeeDataCache(int index) {
		lastCheckedBeeData[index] = null;
		cachedBeeTypeKeys[index] = null;
		cachedBeeWorkTraits[index] = null;
		beeTypeKeyBySlot[index] = null;
	}

}
