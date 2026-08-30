package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.resources.ResourceLocation;

/**
	 * 花朵有效性校验辅助 — 从 {@link BeeSlotTickProcessor#tick()}
	 * 循环内花朵检查代码块搬移。
	 * <br/>
	 * 使用 BeeSlot 内部版本缓存：喂食槽、转化开关和转化配方都未变化时跨 tick 复用，
	 * cache miss 才调用 {@link FeederSlotManager#hasValidFlower}。
	 */
final class ApiaryFlowerValidation {

	private ApiaryFlowerValidation() {
	}

	static boolean check(BeeSlot slot, ResourceLocation beeTypeKey, int cacheVersion,
			FeederSlotManager feederManager) {
		Boolean flowerValid = slot.consumeCachedFlowerValid(cacheVersion);
		if (flowerValid == null) {
			flowerValid = feederManager.hasValidFlower(beeTypeKey);
			slot.setCachedFlowerValid(cacheVersion, flowerValid);
		}
		return flowerValid;
	}
}
