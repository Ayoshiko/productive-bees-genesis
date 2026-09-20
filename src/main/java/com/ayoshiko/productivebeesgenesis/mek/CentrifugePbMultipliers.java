package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeConfig;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import java.util.function.ToIntFunction;

/** 物理升级处理器与封存升级共用公式；只读取数量与当前配置。 */
final class CentrifugePbMultipliers {
	private static final PbUpgradeType[] PRODUCTIVITY = {PbUpgradeType.PRODUCTIVITY, PbUpgradeType.PRODUCTIVITY_2,
			PbUpgradeType.PRODUCTIVITY_3, PbUpgradeType.PRODUCTIVITY_4};
	static float productivity(ToIntFunction<PbUpgradeType> counts) {
		if (!BalanceConfig.centrifugeProductivityAffectsOutput()) return 1;
		double result = 1;
		for (var type : PRODUCTIVITY) {
			result += (double) type.getProductivityFactor() * counts.applyAsInt(type);
		}
		return SaturatingMath.positiveFiniteFloat(result, 1);
	}
	static int parallel(ToIntFunction<PbUpgradeType> counts) {
		long result = (long) counts.applyAsInt(PbUpgradeType.PRODUCTIVITY) * 4
				+ (long) counts.applyAsInt(PbUpgradeType.PRODUCTIVITY_2) * 8
				+ (long) counts.applyAsInt(PbUpgradeType.PRODUCTIVITY_3) * 16
				+ (long) counts.applyAsInt(PbUpgradeType.PRODUCTIVITY_4) * 32;
		return Math.max(1, SaturatingMath.saturatingToInt(result));
	}
	static float time(ToIntFunction<PbUpgradeType> counts, float mekTime) {
		long effective = (long) counts.applyAsInt(PbUpgradeType.TIME) + (long) counts.applyAsInt(PbUpgradeType.TIME_2) * 2;
		float bonus = PbUpgradeConfig.timeBonus();
		if (Float.isNaN(bonus) || bonus <= 0) return mekTime;
		float divisor = SaturatingMath.positiveFiniteFloat(1.0D + (double) bonus * effective, 1);
		return SaturatingMath.positiveFiniteFloat((double) mekTime / divisor, 1);
	}
	static float stability(ToIntFunction<PbUpgradeType> counts) {
		return (float) PbOutputChance.stabilityBonus(counts.applyAsInt(PbUpgradeType.STABILITY),
				ProductiveBeesConfig.UPGRADES.stabilityChanceIncrease.get());
	}
	private CentrifugePbMultipliers() { }
}
