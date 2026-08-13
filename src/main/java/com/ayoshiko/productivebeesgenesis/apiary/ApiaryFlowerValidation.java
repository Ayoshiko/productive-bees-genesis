package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.resources.ResourceLocation;

/**
	 * 花朵有效性校验辅助 — 从 {@link BeeSlotTickProcessor#tick()}
	 * 循环内花朵检查代码块搬移。
	 * <br/>
	 * 使用 BeeSlot 内部 volatile 缓存避免 per-tick HashMap：
	 * cache hit 为 2 次 volatile 读，cache miss 才调用 {@link FeederSlotManager#hasValidFlower}。
	 */
final class ApiaryFlowerValidation {

	private ApiaryFlowerValidation() {
	}

	static boolean check(BeeSlot slot, ResourceLocation beeTypeKey, long currentTick,
			FeederSlotManager feederManager) {
		Boolean flowerValid = slot.consumeCachedFlowerValid(currentTick);
		if (flowerValid == null) {
			flowerValid = feederManager.hasValidFlower(beeTypeKey);
			slot.setCachedFlowerValid(currentTick, flowerValid);
		}
		return flowerValid;
	}
}
