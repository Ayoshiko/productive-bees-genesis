package com.ayoshiko.productivebeesgenesis.logistics;

import net.minecraft.world.item.ItemStack;

/** 可选库存公开的整体模拟入口；不修改源模板或目标资产，真实交付仍走原能力。 */
public interface WholeInventoryItemSimulation {

	/** 当前动态处理器未接入整体模拟时，由调用方继续原逐槽模拟。 */
	int UNAVAILABLE = -1;

	/** 返回已校验的可接收量，或 {@link #UNAVAILABLE}；调用异常必须保留根因。 */
	int productivebeesgenesis$simulateWholeItemInsert(ItemStack stack);
}
