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
		// 花源类型随既有版本失效；容器内容仍由管理器在每个真实 tick 重新核验。
		Boolean containerFlower = slot.cachedContainerFlower(cacheVersion);
		if (containerFlower == null) {
			containerFlower = feederManager.hasContainerFlower(beeTypeKey);
			slot.cacheContainerFlower(cacheVersion, containerFlower);
		}
		if (containerFlower) return feederManager.hasValidFlower(beeTypeKey);
		Boolean flowerValid = slot.consumeCachedFlowerValid(cacheVersion);
		if (flowerValid == null) {
			flowerValid = feederManager.hasValidFlower(beeTypeKey);
			slot.setCachedFlowerValid(cacheVersion, flowerValid);
		}
		return flowerValid;
	}
}
