package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneValue;
import org.jetbrains.annotations.Nullable;

/**
 * PB 基因属性的「优劣排序」语义。
 * <p>
 * <b>为什么需要这个类</b>：{@link GeneValue#getValue()} 只是各属性域内的<i>序号</i>，
 * 不是「越大越好」。PB 中：
 * <ul>
 *   <li>产量 / 耐力 / 天气耐受 / 行为：序号越大越好（very_high &gt; high &gt; medium &gt; normal）</li>
 *   <li><b>性格（TEMPER）：序号越大越差</b>——hostile(3) 会攻击玩家，passive(0) 最理想。
 *       PB 原版 {@link GeneValue#nextTemper} 的「改良」方向正是把 temper 往<b>低</b>推。</li>
 * </ul>
 * 若直接用 {@code getValue()} 比大小判断「能否提升」，自动喂食会把蜜蜂性格越喂越暴躁，
 * 并且永远认为 passive 蜜蜂「还能提升到 hostile」，从而无限消耗小食。
 * <p>
 * 本类把「序号」归一化为<b>期望度（desirability）</b>：数值越大越符合玩家预期，
 * 使上层调度逻辑可以统一用「目标期望度 &gt; 当前期望度」判断是否值得喂食。
 * <p>
 * 纯静态无状态工具类，线程安全。
 */
final class GeneAttributeRanking {

	/** 属性域内的最大序号（PB 各属性域取值范围），用于 TEMPER 的方向翻转。 */
	private static final int TEMPER_MAX_ORDINAL = 3;

	private GeneAttributeRanking() {
	}

	/**
	 * 把 PB 的属性序号换算为「期望度」——数值越大代表玩家越想要。
	 * <br/>
	 * TEMPER 方向翻转（passive 最优），其余属性保持原序。
	 *
	 * @param attribute 属性域
	 * @param value     PB 枚举值，{@code null} 视为该域最差
	 * @return 期望度（非负；越大越好）
	 */
	static int desirability(GeneAttribute attribute, @Nullable GeneValue value) {
		if (value == null || value == GeneValue.EMPTY) return 0;
		int ordinal = value.getValue();
		// 性格越低越好：翻转方向，使 passive(0) 得到最高期望度
		return attribute == GeneAttribute.TEMPER ? TEMPER_MAX_ORDINAL - ordinal : ordinal;
	}

	/**
	 * 判断某属性是否可被自动喂食调度。
	 * <br/>
	 * {@link GeneAttribute#TYPE} 是蜂种而非属性值：PB 的
	 * {@code HoneyTreat.interactLivingEntity} 对含 TYPE 的小食直接拒绝喂食
	 * （走 {@code invalid_use} 分支且不施加任何基因），所以必须整体排除含 TYPE 的小食。
	 *
	 * @param attribute 属性域
	 * @return true 表示该属性参与「能否提升」判定
	 */
	static boolean isRankable(GeneAttribute attribute) {
		return attribute != GeneAttribute.TYPE;
	}
}
