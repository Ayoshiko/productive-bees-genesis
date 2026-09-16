package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector;
import mekanism.api.RelativeSide;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.config.MekanismConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 机械蜂箱侧面配置与警告检查支持（纯静态，无状态）
 * <br/>
 * 从 {@link TileEntityMekApiary} 拆分而来，职责（SRP）：侧面 IO 配置初始化
 * 与 BeeState → RecipeError 的警告映射，不持有方块实体状态。
 */
final class ApiarySideConfigSupport {

	private ApiarySideConfigSupport() {
	}

	/**
	 * 设置蜂箱侧面配置和弹出器 — 覆盖父类单输入/输出配置；
	 * 蜂笼输出槽不参与弹出；快速路径仅由本模组专用弹出器承载
	 */
	static void setupSideConfig(TileEntityMekApiary tile) {
		// 物品 IO 配置：蜂笼输入槽作为输入，仅产物输出槽作为输出（蜂笼输出槽不参与 Ejector 弹出）
		List<mekanism.api.inventory.IInventorySlot> outputSlots = new ArrayList<>();
		outputSlots.addAll(tile.slotManager().getOutputSlots());
		tile.configComponent.setupItemIOConfig(
				Collections.singletonList(tile.slotManager().getCageInSlot()),
				outputSlots,
				tile.slotManager().getEnergySlot(), false);
		// 能量输入配置
		tile.configComponent.setupInputConfig(TransmissionType.ENERGY,
				tile.accessor().productivebeesgenesis$getEnergyContainer());
		// 流体输出配置（右侧）
		tile.configComponent.setupOutputConfig(TransmissionType.FLUID,
				tile.slotManager().getFluidTank(), RelativeSide.RIGHT);
		// 专用组件不修改 Mekanism 全局弹出器，原版及其它附属机器保持原行为。
		tile.ejectorComponent = GenesisTileComponentEjector.replace(tile, tile.ejectorComponent,
				MekanismConfig.general.chemicalAutoEjectRate, () -> Integer.MAX_VALUE);
		// 同时弹出物品和流体
		tile.ejectorComponent.setOutputData(tile.configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
		// 直连输出路由：侧面配置变化时立即标记直连检测，重新扫描目标离心机
		tile.configComponent.addConfigChangeListener(TransmissionType.ITEM,
				ignored -> tile.onDirectEjectRoutingChanged());
	}

	/**
	 * 重写警告检查 — 蜂箱不走 CachedRecipe 管线，手动映射 BeeState 到 RecipeError（Bug 10/1/5）
	 */
	static BooleanSupplier getWarningCheck(TileEntityMekApiary tile, RecipeError error) {
		if (error == RecipeError.NOT_ENOUGH_OUTPUT_SPACE) {
			return () -> tile.slotManager() != null && tile.slotManager().isOutputFull();
		}
		if (error == RecipeError.NOT_ENOUGH_ENERGY) {
			return () -> hasBeeInState(tile, BeeState.WAITING_ENERGY);
		}
		if (error == RecipeError.NOT_ENOUGH_INPUT) {
			return () -> hasBeeInState(tile, BeeState.WAITING_FLOWER);
		}
		return null;
	}

	/** 检查是否有蜜蜂处于指定状态 */
	private static boolean hasBeeInState(TileEntityMekApiary tile, BeeState state) {
		if (tile.slotManager() == null) return false;
		for (BeeSlot slot : tile.slotManager().getBeeSlots()) {
			if (slot.getState() == state) return true;
		}
		return false;
	}
}
