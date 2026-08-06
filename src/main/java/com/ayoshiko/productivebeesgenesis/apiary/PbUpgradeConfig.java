package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.ProductiveBeesConfig;

/**
 * PB 原版升级配置读取器 — 让本模组的升级效果跟随资源蜜蜂原版配置文件。
 * <br/>
 * PB 原版 {@code ProductiveBeesConfig.Upgrades} 定义了：
 * <ul>
 *   <li>{@code productivityMultiplier[1..4]}：α/β/γ/Ω 产量升级每级加成（默认 1.2/1.5/2.0/2.6）</li>
 *   <li>{@code timeBonus}：时间升级每级缩短比例（默认 0.15）</li>
 *   <li>{@code stabilityChanceIncrease}：稳定性升级概率加成（默认 0.15，离心机已直接读取）</li>
 * </ul>
 * 玩家修改 PB 配置文件后，本模组蜂箱/离心机的升级效果会同步生效。
 * <p>
 * 性能：{@code ModConfigSpec.DoubleValue.get()} 是内存值读取（config 加载时解析一次），
 * 开销约等于 volatile 读，可在升级倍率计算路径直接调用；上游倍率已有 100-tick 缓存。
 * <p>
 * 线程安全：PB 配置在服务端/客户端各自持有同步值，get() 线程安全。
 */
public final class PbUpgradeConfig {

	/** PB 原版 timeBonus 默认值（配置未加载/读取异常时回退） */
	private static final float DEFAULT_TIME_BONUS = 0.15f;

	private PbUpgradeConfig() {
	}

	/**
	 * 获取指定产量升级类型在 PB 配置中的每级加成。
	 *
	 * @param type 产量升级类型（仅 PRODUCTIVITY/2/3/4 有值）
	 * @return 配置值；配置未就绪或异常时回退枚举默认（与 PB 默认一致）
	 */
	public static float productivityMultiplier(PbUpgradeType type) {
		try {
			var upgrades = ProductiveBeesConfig.UPGRADES;
			if (upgrades == null) {
				return type.getDefaultProductivityFactor();
			}
			return switch (type) {
				case PRODUCTIVITY -> (float) upgrades.productivityMultiplier.get().doubleValue();
				case PRODUCTIVITY_2 -> (float) upgrades.productivityMultiplier2.get().doubleValue();
				case PRODUCTIVITY_3 -> (float) upgrades.productivityMultiplier3.get().doubleValue();
				case PRODUCTIVITY_4 -> (float) upgrades.productivityMultiplier4.get().doubleValue();
				default -> 0f;
			};
		} catch (RuntimeException e) {
			// 防御：PB 配置未加载/重载窗口期异常时回退枚举默认，不阻塞机器运行
			return type.getDefaultProductivityFactor();
		}
	}

	/**
	 * 获取 PB 时间升级的每级时间缩短比例（timeBonus）。
	 *
	 * @return 配置值；配置未就绪或异常时回退 0.15（PB 默认）
	 */
	public static float timeBonus() {
		try {
			var upgrades = ProductiveBeesConfig.UPGRADES;
			if (upgrades == null) {
				return DEFAULT_TIME_BONUS;
			}
			return (float) upgrades.timeBonus.get().doubleValue();
		} catch (RuntimeException e) {
			return DEFAULT_TIME_BONUS;
		}
	}
}
