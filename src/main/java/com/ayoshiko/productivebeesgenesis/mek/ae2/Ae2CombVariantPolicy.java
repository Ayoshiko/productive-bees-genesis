package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * 蜜脾精确条目「键对齐」的纯判定，不接触任何 AE2/Minecraft 类型，便于单测直接覆盖。
 *
 * @see Ae2CombKeyAlignment
 */
final class Ae2CombVariantPolicy {

	private Ae2CombVariantPolicy() {
		// 工具类禁止实例化
	}

	/**
	 * 是否允许改写条目指向的键。
	 * <p>
	 * 精确模式下键相等本身就是匹配条件，玩家选定的那一个组件变体必须原样保留；
	 * 非精确模式按 bee_type 分组匹配，换成同蜂种的另一个变体不改变任何拉取判定。
	 */
	static boolean allowsRealign(boolean precise) {
		return !precise;
	}

	/**
	 * 该条目是否需要去网络里找真实存在的同种键。
	 *
	 * @param configuredIsComb 配置键是否为蜜脾类物品（熔炼物品条目的精确性就是其存在意义，绝不改写）
	 * @param beeTypeKnown     能否取出 bee_type（取不出就无从判断"同种"）
	 * @param configuredAmount 配置键在网络快照里的库存；> 0 说明这个键本身就在网络里，不必改
	 */
	static boolean needsRealign(boolean configuredIsComb, boolean beeTypeKnown, long configuredAmount) {
		return configuredIsComb && beeTypeKnown && configuredAmount <= 0L;
	}

	/** 候选是否优于当前最佳：同蜂种里取库存最多的一个，库存非正的候选一律不取。 */
	static boolean isBetterVariant(long bestAmount, long candidateAmount) {
		return candidateAmount > 0L && candidateAmount > bestAmount;
	}
}
