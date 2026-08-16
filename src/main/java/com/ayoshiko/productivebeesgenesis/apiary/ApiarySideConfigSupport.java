package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import mekanism.api.RelativeSide;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentEjector;

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
	 * 蜂笼输出槽不参与弹出；tickDelay=1 由 Mixin 动态调整
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
		// 创建弹出器组件，设置 tickDelay 为 1（实际延迟由 Mixin 动态调整）
		tile.ejectorComponent = new TileComponentEjector(tile);
		((TileEntityEjectorAccessor) tile.ejectorComponent).productivebeesgenesis$setTickDelay(1);
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
