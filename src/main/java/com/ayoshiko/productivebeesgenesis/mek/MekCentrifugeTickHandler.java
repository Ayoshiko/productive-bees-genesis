package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.item.ItemStack;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

/**
 * 基础MEK离心机服务端tick处理器
 * <br/>
 * Task 11 重构：从 {@link TileEntityMekCentrifuge} 抽取 onUpdateServer 与 tryProcessPbRecipe 逻辑，
 * 让主类聚焦于槽位/接口实现，tick流程独立维护。
 * <p>
 * 职责：
 * <ul>
 *   <li>调用 super.onUpdateServer()（通过 {@link TileEntityMekCentrifuge#callSuperOnUpdateServer}）
 *       触发 Mekanism SMELTING 管线与 ejector tick</li>
 *   <li>独立处理 PB 离心配方（不走 Mekanism CachedRecipe 管线）</li>
 *   <li>管理 active 状态切换（pbWasProcessing 标志位）</li>
 * </ul>
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
class MekCentrifugeTickHandler {

	/** 所属方块实体引用 */
	private final TileEntityMekCentrifuge tile;

	/** PB配方处理器 — 由主类持有并注入 */
	private final PbRecipeProcessor pbProcessor;

	/** 上一tick是否在处理PB配方 — 用于检测PB停止时恢复SMELTING激活状态 */
	private boolean pbWasProcessing;

	MekCentrifugeTickHandler(TileEntityMekCentrifuge tile, PbRecipeProcessor pbProcessor) {
		this.tile = tile;
		this.pbProcessor = pbProcessor;
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
	 *
	 * @return 是否需要发送客户端同步包（由 super 返回）
	 */
	boolean onUpdateServer() {
		// super前保存能量，用于计算总消耗（SMELTING + PB），与工厂版逻辑保持一致
		var energyContainer = tile.accessor().productivebeesgenesis$getEnergyContainer();
		long energyBefore = energyContainer.getEnergy();

		boolean sendUpdatePacket = tile.callSuperOnUpdateServer();

		// PB配方独立处理（不走Mekanism管线）
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

		// 不再重复调用 energySlot.fillContainerOrConvert() — 父类 super.onUpdateServer() 已处理
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
			// 捕获异常防止tick崩溃，记录错误日志并重置PB状态
			ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 异常，重置PB状态", e);
			pbProcessor.resetPbState(0);
			return false;
		}
	}
}
