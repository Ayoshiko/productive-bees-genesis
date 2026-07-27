package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FluidPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputPuller;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;

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
 * 采用"延迟一 tick"策略：本 gameTick 第一次 tick 时使用上一 gameTick 的最终 multiplier 作为批量倍率，
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
 *   <li>批量收获模式：Tick 加速检测 + 延迟一 tick 策略（Task 4）</li>
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

	/** 上一 gameTick 的最终加速倍率 — 用于本 gameTick 第一次 tick 时的批量倍率（延迟一 tick 策略） */
	private int lastTickMultiplier = 1;

	/** 本 gameTick 是否已处理过 — 用于跳过同一 gameTick 内后续重复 tick（调试用,逻辑上 multiplier>1 即可判断） */
	private long lastProcessedGameTick = Long.MIN_VALUE;

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
	 * Task 4 批量收获模式：在 tick 入口检测加速倍率,采用"延迟一 tick"策略：
	 * <ul>
	 *   <li>multiplier == 1（本 gameTick 第一次调用）：使用 lastTickMultiplier 作为批量倍率,
	 *       设置到 pbProcessor,正常处理（产出 N 倍）</li>
	 *   <li>multiplier > 1（本 gameTick 后续调用）：更新 lastTickMultiplier,跳过 PB 处理,
	 *       但仍调用 super 让 ejector 工作（避免产物滞留）</li>
	 * </ul>
	 *
	 * @return 是否需要发送客户端同步包（由 super 返回）
	 */
	boolean onUpdateServer() {
		// Task 4 批量收获模式：Tick 加速检测（延迟一 tick 策略）
		// TickAccelTracker 是事后统计的：同一 gameTick 内第一次调用时 multiplier=1,
		// 后续调用时 multiplier 才递增。因此使用上一 gameTick 的最终 multiplier 作为本 gameTick 的批量倍率。
		boolean skipPb = false;
		if (tickAccelTracker != null) {
			Level level = tile.getLevel();
			if (level != null && !level.isClientSide) {
				tickAccelTracker.onTick(level);
				int multiplier = tickAccelTracker.getMultiplier();
				long currentGameTick = level.getGameTime();

				if (multiplier > 1) {
					// 本 gameTick 后续调用：持续更新 lastTickMultiplier,跳过 PB 处理
					// 仍调用 super 让 ejector 工作（避免产物滞留）,方案 A
					lastTickMultiplier = multiplier;
					skipPb = true;
				} else {
					// multiplier == 1：本 gameTick 第一次调用
					// 使用 lastTickMultiplier 作为批量倍率（延迟一 tick 策略）
					int batchMultiplier = lastTickMultiplier;
					lastTickMultiplier = 1; // 重置,本 gameTick 后续调用会更新
					lastProcessedGameTick = currentGameTick;
					// 设置 PbRecipeProcessor 的批量倍率（应用到 effectiveOps 和万象路径）
					pbProcessor.setTickMultiplier(batchMultiplier);
				}
			}
		}

		// 延迟连接 AE2 网格节点（避免在 clearRemoved 中连接导致递归栈溢出；内部有 isAe2Loaded 守卫）
		tile.ae2Handler().tryConnectNode();

		// PB 升级输入槽自动安装（与工厂版一致；内部有 level/clientSide/空输入守卫）
		tile.pbUpgradeHandler().processPbUpgradeInput();

		// v1.8.0: 在 super 调用前从 AE 网络注入 FE 能量
		// 让父类 super.onUpdateServer() 处理 SMELTING 配方消耗时已有注入的能量可用
		// 守卫（AE2 加载 / 配置启用 / grid 非 null）由 injectAe2Energy() 内部处理
		tile.productivebeesgenesis$injectAe2Energy();

		// super前保存能量，用于计算总消耗（SMELTING + PB），与工厂版逻辑保持一致
		var energyContainer = tile.accessor().productivebeesgenesis$getEnergyContainer();
		long energyBefore = energyContainer.getEnergy();

		boolean sendUpdatePacket = tile.callSuperOnUpdateServer();

		// PB配方独立处理（不走Mekanism管线）
		// Task 4: 批量收获模式下,本 gameTick 后续 tick 跳过 PB 处理（super 仍调用让 ejector 工作）
		if (!skipPb) {
			// 入口缓存刷新 — 与工厂版 MekCentrifugeFactoryHelper.processPbRecipesAndUpdate 保持一致
			// 修复重构遗漏:未刷新时 cachedEnergyPerTick/cachedOperationsPerTick 保持默认 0,
			// 导致 PbRecipeProcessor 内 effectiveOps = cachedOps * multiplier = 0 * 1 = 0,
			// 永远命中 (effectiveOps <= 0) 提前返回,基础离心机无法处理任何蜜脾。
			// skipPb 为 true 时（批量收获模式跳过 PB）不刷新,避免无谓刷新开销。
			pbProcessor.refreshFluidTankFullCache(tile);
			pbProcessor.refreshEnergyAndOpsCache(tile);
			boolean pbResult = tryProcessPbRecipe();
			if (pbResult) {
				tile.callSetActive(true);
				pbWasProcessing = true;
			} else if (pbWasProcessing) {
				// PB刚停止 — 直接设为false
				// 基础机器只有1个输入槽，PB停止时不可能有SMELTING在处理
				// 如果SMELTING有配方，下一tick的super.onUpdateServer()会重新setActive(true)
				tile.callSetActive(false);
				pbWasProcessing = false;
			}
			// 修复 v13: 同步 PB 处理进度到客户端,使 GUI 进度条正确显示
			// tickProgressSync 内部有节流:低进程每 tick 同步,高进程(≥9)每 5 tick 同步
			pbProcessor.tickProgressSync();
		}

		// 不再重复调用 energySlot.fillContainerOrConvert() — 父类 super.onUpdateServer() 已处理

		// AE2 输入拉取 + 输出推送（AE2 未加载时短路，避免触发 appeng 类加载）
		// 拉取间隔由 Ae2InputPuller 内部基于 lastPullTick 和配置 aeInputIntervalTicks 控制
		// 输出推送内部有 per-tile 开关和网格连接守卫，未启用时安全短路
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			Ae2InputPuller.pullInputs(tile);
			Ae2OutputPusher.pushOutputs(tile);
			// Task 13: 多槽推送 — 内部遍历 host.fluidOutputTankCount() 个槽
			Ae2FluidPusher.pushFluids(tile);
		}

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
