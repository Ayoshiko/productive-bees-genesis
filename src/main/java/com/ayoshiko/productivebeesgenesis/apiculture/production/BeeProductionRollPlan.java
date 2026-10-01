package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import java.math.BigDecimal;

/**
 * 一个蜂生产周期的精确轮数合同；调用者提供已绑定周期的随机样本，不读取或推进任何随机源。
 * 固定单产物可直接乘每轮数量；概率配方仍须另行预算化采样，不能把轮数当成全部产物。
 */
public final class BeeProductionRollPlan {
	private static final ProductAmount ONE = ProductAmount.of(1);
	private final ProductAmount fixedRolls;
	private final double extraChance;

	private BeeProductionRollPlan(ProductAmount fixedRolls, double extraChance) {
		this.fixedRolls = fixedRolls;
		this.extraChance = extraChance;
	}

	/** 使用已按物理蜂箱公式得到的 float 倍率；不再次计算或截断升级数量。 */
	public static BeeProductionRollPlan fromMultiplier(float multiplier) {
		if (!Float.isFinite(multiplier) || multiplier <= 0)
			throw new IllegalArgumentException("Invalid bee production multiplier");
		double value = multiplier;
		// 此范围的 float 都是整数。double 精确容纳 float，不能先强转 long 或用十进制显示字符串。
		if (value >= 0x1.0p63)
			return new BeeProductionRollPlan(ProductAmount.of(new BigDecimal(value).toBigIntegerExact()), 0);
		long whole = (long) value;
		return new BeeProductionRollPlan(ProductAmount.of(whole), value - whole);
	}

	public ProductAmount fixedRolls() { return fixedRolls; }
	public double extraChance() { return extraChance; }

	/** 样本位于 [0,1)；恰等于概率时不增加轮数，与独立蜂箱的单次 Bernoulli 边界一致。 */
	public ProductAmount sampleCycle(double draw) {
		if (!Double.isFinite(draw) || draw < 0 || draw >= 1)
			throw new IllegalArgumentException("Invalid bee cycle sample");
		return draw < extraChance ? fixedRolls.add(ONE) : fixedRolls;
	}

	/** 同一周期的固定单栈产物：每轮先应用基因取整，再精确汇总，不饱和总数量。 */
	public ProductAmount guaranteedOutput(int baseCount, int productivityLevel, double draw) {
		if (baseCount < 1 || productivityLevel < 0 || productivityLevel > 3)
			throw new IllegalArgumentException("Invalid guaranteed bee output");
		return sampleCycle(draw).multiply(BeeProductionSampling.adjustStackCount(baseCount, productivityLevel));
	}
}
