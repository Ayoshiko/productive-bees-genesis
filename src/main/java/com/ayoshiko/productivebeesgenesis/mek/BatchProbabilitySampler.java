package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.ThreadLocalRandom;

/**
	 * 批量概率采样器 — 封装 Binomial/Poisson/CLT 三路采样与保底机制
	 * <br/>
	 * 用于 {@link PbRecipeCompleter#accumulatePbRecipeOutputsBatch} 中
	 * chance &lt; 1.0 的概率产物批量采样。根据 N（批量次数）与 λ=N×p（期望通过次数）
	 * 自动选择最优算法：
	 * <ul>
	 *   <li>N=1：原版 Bernoulli 路径（调用方处理）</li>
	 *   <li>2 ≤ N ≤ 30：精确 Binomial 逆变换采样（O(N) 时间，CLT 在小 N 下误差较大）</li>
	 *   <li>N &gt; 30 且 λ &lt; 5：Poisson 近似（Knuth 算法 O(λ)，二项分布退化场景）</li>
	 *   <li>N &gt; 30 且 λ ≥ 5：CLT 正态近似（原有路径，O(1)）</li>
	 * </ul>
	 * <p>
	 * 保底机制（SubTask 4.4）：将期望产量 N×p 拆分为确定性部分 floor(N×p) 与
	 * 随机部分剩余 N-floor(N×p) 次 Binomial 采样，调整概率保持期望一致。
	 * 数学等价性：E[guaranteed + Binomial(remaining, adjustedP)]
	 * = floor(Np) + remaining × (Np - floor(Np))/remaining = Np。
	 * <p>
	 * 线程安全：所有方法为静态、无状态、仅依赖 {@link ThreadLocalRandom}，可并发调用。
	 */
public final class BatchProbabilitySampler {

	/** 精确 Binomial 采样的 N 上限（含），N ≤ 此值走逆变换采样 */
	static final int EXACT_BINOMIAL_MAX_N = 30;

	/** Poisson 近似的 λ 阈值（不含），λ &lt; 此值且 N ≥ 30 走 Poisson */
	static final double POISSON_LAMBDA_THRESHOLD = 5.0;

	/**
	 * Task 13：N 的安全上限 — 防止极端参数下的数值溢出或性能问题
	 * <br/>
	 * STACK=16 + 256x 加速下 effectiveOps 最大 65536，远低于此上限。
	 * 超过此值时强制截断，保证：
	 * <ul>
	 *   <li>{@code (long)(n * p)} 不超过 Long.MAX_VALUE</li>
	 *   <li>{@code (int) guaranteed} 不溢出</li>
	 *   <li>{@code sampleBinomialExact} 的 O(N) 循环不会 runaway（虽然 N ≤ 30 才走此路径）</li>
	 * </ul>
	 */
	static final int MAX_SAFE_N = 1_000_000;

	private BatchProbabilitySampler() {
		// 工具类禁止实例化
	}

	/**
	 * 采样 Binomial(N, p) — 根据 N 和 λ 自动选择最优算法
	 * <br/>
	 * 调用方应先经过保底机制拆分，因此本方法的 N 通常为 remaining（≤ 原 batchCount）。
	 * <p>
	 * Task 13 sanity check：
	 * <ul>
	 *   <li>NaN/Infinity p 视为极端值，返回 0 或 N</li>
	 *   <li>N 超过 {@link #MAX_SAFE_N} 时截断并记日志</li>
	 *   <li>返回值强制 clamp 到 [0, n] 范围</li>
	 * </ul>
	 *
	 * @param random 线程局部随机数生成器
	 * @param n      试验次数（≥ 0）
	 * @param p      成功概率（[0,1]）
	 * @return 成功次数 [0, n]
	 */
	public static int sampleBinomial(ThreadLocalRandom random, int n, double p) {
		if (n <= 0) return 0;
		// Task 13：NaN/Infinity 守卫
		if (Double.isNaN(p) || Double.isInfinite(p)) return 0;
		if (p <= 0.0) return 0;
		if (p >= 1.0) return n;
		// Task 13：N 安全上限截断
		if (n > MAX_SAFE_N) {
			n = MAX_SAFE_N;
		}
		if (n == 1) return random.nextDouble() < p ? 1 : 0;
		int result;
		if (n <= EXACT_BINOMIAL_MAX_N) {
			result = sampleBinomialExact(random, n, p);
		} else {
			double lambda = (double) n * p;
			if (lambda < POISSON_LAMBDA_THRESHOLD) {
				result = samplePoissonKnuth(random, lambda, n);
			} else {
				result = sampleBinomialCLT(random, n, p);
			}
		}
		// Task 13：最终 clamp 防止数值漂移
		return Math.max(0, Math.min(n, result));
	}

	/**
	 * 保底机制 + Binomial 采样（SubTask 4.4）
	 * <br/>
	 * 将 N×p 拆分为：
	 * <ul>
	 *   <li>guaranteed = floor(N×p) — 确定性保底产量</li>
	 *   <li>remaining = N - guaranteed — 剩余试验次数</li>
	 *   <li>adjustedP = (N×p - guaranteed) / remaining — 调整后概率，保持期望一致</li>
	 * </ul>
	 * 总产量 = guaranteed + sampleBinomial(remaining, adjustedP)。
	 * <br/>
	 * 退化处理：
	 * <ul>
	 *   <li>p ≤ 0 或 N ≤ 0：返回 0</li>
	 *   <li>p ≥ 1：返回 N</li>
	 *   <li>remaining ≤ 0（即 guaranteed ≥ N）：返回 guaranteed</li>
	 * </ul>
	 * <p>
	 * Task 13 sanity check：
	 * <ul>
	 *   <li>NaN/Infinity p 视为极端值，返回 0 或 N</li>
	 *   <li>N 超过 {@link #MAX_SAFE_N} 时截断</li>
	 *   <li>guaranteed clamp 到 [0, n] 防止 (int) guaranteed 溢出</li>
	 *   <li>返回值强制 clamp 到 [0, n] 范围</li>
	 * </ul>
	 *
	 * @param random 线程局部随机数生成器
	 * @param n      批量次数（≥ 0）
	 * @param p      单次成功概率（[0,1]）
	 * @return 总成功次数（保底 + 随机），范围 [0, n]
	 */
	public static long sampleBinomialWithGuarantee(ThreadLocalRandom random, int n, double p) {
		if (n <= 0) return 0L;
		// Task 13：NaN/Infinity 守卫
		if (Double.isNaN(p) || Double.isInfinite(p)) return 0L;
		if (p <= 0.0) return 0L;
		if (p >= 1.0) return (long) n;
		// Task 13：N 安全上限截断
		if (n > MAX_SAFE_N) {
			n = MAX_SAFE_N;
		}
		long guaranteed = (long) (n * p);
		// Task 13：guaranteed clamp 到 [0, n] 防止 (int) guaranteed 溢出
		if (guaranteed >= n) return (long) n;
		if (guaranteed < 0) return 0L;
		int remaining = n - (int) guaranteed;
		if (remaining <= 0) return guaranteed;
		double adjustedP = ((double) n * p - guaranteed) / remaining;
		if (adjustedP <= 0.0) return guaranteed;
		if (adjustedP >= 1.0) return guaranteed + remaining;
		int sample = sampleBinomial(random, remaining, adjustedP);
		long result = guaranteed + sample;
		// Task 13：最终 clamp 防止数值漂移
		return Math.max(0, Math.min(n, result));
	}

	/**
	 * 精确 Binomial 采样（N ≤ 30）— 逆变换采样
	 * <br/>
	 * 利用递推关系构建 CDF：P(X=k) = P(X=k-1) × (N-k+1)/k × p/(1-p)。
	 * 时间复杂度 O(N)，N ≤ 30 时仅需 30 次浮点运算，远优于 N 次 Bernoulli 采样。
	 * <br/>
	 * 数学性质：精确匹配二项分布，无 CLT 近似误差。
	 *
	 * @param random 线程局部随机数生成器
	 * @param n      试验次数（≥ 1）
	 * @param p      成功概率（(0,1)）
	 * @return 成功次数 [0, n]
	 */
	private static int sampleBinomialExact(ThreadLocalRandom random, int n, double p) {
		double q = 1.0 - p;
		double u = random.nextDouble();
		// P(X=0) = q^N
		double prob = Math.pow(q, n);
		double cdf = prob;
		if (u <= cdf) return 0;
		for (int k = 1; k <= n; k++) {
			// 递推：P(X=k) = P(X=k-1) × (N-k+1)/k × p/q
			prob *= (double) (n - k + 1) / k * p / q;
			cdf += prob;
			if (u <= cdf) return k;
		}
		// 浮点累加误差兜底（理论上 cdf[n] ≈ 1.0）
		return n;
	}

	/**
	 * Poisson 采样（Knuth 算法） — O(λ) 时间复杂度
	 * <br/>
	 * 算法：L = exp(-λ), k = 0, p = 1; do { k++; p *= uniform(); } while (p > L); return k-1。
	 * <br/>
	 * 适用场景：N ≥ 30 且 λ = N×p &lt; 5（二项分布退化为 Poisson）。
	 * λ &lt; 5 时循环次数 ≤ 5，性能优于 CLT 的 nextGaussian 计算。
	 * <br/>
	 * 数学性质：Poisson(λ) 是 Binomial(N,p) 在 N→∞、p→0、Np=λ 时的极限分布。
	 * 当 N ≥ 30 且 λ &lt; 5 时近似误差 &lt; 1%。
	 *
	 * @param random 线程局部随机数生成器
	 * @param lambda Poisson 参数（= N×p，&gt; 0）
	 * @param n      原 Binomial 的 N，用于上限截断（避免 Poisson 长尾超过 N）
	 * @return 成功次数 [0, n]
	 */
	private static int samplePoissonKnuth(ThreadLocalRandom random, double lambda, int n) {
		double L = Math.exp(-lambda);
		int k = 0;
		double p = 1.0;
		do {
			k++;
			p *= random.nextDouble();
		} while (p > L && k < n);
		// k 此时为首次使 p ≤ L 的整数，返回 k-1；若因 k<n 截断，返回 n
		return k < n ? k - 1 : n;
	}

	/**
	 * CLT 正态近似 Binomial(N, p) — O(1) 时间复杂度
	 * <br/>
	 * Binomial(N,p) 在 N 大且 p 不极端时近似 Normal(Np, Np(1-p))。
	 * <br/>
	 * 适用场景：N &gt; 30 且 λ ≥ 5（CLT 近似误差 &lt; 1%）。
	 * <br/>
	 * 退化处理：方差极小（stdDev &lt; 0.5）时直接取均值，避免 Normal 离散化误差。
	 *
	 * @param random 线程局部随机数生成器
	 * @param n      试验次数（≥ 1）
	 * @param p      成功概率（(0,1)）
	 * @return 成功次数 [0, n]
	 */
	private static int sampleBinomialCLT(ThreadLocalRandom random, int n, double p) {
		double mean = (double) n * p;
		double variance = (double) n * p * (1.0 - p);
		double stdDev = Math.sqrt(variance);
		if (stdDev < 0.5) {
			// 方差极小（p 接近 0 或 1）直接取均值避免 Normal 离散误差
			return (int) Math.round(mean);
		}
		long k = Math.round(mean + random.nextGaussian() * stdDev);
		return (int) Math.max(0, Math.min(n, k));
	}
}
