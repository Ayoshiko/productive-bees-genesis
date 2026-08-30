package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * 低 TPS 时的输入拉取降级闸门（无状态纯函数）。
 * <p>
 * <b>为什么不能再用「TPS &lt; 10 直接 return」</b>：AE2 输出推送、配方加工、产物回送
 * 都没有这道闸门，只有输入拉取有。于是服务器长期 MSPT &gt; 100ms 时，玩家看到的
 * 现象正是「机器在线，加工和产物回送都正常，就是不从 AE2 拉取」——而且因为 100-tick
 * 滚动平均在繁忙服上可能长时间贴在阈值附近，两台相同配置的机器会一台正常一台不拉，
 * 并随负载起伏自行恢复。二值闸门把「性能保护」变成了「功能缺失」。
 * <p>
 * 改为<b>限流而非停机</b>：低 TPS 下每 {@link #LOW_TPS_ALLOW_EVERY_N_CALLS} 次调用
 * 放行一次。速率本身已由 {@code ServerTickTimeMonitor.getTpsFactor()} 降到 0.1 倍，
 * 两者叠加后卡服时的拉取开销约为满速的 1/200，同时保证机器永不断供。
 */
final class Ae2LowTpsGate {

	/** 触发降级的 TPS 阈值（对应 avgMspt &gt; 100ms）。 */
	static final double LOW_TPS_THRESHOLD = 10.0;

	/** 低 TPS 时每 N 次调用放行一次，保证不完全断供。 */
	static final long LOW_TPS_ALLOW_EVERY_N_CALLS = 20L;

	private Ae2LowTpsGate() {
	}

	/**
	 * 是否跳过本次拉取。
	 *
	 * @param tps         当前估算 TPS
	 * @param pullCounter 本机内部拉取调用计数（单调递增，兼容加速模组）
	 */
	static boolean shouldSkip(double tps, long pullCounter) {
		if (tps >= LOW_TPS_THRESHOLD) return false;
		// Math.floorMod 而非 %：pullCounter 理论上可为负（计数器重置组合），
		// 负余数会让放行点分布错位。
		return Math.floorMod(pullCounter, LOW_TPS_ALLOW_EVERY_N_CALLS) != 0L;
	}
}
