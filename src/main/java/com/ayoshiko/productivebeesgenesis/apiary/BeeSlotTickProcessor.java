package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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
	 * 累积产出阈值 — 达到此值时提前 flush，避免满升级时单次 flush 量过大导致 MSPT 尖刺。
	 * <br/>
	 * Spark 分析显示 v2.0.2 满升级场景 MSPT max=54.6ms，主因是每 10 tick 一次的
	 * 批量 flush 瞬间处理数百次累积产出。提前触发将大批量拆为小批量，
	 * 平滑 flush 负载到多个 tick。
	 * <p>
	 * 正常低升级场景 10 tick 内累积量通常 < 10，不受影响。
	 */
	private static final int FLUSH_ACCUMULATION_THRESHOLD = 64;

	/** 配置缓存刷新间隔（tick） */
	private static final int CONFIG_REFRESH_INTERVAL = 100;

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

	/** O(1) 激活状态计数器 — 封装每槽位 CAS 守卫与 workingCount 增量维护 */
	private final ApiaryBeeActivationCounter activationCounter;

	/** 蜜蜂槽位处理异常日志冷却器（tick 模式） */
	private final LogThrottle slotErrorThrottle;

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

	/**
	 * 主循环预算的蜜蜂类型键数组 — 供 flushPendingProductions 复用，避免重复调用 resolveBeeTypeKeyForSlot。
	 * <br/>
	 * 在 {@link #tick()} 主循环中每只非空蜜蜂写入一次（与 {@link #cachedBeeTypeKeys} 同步更新），
	 * {@link #flushPendingProductions} 直接读取本数组，跳过 resolveBeeTypeKeyForSlot 的二次调用。
	 * 仅服务端 tick 线程访问，无需同步。
	 */
	private final ResourceLocation[] beeTypeKeyBySlot;

	/**
	 * flushPendingProductions 复用的分组缓冲（避免每次刷新都 new HashMap）
	 * <br/>
	 * 仅服务端 tick 线程访问，无需同步。每次 flushPendingProductions 调用前 clear()。
	 */
	private final HashMap<ResourceLocation, List<Integer>> pendingProductionsBuffer = new HashMap<>();

	// ----- basic 配置缓存 -----
	/** 缓存的每槽每 tick 能耗（FE） */
	private volatile long cachedEnergyPerTick = 50L;

	/** 缓存的基础处理时间（tick） */
	private volatile int cachedProcessingTime = 200;

	/** 上次刷新配置的游戏刻 — AtomicLong + CAS 防止多线程重复刷新 */
	private final AtomicLong lastConfigRefreshTick = new AtomicLong(-CONFIG_REFRESH_INTERVAL);

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
	 */
	BeeSlotTickProcessor(TileEntityMekApiary tile, ApiarySlotManager slotManager,
			BeeProduceProcessor produceProcessor, ApiaryUpgradeHandler upgradeHandler,
			FeederSlotManager feederManager, ApiaryBeeActivationCounter activationCounter,
			LogThrottle slotErrorThrottle) {
		this.tile = tile;
		this.slotManager = slotManager;
		this.produceProcessor = produceProcessor;
		this.upgradeHandler = upgradeHandler;
		this.feederManager = feederManager;
		this.activationCounter = activationCounter;
		this.slotErrorThrottle = slotErrorThrottle;
		this.pendingProductions = new int[slotManager.getBeeSlotCount()];
		this.lastCheckedBeeData = new CompoundTag[slotManager.getBeeSlotCount()];
		this.cachedBeeTypeKeys = new ResourceLocation[slotManager.getBeeSlotCount()];
		this.beeTypeKeyBySlot = new ResourceLocation[slotManager.getBeeSlotCount()];
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

		var energyContainer = tile.accessor().productivebeesgenesis$getEnergyContainer();
		// 防御性 null 检查 — 极少数情况下（如方块实体早期构造期或测试环境）energyContainer 可能为 null
		// 此时直接失活所有蜜蜂并返回，避免 NPE 中断 tick 处理
		if (energyContainer == null) {
			activationCounter.deactivateAll();
			return;
		}
		long pendingEnergyCost = 0;
		float energyMultiplier = upgradeHandler.getEnergyMultiplier();
		float timeMultiplier = upgradeHandler.getTimeMultiplier();
		// Task 1.2：STACK 升级产出次数倍率 — 循环外计算一次，所有蜜蜂共享
		int stackProductionCount = upgradeHandler.getStackProductionCount();

		// 批量刷新周期判断 — 累积量阈值提前触发，避免满升级时单次 flush 量过大导致 MSPT 尖刺
		tickCounter++;
		boolean shouldFlush = (tickCounter >= BATCH_FLUSH_INTERVAL)
				|| (accumulatedProgress.get() >= FLUSH_ACCUMULATION_THRESHOLD);
		if (shouldFlush) tickCounter = 0;

		BeeSlot[] beeSlots = slotManager.getBeeSlots();

		// 刷新配置缓存（每 100 tick 一次，参考离心机 TileComponentEjectorCooldownMixin）
		if (level != null) {
			refreshConfigCache(level.getGameTime());
		}

		// 缓存输出空间状态 — 避免循环内重复调用 isOutputFull()（O(N×M) → O(N+M)）
		// 输出槽对所有蜜蜂共享，状态在一次 tick 内一致
		boolean outputFull = slotManager.isOutputFull();

		// 花朵有效性缓存：使用 BeeSlot 内部 volatile 字段直接缓存 — 比 per-tick HashMap 更便宜：
		//   - cache hit: 2 次 volatile 读（cachedFlowerValidTick、cachedFlowerValid）≈ 10ns
		//   - cache miss: 1 次 LinkedHashMap.get + 2 次 volatile 写
		// 同 tick 内同种蜜蜂 cache hit，避免重复调用 hasValidFlower。
		// 使用 long 类型避免 long-running 服务器上 getGameTime() 溢出（int 上限约 3.4 年游戏时间）
		long currentTick = (level != null) ? level.getGameTime() : 0L;

		// v9-P3 修复：try-finally 确保循环中途异常时已累积的能量仍被扣除，
		// 避免 ticksInHive 已推进、pendingEnergyCost 已累积但能量未扣除的不一致状态
		try {
		for (int i = 0; i < beeSlots.length; i++) {
			BeeSlot slot = beeSlots[i];
			if (slot.isEmpty()) {
				activationCounter.onBeeDeactivated(i);
				continue;
			}

			// 花朵检查 — 无花朵则蜜蜂无法工作
			// 使用 beeData 直接查询花朵偏好，避免 EntityType.getKey() 对 ConfigurableBee 返回通用类型
			ResourceLocation beeTypeKey = resolveBeeTypeKeyForSlot(slot, i);
			beeTypeKeyBySlot[i] = beeTypeKey; // Task 5：预算写入，供 flushPendingProductions 复用
			if (beeTypeKey == null) {
				slot.setState(BeeState.WAITING_FLOWER);
				activationCounter.onBeeDeactivated(i);
				continue;
			}
			// 每只蜜蜂内部缓存（volatile 字段）：同 tick 内同种蜜蜂 cache hit，
			// 避免对 LinkedHashMap (access-order) 的 get + afterNodeAccess 调用（Spark HashMap.get 23.68ms 热点）
			Boolean flowerValid = slot.consumeCachedFlowerValid(currentTick);
			if (flowerValid == null) {
				flowerValid = feederManager.hasValidFlower(beeTypeKey);
				slot.setCachedFlowerValid(currentTick, flowerValid);
			}
			if (!flowerValid) {
				slot.setState(BeeState.WAITING_FLOWER);
				activationCounter.onBeeDeactivated(i);
				continue;
			}

			// 输出空间检查 — 输出槽满时彻底停止生产（不推进进度、不累积pendingProductions）
			// 与离心机"输出满停机"语义一致，避免输出满时pendingProductions无限累积
			if (outputFull) {
				slot.setState(BeeState.WAITING_OUTPUT);
				activationCounter.onBeeDeactivated(i);
				continue;
			}

			// 能量检查 — 累计能耗超过当前能量时等待
			long beeEnergyCost = calculateBeeEnergyCost(energyMultiplier);
			if (energyContainer.getEnergy() < pendingEnergyCost + beeEnergyCost) {
				slot.setState(BeeState.WAITING_ENERGY);
				activationCounter.onBeeDeactivated(i);
				continue;
			}

			// 推进计时
			int currentTicks = slot.getTicksInHive();
			// 模块1修复：从 baseMinOccupationTicks 读取原始基础值，而非 minOccupationTicks（adjusted 值）。
			// 此前从 minOccupationTicks 读取会被上一 tick 回写的 adjustedMinTicks 污染，
			// 导致下一 tick 再次乘以 timeMultiplier 形成指数衰减。
			int baseMinTicks = slot.getBaseMinOccupationTicks();
			if (baseMinTicks <= 0) {
				// 使用配置缓存的基础处理时间（从 ModConfig.SERVER.apiaryProcessingTime 读取，默认1200）
				baseMinTicks = cachedProcessingTime;
			}
			// 应用时间倍率（< 1.0 加速，> 1.0 减速）
			// Task 4：CREATIVE 升级 — adjustedMinTicks=1，每 tick 产出（参考 MEK getTicksRequired 返回 0）
			int adjustedMinTicks = upgradeHandler.hasCreativeUpgrade() ? 1
					: Math.max(1, Math.round(baseMinTicks * timeMultiplier));
			// 模块1：蜂箱速度调试日志 — 每 100 tick 采样一次，仅在 dev 模式开启时输出
			// 外层 isEnabled() 守卫避免 dev 关闭时调用 DevLog.debug 的方法调用开销
			// DevLog.debug 内部还会检查 apiary_speed feature 开关并做 1000ms 节流
			if ((currentTick % 100) == 0 && DevModeManager.isEnabled()) {
				DevLog.debug("apiary_speed",
						"蜂箱速度诊断 slot={} baseMinTicks={} timeMultiplier={} "
								+ "mekTimeMul={} pbTimeDivisor={} speedUpgrades={} maxSpeed={} "
								+ "maxUpgradeMul={} adjustedMinTicks={}",
						i, baseMinTicks, timeMultiplier,
						upgradeHandler.getMekSpeedTimeMultiplier(),
						upgradeHandler.getPbTimeDivisor(),
						upgradeHandler.getMekSpeedUpgrades(),
						upgradeHandler.getMaxSpeedUpgrades(),
						upgradeHandler.getMaxUpgradeMultiplier(),
						adjustedMinTicks);
			}
			// 同步 adjustedMinTicks 到 BeeSlot，确保 tooltip 工作进度显示正确的工作 tick 上限
			// 修复：此前不更新 minOccupationTicks 导致 tooltip 始终显示 300/0 tick（0%）
			if (slot.getMinOccupationTicks() != adjustedMinTicks) {
				slot.setMinOccupationTicks(adjustedMinTicks);
			}
			int newTicks = currentTicks + 1;
			slot.setTicksInHive(newTicks);
			pendingEnergyCost += beeEnergyCost;

			// 更新进度（供 GUI 进度条渲染）
			slot.setProgress((float) newTicks / adjustedMinTicks);

			// 完成累积 — 达到最小 occupation ticks 时累积待产出次数（不立即产出）
			if (newTicks >= adjustedMinTicks) {
				// 重置计时与进度，由 tick 处理器统一管理（SRP：产出处理器不再管理槽位状态）
				slot.setTicksInHive(0);
				slot.setProgress(0.0f);
				if (i < pendingProductions.length) {
					// Task 1.2：STACK 升级 — 一次完成多个产出周期（2^stackUpgrades 次）
					// 每个产出周期独立触发随机概率（基因采样器、万象创世随机蜜脾）
					// Task 6：批量收获模式 — 应用 tickMultiplier（N 倍产出跳过 N-1 次重复 tick）
					int pendingCount = stackProductionCount * tickMultiplier;
					pendingProductions[i] += pendingCount;
					accumulatedProgress.addAndGet(pendingCount);
				}
			}

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
		// Task 23.5：复用实例字段 pendingProductionsBuffer，避免每次 flush 都 new HashMap
		// Task 5：复用主循环预算的 beeTypeKeyBySlot[i]，避免重复调用 resolveBeeTypeKeyForSlot
		pendingProductionsBuffer.clear();
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
			pendingProductionsBuffer.computeIfAbsent(beeTypeKey, k -> new ArrayList<>()).add(i);
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
			for (Map.Entry<ResourceLocation, List<Integer>> entry : pendingProductionsBuffer.entrySet()) {
				ResourceLocation typeKey = entry.getKey();

				// 同组共享一次配方查询（缓存命中 O(1)）
				// 模块 2+3：getCachedProduce 返回 Map<ItemStack, ChancedOutput>（配方原始数据，不执行概率检查）
				// 模块 1：传入 feederManager 支持 lumber_bee/quarry_bee/dye_bee 从喂食槽推断产物
				Map<ItemStack, ChancedOutput> produceList = produceProcessor.getCachedProduce(typeKey, level, feederManager);
				if (produceList.isEmpty() && !PBConstants.WANNA_TYPE.equals(typeKey)) continue;

				// 复用 pendingProductions 数组，processBatchProduce 内部按索引读取
				// Bug 10: 传入 level 用于万象创世随机蜜脾生成
				// Bug 3: 传入 entry.getValue() 限定仅处理当前组的槽位索引，避免混养串组
				// F4: 传入 outputBuffer，输出槽满载时剩余产物送入缓冲区下 tick 重试
				produceProcessor.processBatchProduce(beeSlots, pendingProductions, entry.getValue(),
						typeKey, produceList, slotManager, feederManager, tile.getBlockPos(),
						level, tile.getOutputBuffer());
			}
		} finally {
			// 清零所有累积计数（含 accumulatedProgress），异常时也执行，防止产出翻倍
			clearPendingProductions();
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
		return key;
	}

	/**
	 * 计算单只蜜蜂每 tick 能耗
	 * <br/>
	 * = 配置能耗 × 能耗倍率（由 {@link ApiaryUpgradeHandler#getEnergyMultiplier} 提供，
	 * 已综合 MEK 速度升级的影响：每级速度升级增加 10% 能耗）。
	 * <p>
	 * 使用 {@link #cachedEnergyPerTick}（从 ModConfig.SERVER.apiaryEnergyPerTick 读取，每 100 tick 刷新）
	 * 替代硬编码常量，让配置实际生效。
	 * <p>
	 * MEKExtras CREATIVE 升级：能耗倍率为 0.0f 时直接返回 0，跳过 Math.max(1L, ...) 的最小值保护，
	 * 实现零能量消耗。
	 *
	 * @param energyMultiplier 能耗倍率（来自升级处理器，CREATIVE 安装时为 0.0f）
	 * @return 单只蜜蜂每 tick 能耗（FE），CREATIVE 安装时返回 0
	 */
	private long calculateBeeEnergyCost(float energyMultiplier) {
		if (energyMultiplier <= 0.0f) return 0L; // MEKExtras CREATIVE 升级：零能耗
		return Math.max(1L, (long) (cachedEnergyPerTick * energyMultiplier));
	}

	/**
	 * 刷新配置缓存 — 每 {@link #CONFIG_REFRESH_INTERVAL} tick 刷新一次
	 * <br/>
	 * 参考离心机 {@link com.ayoshiko.productivebeesgenesis.mixin.mek.TileComponentEjectorCooldownMixin}
	 * 的配置缓存模式，将 {@link ApiaryConfigSection} 的 basic 和 ejection 配置值缓存到 volatile 字段，
	 * 避免 256× 加速场景下每 tick 高频读取 NeoForge 配置。
	 * <p>
	 * 线程安全：使用 AtomicLong + CAS（compareAndSet）保证「检查时间戳 + 写入新值 + 加载配置」的原子性。
	 * 即使异步线程与主线程同时调用，CAS 也只有一个线程能成功推进时间戳，另一个线程短路返回。
	 *
	 * @param currentTick 当前游戏刻
	 */
	private void refreshConfigCache(long currentTick) {
		long lastRefresh = lastConfigRefreshTick.get();
		if (currentTick - lastRefresh < CONFIG_REFRESH_INTERVAL) {
			return;
		}
		// CAS 推进时间戳：失败说明其他线程已先一步完成刷新，本线程无需重复加载
		if (!lastConfigRefreshTick.compareAndSet(lastRefresh, currentTick)) {
			return;
		}
		// basic 配置
		cachedEnergyPerTick = ModConfig.SERVER.apiaryEnergyPerTick.get();
		cachedProcessingTime = ModConfig.SERVER.apiaryProcessingTime.get();
	}
}
