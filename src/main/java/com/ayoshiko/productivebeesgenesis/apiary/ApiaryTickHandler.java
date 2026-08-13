package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.mek.TickBatchSkipState;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.world.level.Level;

/**
	 * 通用机械蜂箱服务端 tick 处理器
	 * <br/>
	 * 参考 {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeTickHandler} 模式，
	 * 负责服务端 tick 逻辑编排，让主类聚焦于槽位/接口实现。
	 * <p>
	 * 职责（编排器角色）：
	 * <ul>
	 *   <li>调用 super.onUpdateServer() 触发 Mekanism 能量填充管线</li>
	 *   <li>委托 {@link CageTickProcessor} 处理蜂笼输入（蜜蜂在蜂笼与蜂槽间双向转移）</li>
	 *   <li>委托 {@link BeeSlotTickProcessor} 处理蜜蜂生产周期（花朵检查/输出空间检查/能量检查/推进计时/完成累积）</li>
	 *   <li>管理 active 状态切换与工作声音播放</li>
	 * </ul>
	 * <p>
	 * tick 完整流程：
	 * <ol>
	 *   <li>AE2 能量注入 — {@link TileEntityMekApiary#productivebeesgenesis$injectAe2Energy}</li>
	 *   <li>调用 super.onUpdateServer() — 能量填充管线 + ejector tick</li>
	 *   <li>蜂笼输入处理 — {@link CageTickProcessor#tick}（在生产逻辑前执行）</li>
	 *   <li>蜜蜂槽位处理 — {@link BeeSlotTickProcessor#tick}（含批量产出刷新与能量扣除）</li>
	 *   <li>PB 升级输入槽自动安装 + 动画计数器递减</li>
	 *   <li>active 状态管理 — 有蜜蜂工作时设为 true</li>
	 *   <li>工作声音播放 — 按概率播放 PB 蜜蜂嗡嗡声</li>
	 * </ol>
	 * <p>
	 * Task 17：super.onUpdateServer() 前调用 AE2 能量注入，让蜜蜂生产消耗也能使用注入的能量。
	 * 守卫（AE2 加载 / 配置启用 / grid 非 null）由 injectAe2Energy() 内部处理。
	 * <p>
	 * 线程安全：方块实体在服务端单线程执行，AtomicInteger 提供防御性原子计数。
	 */
class ApiaryTickHandler {

	/** 所属方块实体引用 */
	private final TileEntityMekApiary tile;

	/** Task 4：蜂箱工作声音处理器 — 播放 PB 蜜蜂嗡嗡声 */
	private final ApiarySoundHandler soundHandler;

	/** O(1) 激活状态计数器 — 封装每槽位 CAS 守卫与 workingCount 增量维护（与 {@link BeeSlotTickProcessor} 共享） */
	private final ApiaryBeeActivationCounter activationCounter;

	/** 转化处理器 — 物品转化与方块转化（均以饲养板 BlockItem 为转化目标） */
	private final ApiaryConversionProcessor conversionProcessor;

	/** 蜜蜂槽位 tick 处理器 — 推进生产计时/批量产出/能量扣除 */
	private final BeeSlotTickProcessor beeSlotProcessor;

	/** 蜂笼输入 tick 处理器 — 蜜蜂在蜂笼与蜂槽间双向转移 */
	private final CageTickProcessor cageProcessor;

	/** 蜜蜂槽位处理异常日志冷却器（tick 模式，与 {@link BeeSlotTickProcessor} 共享） */
	private final LogThrottle slotErrorThrottle = new LogThrottle();

	/** TickAccelTracker 实例 — 蜂箱不通过 IAe2InputHost 获取，自建实例用于检测加速模组（JDT/JDTE/加速火把等） */
	private final TickAccelTracker tickAccelTracker = new TickAccelTracker();

	/** 批量收获状态（同 gameTick 门控 + 共享预算）— ticker 与 JDTE flush 共用 */
	private final TickBatchSkipState skipState = new TickBatchSkipState();

	/** 上一 tick 是否有蜜蜂在工作 — 用于检测工作停止时恢复 active 状态 */
	private boolean wasWorking;

	/**
	 * 构造 tick 处理器
	 *
	 * @param tile             所属方块实体
	 * @param slotManager      槽位管理器
	 * @param produceProcessor 产出处理器
	 * @param upgradeHandler   升级处理器
	 * @param feederManager    喂食器管理器（花朵检查）
	 */
	ApiaryTickHandler(TileEntityMekApiary tile, ApiarySlotManager slotManager,
			BeeProduceProcessor produceProcessor, ApiaryUpgradeHandler upgradeHandler,
			FeederSlotManager feederManager) {
		this.tile = tile;
		this.activationCounter = new ApiaryBeeActivationCounter(slotManager.getBeeSlotCount());
		this.soundHandler = new ApiarySoundHandler(tile);
		this.conversionProcessor = new ApiaryConversionProcessor(tile, slotManager, feederManager);
		this.beeSlotProcessor = new BeeSlotTickProcessor(tile, slotManager, produceProcessor,
				upgradeHandler, feederManager, activationCounter, slotErrorThrottle,
				conversionProcessor);
		this.cageProcessor = new CageTickProcessor(slotManager);
	}

	/**
	 * 服务端 tick — 总是调用 super 以确保能量填充管线运行
	 * <br/>
	 * 参考Mekanism原版TileEntityNutritionalLiquifier的做法：总是调用super.onUpdateServer()，
	 * 确保TileEntityConfigurableMachine中的ejectorComponent.tickServer()被执行（否则输出无法自动弹出）。
	 * <p>
	 * 蜜蜂生产逻辑委托至 {@link BeeSlotTickProcessor#tick}，蜂笼输入委托至 {@link CageTickProcessor#tick}。
	 * <p>
	 * Task 17：super.onUpdateServer() 前调用 AE2 能量注入，让蜜蜂生产消耗也能使用注入的能量。
	 * 守卫（AE2 加载 / 配置启用 / grid 非 null）由 injectAe2Energy() 内部处理。
	 *
	 * @return 是否需要发送客户端同步包（由 super 返回）
	 */
	boolean onUpdateServer() {
		// Task 6 批量收获模式：虚拟 tick 银行 + 每 tick 预算（对齐 JDTE 调度器哲学）
		// decideAction 内部完成 onTick 计数、同 gameTick 门控与共享预算取款，消除 1024x 尖峰。
		boolean skipBeeProcessing = false;
		int batchMultiplier = 1;
		Level level = tile.getLevel();
		if (level != null && !level.isClientSide) {
			TickBatchSkipState.TickAction action = skipState.decideAction(tickAccelTracker, level);
			if (action == TickBatchSkipState.TickAction.ALREADY_HANDLED) {
				// 同 gameTick 已由 JDTE flush 完整处理：完全跳过（含 super，避免双跑）
				return false;
			}
			skipBeeProcessing = action == TickBatchSkipState.TickAction.SKIP;
			if (!skipBeeProcessing) {
				batchMultiplier = skipState.getBatchMultiplier();
			}
		}
		return runTick(skipBeeProcessing, batchMultiplier);
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine.accumulateAcceleratedTicks} 委托入口
	 * <br/>
	 * 仅入账虚拟 tick 银行，不执行处理（flush 时统一执行一次完整批量）。
	 */
	void accumulateAcceleratedTicks(int ticks) {
		tickAccelTracker.addVirtualTicks(ticks);
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine.flushAcceleratedTicks} 委托入口
	 * <br/>
	 * JDTE 对实现合并接口的目标不再循环调用 ticker，而是在批量 pass 结束时调用一次本方法：
	 * 从共享预算取本 tick 批量倍率并强制执行一次完整批量（能量注入 + super + 蜜蜂生产 +
	 * 缓冲区分发 + active 状态），把 N 次 ticker 调用开销降为 1 次。
	 * <p>
	 * 与 {@link #onUpdateServer} 共享同 gameTick 门控（{@link TickBatchSkipState#tryBeginGameTick}）：
	 * 无论 JDTE flush 在 ticker 之前还是之后调用，同一 gameTick 只执行一次完整处理，避免双跑。
	 */
	void flushAcceleratedTicks() {
		Level level = tile.getLevel();
		if (level == null || level.isClientSide) {
			return;
		}
		long gameTick = level.getGameTime();
		if (!skipState.tryBeginGameTick(gameTick)) {
			// 同 gameTick 已由 ticker 完整处理：跳过，避免双跑
			return;
		}
		int batchMultiplier = skipState.takeSharedBatchMultiplier(tickAccelTracker, gameTick);
		runTick(false, batchMultiplier);
	}

	/**
	 * 完整 tick 主体 — 由 {@link #onUpdateServer()} 与 {@link #flushAcceleratedTicks()} 共享。
	 * <br/>
	 * 批量倍率在进入前已从虚拟 tick 银行取出，skip 时仍执行 super 保证 ejector/能量管线工作。
	 */
	private boolean runTick(boolean skipBeeProcessing, int batchMultiplier) {
		// AE2 能量注入（在 super 之前调用，让能量填充管线能使用注入的能量）
		tile.productivebeesgenesis$injectAe2Energy();
		// 调用 super 处理能量填充和 ejector tick
		boolean sendUpdatePacket = tile.callSuperOnUpdateServer();

		if (!skipBeeProcessing) {
			beeSlotProcessor.setTickMultiplier(batchMultiplier);
			try {
				// 蜂笼输入 — 蜜蜂从蜂笼转移到蜂槽（在生产逻辑前执行）
				cageProcessor.tick();
				// 蜜蜂生产逻辑独立处理（花朵检查/推进计时/批量产出/能量扣除）
				beeSlotProcessor.tick();
				// PB升级输入槽自动安装处理
				tile.processPbUpgradeInput();
				// Bug 4：递减PB升级动画计数器
				tile.tickPbUpgradeAnim();
			} catch (Exception e) {
				// 捕获异常防止 tick 崩溃，记录错误日志（节流避免刷屏）
				final Exception cause = e;
				// 统一使用 ms 时间源，避免 tick/ms 双模式混用导致节流失效（Task 15）
				slotErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
					ProductiveBeesGenesis.LOGGER.error("ApiaryTickHandler 处理蜜蜂槽位时异常"
							+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), cause);
				});
			}
		}

		// F4: 重试将缓冲区产物注入输出槽
		// Tick 加速模式（skipBeeProcessing=true）下降低频率：缓冲区在加速期间无新产物入队，
		// 仅需每 4 tick 检查一次输出槽是否有空间（Ejector 腾出空间后缓冲区填充）
		if (!skipBeeProcessing || (tickAccelTracker.getMultiplier() & 3) == 0) {
			try {
				tile.getOutputBuffer().tickRedistribute(tile.getSlotManager().getOutputSlots());
			} catch (Exception e) {
				ProductiveBeesGenesis.LOGGER.warn("ApiaryOutputBuffer tickRedistribute 异常", e);
			}
		}

		// active 状态管理 — O(1) 计数器读取
		boolean isWorking = activationCounter.hasActiveBee();
		if (isWorking) {
			tile.callSetActive(true);
			wasWorking = true;
		} else if (wasWorking) {
			tile.callSetActive(false);
			wasWorking = false;
		}

		// Task 4：有蜜蜂工作时按概率播放 PB 蜜蜂嗡嗡声
		if (isWorking) {
			soundHandler.maybePlayWorkSound(activationCounter.getWorkingCount());
		}

		return sendUpdatePacket;
	}

}
