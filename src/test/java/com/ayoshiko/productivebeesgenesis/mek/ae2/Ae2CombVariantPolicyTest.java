package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 蜜脾键对齐的纯判定测试 —— 不接触 AE2/Minecraft 运行时。 */
class Ae2CombVariantPolicyTest {

	@Test
	@DisplayName("精确模式绝不改写玩家选定的变体")
	void preciseModeForbidsRealign() {
		assertFalse(Ae2CombVariantPolicy.allowsRealign(true));
		assertTrue(Ae2CombVariantPolicy.allowsRealign(false));
	}

	@Test
	@DisplayName("只有蜜脾、蜂种已知且配置键在网络里查不到时才需要对齐")
	void realignOnlyForMissingCombKeys() {
		assertTrue(Ae2CombVariantPolicy.needsRealign(true, true, 0L));
		// 配置键本身就在网络里 → 不动
		assertFalse(Ae2CombVariantPolicy.needsRealign(true, true, 1L));
		// 熔炼物品条目的精确性就是其存在意义
		assertFalse(Ae2CombVariantPolicy.needsRealign(false, true, 0L));
		// 取不出 bee_type 就无从判断「同种」
		assertFalse(Ae2CombVariantPolicy.needsRealign(true, false, 0L));
	}

	@Test
	@DisplayName("同蜂种候选取库存最多的一个，库存非正一律不取")
	void bestVariantWins() {
		assertTrue(Ae2CombVariantPolicy.isBetterVariant(0L, 1L));
		assertTrue(Ae2CombVariantPolicy.isBetterVariant(64L, 65L));
		assertFalse(Ae2CombVariantPolicy.isBetterVariant(64L, 64L));
		assertFalse(Ae2CombVariantPolicy.isBetterVariant(64L, 63L));
		assertFalse(Ae2CombVariantPolicy.isBetterVariant(0L, 0L));
		assertFalse(Ae2CombVariantPolicy.isBetterVariant(0L, -5L));
	}
}
