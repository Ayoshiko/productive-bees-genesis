package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FluidPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputPuller;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
	 * 基础MEK离心机服务端tick处理器
	 * <br/>
	 * Task 11 重构：从 {@link TileEntityMekCentrifuge} 抽取 onUpdateServer 与 tryProcessPbRecipe 逻辑，
	 * 让主类聚焦于槽位/接口实现，tick流程独立维护。
	 * <p>
	 * Task 12 扩展：承接原 {@link TileEntityMekCentrifuge#onUpdateServer} 中的 AE2 节点连接、
	 * PB 升级输入槽自动安装、AE2 输出推送（物品+流体）逻辑，使主类 onUpdateServer 完全委托。
	 * <p>
	 * Task 4 扩展：批量收获模式 — 通过 {@link TickAccelTracker} 检测加速模组（JDT/加速火把/JDTE 等），
	 * 采用"虚拟 tick 银行 + 每 tick 预算"策略：每次 tick 调用向银行入账，本 gameTick 第一次 tick 时取出批量预算，
	 * 后续重复 tick 跳过 PB 处理（仍调用 super 让 ejector 工作），实现 N 倍产出跳过 N-1 次重复处理。
	 * <p>
	 * 职责：
	 * <ul>
	 *   <li>延迟连接 AE2 网格节点（{@link MekCentrifugeAe2Handler#tryConnectNode}）</li>
	 *   <li>PB 升级输入槽自动安装（{@link MekCentrifugePbUpgradeHandler#processPbUpgradeInput}）</li>
	 *   <li>调用 super.onUpdateServer()（通过 {@link TileEntityMekCentrifuge#callSuperOnUpdateServer}）
	 *       触发 Mekanism SMELTING 管线与 ejector tick</li>
	 *   <li>独立处理 PB 离心配方（不走 Mekanism CachedRecipe 管线）</li>
	 *   <li>管理 active 状态切换（pbWasProcessing 标志位）</li>
	 *   <li>AE2 输入拉取 + 输出推送（物品 {@link Ae2OutputPusher} + 流体 {@link Ae2FluidPusher}）</li>
	 *   <li>批量收获模式：Tick 加速检测 + 虚拟 tick 银行（Task 4）</li>
	 * </ul>
	 * <p>
	 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
	 */
class MekCentrifugeTickHandler {

	/** 所属方块实体引用 */
	private final TileEntityMekCentrifuge tile;

	/** PB配方处理器 — 由主类持有并注入 */
	private final PbRecipeProcessor pbProcessor;

	/** TickAccelTracker 引用 — 用于检测加速模组的加速倍率,为 null 时不启用批量收获 */
	private final TickAccelTracker tickAccelTracker;

	/** 批量收获状态（同 gameTick 门控 + 共享预算）— ticker 与 JDTE flush 共用 */
	private final TickBatchSkipState skipState = new TickBatchSkipState();

	/** 上一tick是否在处理PB配方 — 用于检测PB停止时恢复SMELTING激活状态 */
	private boolean pbWasProcessing;

	/** 进程异常日志冷却器（tick 模式） */
	private final LogThrottle pbErrorThrottle = new LogThrottle();

	MekCentrifugeTickHandler(TileEntityMekCentrifuge tile, PbRecipeProcessor pbProcessor,
			TickAccelTracker tickAccelTracker) {
		this.tile = tile;
		this.pbProcessor = pbProcessor;
		this.tickAccelTracker = tickAccelTracker;
	}

	/**
	 * 服务端tick — 总是调用super以确保ejector被tick
	 * <br/>
	 * 参考Mekanism原版TileEntityNutritionalLiquifier的做法：总是调用super.onUpdateServer()，
	 * 确保TileEntityConfigurableMachine中的ejectorComponent.tickServer()被执行（否则输出无法自动弹出）。
	 * super会处理SMELTING配方（通过recipeCacheLookupMonitor.updateAndProcess()），
	 * PB配方在tryProcessPbRecipe中独立处理（内部会跳过有SMELTING配方的输入，避免双重处理）。
	 * <p>
	 * 声音控制：基础机器只有1个输入槽，不可能同时处理SMELTING和PB配方。
	 * PB停止时直接setActive(false)，如果SMELTING有配方在处理，下一tick的super会重新setActive(true)。
	 * <p>
	 * 注意：父类 TileEntityElectricMachine.onUpdateServer() 已经调用 energySlot.fillContainerOrConvert()，
	 * 子类不应重复调用，否则每tick会执行两次能量容器填充（造成无意义的性能开销）。
	 * <p>
	 * Task 4 批量收获模式：在 tick 入口检测加速倍率,采用"虚拟 tick 银行 + 每 tick 预算"策略：
	 * <ul>
	 *   <li>multiplier == 1（本 gameTick 第一次调用）：从虚拟 tick 银行取出批量预算（每真实 tick 上限受配置与 TPS 因子约束）,
	 *       设置到 pbProcessor,正常处理（产出 N 倍）</li>
	 *   <li>multiplier > 1（本 gameTick 后续调用）：仅入账（onTick 内完成）,跳过 PB 处理,
	 *       但仍调用 super 让 ejector 工作（避免产物滞留）</li>
	 * </ul>
	 *
	 * @return 是否需要发送客户端同步包（由 super 返回）
	 */
	boolean onUpdateServer() {
		// Task 4 批量收获模式：虚拟 tick 银行 + 每 tick 预算（对齐 JDTE 调度器哲学）
		// decideAction 内部完成 onTick 计数、同 gameTick 门控与共享预算取款
		boolean skipPb = false;
		int batchMultiplier = 1;
		if (tickAccelTracker != null) {
			Level level = tile.getLevel();
			if (level != null && !level.isClientSide) {
				TickBatchSkipState.TickAction action = skipState.decideAction(tickAccelTracker, level);
				if (action == TickBatchSkipState.TickAction.ALREADY_HANDLED) {
					// 同 gameTick 已由 JDTE flush 完整处理：完全跳过（含 super，避免双跑）
					return false;
				}
				skipPb = action == TickBatchSkipState.TickAction.SKIP;
				if (skipPb) {
					// 同 gameTick 后续 ticker 调用已由 decideAction 合并；对齐 JDTE
					// CoalescedAcceleratedMachine 语义，完全跳过 super/能量/AE2 重路径。
					// 同一 gameTick 只需首个入口完整处理一次。
					return false;
				}
				if (!skipPb) {
					batchMultiplier = skipState.getBatchMultiplier();
				}
			}
		}
		return runTick(skipPb, batchMultiplier);
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine.accumulateAcceleratedTicks} 委托入口
	 * <br/>
	 * 仅入账虚拟 tick 银行，不执行处理（flush 时统一执行一次完整批量）。
	 */
	void accumulateAcceleratedTicks(int ticks) {
		if (tickAccelTracker != null) {
			tickAccelTracker.addVirtualTicks(ticks);
		}
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine.flushAcceleratedTicks} 委托入口
	 * <br/>
	 * JDTE 对实现合并接口的目标不再循环调用 ticker，而是在批量 pass 结束时调用一次本方法：
	 * 从共享预算取本 tick 批量倍率并强制执行一次完整批量（super + PB 配方 + AE2 推送）。
	 * <p>
	 * 与 {@link #onUpdateServer} 共享同 gameTick 门控（{@link TickBatchSkipState#tryBeginGameTick}）：
	 * 无论 JDTE flush 在 ticker 之前还是之后调用，同一 gameTick 只执行一次完整处理，避免双跑。
	 */
	void flushAcceleratedTicks() {
		Level level = tile.getLevel();
		if (level == null || level.isClientSide || tickAccelTracker == null) {
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
	private boolean runTick(boolean skipPb, int batchMultiplier) {
		// 延迟连接 AE2 网格节点（避免在 clearRemoved 中连接导致递归栈溢出；内部有 isAe2Loaded 守卫）
		tile.ae2Handler().tryConnectNode();

		// 升级槽与 PB 批处理使用相同的真实游戏刻门控，避免 JDTE 子 tick 重复扫描。
		if (!skipPb) {
			tile.pbUpgradeHandler().processPbUpgradeInput();
		}

		// v2.0.0: 在 super 调用前从 AE 网络注入 FE 能量
		tile.productivebeesgenesis$injectAe2Energy(batchMultiplier);

		// super前保存能量，用于计算总消耗（SMELTING + PB），与工厂版逻辑保持一致
		var energyContainer = tile.accessor().productivebeesgenesis$getEnergyContainer();
		long energyBefore = energyContainer.getEnergy();

		boolean sendUpdatePacket = tile.callSuperOnUpdateServer();

		// SMELTING（电力熔炼炉）配方加速 — Mekanism 管线每调用 super 推进 1 tick，
		// 但批量收获模式下 super 每 gameTick 只调用一次（时间手杖的后续 ticker 调用
		// 与 JDTE flush 均被同 gameTick 门控跳过）。输入为 SMELTING 配方时轻量补调，
		// 使熔炉配方按批量倍率 M 推进（JDTE 时间加速器与 JDT 时间手杖均生效）。
		// PB 配方路径不需要补调：PbVirtualTickPlan 已在内部按倍率推进。
		if (batchMultiplier > 1) {
			// 轻量补调：仅推进已缓存熔炉配方（跳过 ejector/能量回填/每 tick 配方重查），
			// 语义等价于真实推进 batchMultiplier 次 tick，256x 加速下 MSPT 占用极低。
			if (tile.runLightSmeltingTicks(batchMultiplier)) {
				sendUpdatePacket = true;
			}
		}

		// 在配方处理前释放上 tick 遗留流体，避免满罐使本 tick 的高并行批次提前暂停。
		if (!skipPb && Ae2IntegrationLoader.isAe2Loaded()) {
			Ae2FluidPusher.pushFluids(tile);
		}

		// PB配方独立处理（不走Mekanism管线）
		if (!skipPb) {
			pbProcessor.setTickMultiplier(batchMultiplier);
			// 入口缓存刷新 — 与工厂版 MekCentrifugeFactoryHelper.processPbRecipesAndUpdate 保持一致
			pbProcessor.refreshFluidTankFullCache(tile);
			pbProcessor.refreshEnergyAndOpsCache(tile);
			boolean pbResult = tryProcessPbRecipe();
			if (pbResult) {
				tile.callSetActive(true);
				pbWasProcessing = true;
			} else if (pbWasProcessing) {
				tile.callSetActive(false);
				pbWasProcessing = false;
			}
			// 修复 v13: 同步 PB 处理进度到客户端
			pbProcessor.tickProgressSync();
		}

		// AE2 输入拉取 + 输出推送（AE2 未加载时短路，避免触发 appeng 类加载）
		if (!skipPb && Ae2IntegrationLoader.isAe2Loaded()) {
			Ae2InputPuller.pullInputs(tile, batchMultiplier);
			Ae2OutputPusher.pushOutputs(tile);
			// 配方执行期间写入本地罐的流体需要在同一真实 tick 内立即收尾排空。
			Ae2FluidPusher.pushLocalTankContentsNow(tile);
		}

		// 配方扣能后补回正常容量；稳态下 tick 开头会因容量已满而短路，
		// 因此不会把实际 AE2 取电调用翻倍，并可消除客户端能量条的锯齿同步。
		tile.productivebeesgenesis$injectAe2Energy(batchMultiplier);

		return sendUpdatePacket;
	}


	/**
	 * 尝试PB离心配方处理
	 * <br/>
	 * SMELTING配方优先于PB配方：如果输入物品存在SMELTING配方，则跳过PB处理，
	 * 交由super.onUpdateServer()的Mekanism管线处理，避免同一输入被双重处理。
	 * <p>
	 * 与工厂版的差异：
	 * <ul>
	 *   <li>基础机器的 active 由 onUpdateServer 中的 pbWasProcessing 逻辑管理，
	 *       setPbActiveState 为 no-op，因此 SMELTING 命中时用 resetPbState（不触发 setPbActiveState）</li>
	 *   <li>SMELTING 检查结果缓存由 PbRecipeProcessor.hasSmeltingRecipe 管理（与工厂版一致）</li>
	 * </ul>
	 *
	 * @return true 如果正在处理PB配方
	 */
	private boolean tryProcessPbRecipe() {
		try {
			if (tile.getLevel() == null || tile.getLevel().isClientSide) return false;
			if (!tile.canFunction()) return false;

			ItemStack input = tile.accessor().productivebeesgenesis$getInputSlot().getStack();
			if (input.isEmpty()) {
				// 空输入：重置 PB 状态和 SMELTING 缓存（与原版 clearPbState + lastCheckedInput=EMPTY 一致）
				pbProcessor.resetPbState(0);
				pbProcessor.resetSmeltingCache(0);
				return false;
			}

			// PB配方短路 — 万象创世蜜脾/蜜脾块或有PB离心配方的物品跳过 SMELTING 检查
			boolean isMyriad = MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
					|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
			// 优化3：复用已查找的 PB 配方，避免 tryProcessPbRecipe 内部再次 findPbRecipe
			// 万象创世路径下 preFoundRecipe 传 null（走 myriadHandler 特殊路径，不使用 PB 配方）
			RecipeHolder<CentrifugeRecipe> preFoundRecipe = isMyriad ? null : pbProcessor.findPbRecipe(input);
			boolean hasPbRecipe = isMyriad || preFoundRecipe != null;

			if (hasPbRecipe) {
				return pbProcessor.tryProcessPbRecipe(0, preFoundRecipe);
			}

			// SMELTING 配方检查（带缓存，输入变更时才重新查询）
			// SMELTING 优先于 PB，有 SMELTING 配方时跳过 PB 处理，交由 super 的 Mekanism 管线
			if (pbProcessor.hasSmeltingRecipe(0, input)) {
				// 有 SMELTING 配方：重置 PB 状态（不调用 setPbActiveState，避免与 SMELTING 的 setActive 冲突）
				pbProcessor.resetPbState(0);
				return false;
			}

			// 委托给 PbRecipeProcessor 处理 PB 配方（含万象创世路径、输出聚合、Task 8 输出槽满前置检查）
			return pbProcessor.tryProcessPbRecipe(0);
		} catch (Exception e) {
			// 捕获异常防止tick崩溃，记录错误日志并重置PB状态（节流避免刷屏）
			final Exception cause = e;
			// 统一使用 ms 时间源，避免 tick/ms 双模式混用导致节流失效（Task 15）
			pbErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
				ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 异常，重置PB状态"
						+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), cause);
			});
			pbProcessor.resetPbState(0);
			return false;
		}
	}
}
