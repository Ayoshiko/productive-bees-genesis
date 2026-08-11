package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.Upgrade;
import mekanism.common.config.MekanismConfig;

/**
 * 蜂箱升级倍率公式与诊断查询（纯静态，无状态）
 * <br/>
 * 从 {@link ApiaryUpgradeHandler} 拆分而来，职责（SRP）：MEK 速度/能量升级的
 * 纯公式计算与诊断 getter，不持有方块实体状态。
 */
final class ApiaryUpgradeMath {

	private ApiaryUpgradeMath() {
	}

	/** 纯公式：MEK 速度升级时间倍率（运行时入口由 {@code MekanismUtils} 承接可选模组 mixin） */
	static float computeMekSpeedTimeMultiplier(int speedUpgrades, int maxSpeed, float maxMultiplier) {
		if (maxSpeed <= 0 || speedUpgrades <= 0 || maxMultiplier <= 0) return 1.0f;
		float speedFraction = (float) speedUpgrades / maxSpeed;
		return (float) Math.pow(maxMultiplier, -speedFraction);
	}

	/**
	 * 纯公式版本，用于验证默认 Mekanism 升级语义；运行时仍通过
	 * {@code MekanismUtils#getEnergyPerTick} 承接可选模组的公式 mixin。
	 */
	static float computeMekSpeedEnergyMultiplier(int speedUpgrades, int energyUpgrades,
			int maxUpgrades, float maxMultiplier) {
		if (maxUpgrades <= 0 || maxMultiplier <= 0.0f) return 1.0f;
		float exponent = (2.0f * speedUpgrades - energyUpgrades) / maxUpgrades;
		return (float) Math.pow(maxMultiplier, exponent);
	}

	/**
	 * 获取 MEK 速度升级的最大安装数量
	 * <br/>
	 * 来源：{@link Upgrade#SPEED#getMax()}。MEK 原版返回 8，Mekanism Unleashed 扩展到 32。
	 *
	 * @return 速度升级最大数量
	 */
	static int getMaxSpeedUpgrades() {
		return Upgrade.SPEED.getMax();
	}

	/**
	 * 获取 MEK 升级倍率上限
	 * <br/>
	 * 来源：{@code MekanismConfig.general.maxUpgradeMultiplier}。默认 10.0，
	 * Mekanism Unleashed (MU) 可能修改为 1200+。
	 *
	 * @return 升级倍率上限
	 */
	static float getMaxUpgradeMultiplier() {
		return MekanismConfig.general.maxUpgradeMultiplier.get();
	}

	/**
	 * 获取 PB 时间升级的除数分量
	 * <br/>
	 * 公式：{@code 1.0 + timeBonus × effectiveTimeUpgrades}（timeBonus 读取 PB 原版配置），
	 * 其中 {@code effectiveTimeUpgrades = timeCount + time2Count × 2}。
	 *
	 * @return PB 时间升级除数（≥1.0）
	 */
	static float getPbTimeDivisor(ApiaryUpgradeHandler handler) {
		int timeCount = handler.getInstalledUpgrades(PbUpgradeType.TIME);
		int time2Count = handler.getInstalledUpgrades(PbUpgradeType.TIME_2);
		int effectiveTimeUpgrades = timeCount + time2Count * 2;
		return 1.0f + PbUpgradeConfig.timeBonus() * effectiveTimeUpgrades;
	}

	/**
	 * 获取能量容量倍率
	 * <br/>
	 * 公式：{@code Math.pow(2, energyUpgrades)}。仅供 GUI 显示参考。
	 *
	 * @return 能量容量倍率（≥1.0）
	 */
	static float getEnergyCapacityMultiplier(ApiaryUpgradeHandler handler) {
		int energyUpgrades = handler.getMekEnergyUpgrades();
		return (float) Math.pow(2, energyUpgrades);
	}
}
