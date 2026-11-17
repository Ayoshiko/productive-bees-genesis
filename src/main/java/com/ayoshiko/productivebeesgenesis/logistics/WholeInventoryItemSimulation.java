package com.ayoshiko.productivebeesgenesis.logistics;

import net.minecraft.world.item.ItemStack;

/** 可选库存公开的整体模拟与交付入口；真实交付仍保留原外部能力包装。 */
public interface WholeInventoryItemSimulation {

	/** 当前动态处理器未接入整体模拟时，由调用方继续原逐槽模拟。 */
	int UNAVAILABLE = -1;

	/** 返回已校验的可接收量，或 {@link #UNAVAILABLE}；调用异常必须保留根因。 */
	int productivebeesgenesis$simulateWholeItemInsert(ItemStack stack);

	/** 调用方交付副本；返回实际接收量，UNAVAILABLE 仅表示尚未执行目标的原入口回退。 */
	default int productivebeesgenesis$executeWholeItemInsert(ItemStack stack) {
		return UNAVAILABLE;
	}
}
