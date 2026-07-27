package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 均匀分布求和采样工具 — 中心极限定理 (CLT) 实现
 * <br/>
 * 从 {@link PbRecipeCompleter} 抽取,遵循单一职责原则:仅负责 N 次 [min, max] 均匀分布之和的采样,
 * 不持有任何状态,纯函数式工具类。
 * <p>
 * 数学等价性:
 * <ul>
 *   <li>n=1: 走原版精确路径 (nextFloat),与单次采样完全一致</li>
 *   <li>min==max: 固定数量,无随机,返回 min * n * modifier</li>
 *   <li>n&gt;1 且 min&lt;max: Normal 近似 N 次均匀分布之和</li>
 * </ul>
 * 单次 U[min, max]: mean=(min+max)/2, var=((max-min+1)^2-1)/12;
 * N 次之和: mean=N*mean1, var=N*var1。
 * <br/>
 * 线程安全:无状态静态方法,所有依赖通过参数传入。
 */
public final class SampleUniformSum {

	private SampleUniformSum() {}

	/**
	 * Normal 近似 N 次 [min, max] 均匀分布之和(中心极限定理 CLT)
	 * <br/>
	 * n=1 时走原版精确采样(nextFloat),保持完全等价。min==max 时无随机。
	 *
	 * @param random   随机数生成器
	 * @param min      单次最小产出
	 * @param max      单次最大产出
	 * @param n        采样次数(&gt; 0)
	 * @param modifier 生产力倍率
	 * @return N 次采样的总和乘以 modifier
	 */
	public static long sample(ThreadLocalRandom random, int min, int max, long n, int modifier) {
		if (n <= 0) return 0;
		if (n == 1) {
			// 单次走原版精确路径
			int count = min;
			if (max > count) {
				count += random.nextInt(max - count + 1);
			}
			return (long) count * modifier;
		}
		if (min == max) {
			// 固定数量,无随机
			return (long) min * modifier * n;
		}
		// Normal 近似 N 次均匀分布之和
		double mean = (min + max) / 2.0 * n;
		double range = (max - min + 1);
		double variance = n * (range * range - 1) / 12.0;
		double stdDev = Math.sqrt(Math.max(0, variance));
		double gaussian = random.nextGaussian();
		long sum = Math.round(mean + gaussian * stdDev);
		// 限制在 [min*n, max*n] 范围内(防止 Normal 近似的极端值)
		long lower = (long) min * n;
		long upper = (long) max * n;
		sum = Math.max(lower, Math.min(upper, sum));
		return sum * modifier;
	}
}
