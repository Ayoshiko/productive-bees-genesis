package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.util.RandomSource;

/**
 * 用均匀顺序统计量拆分二项分布，不使用正态／Poisson 近似或期望值。
 * 第 k 个顺序统计量 X~Beta(k,n+1-k)；按 p 位于 X 的左右条件化，剩余问题规模减半。
 */
public final class ExactBinomialSampler {
    private ExactBinomialSampler() {}
    private static final long MAX_CHUNK = 1L << 40;

    public static long sample(RandomSource random, long n, double p) {
        if (n < 0 || !Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Invalid binomial parameters");
        if (n == 0 || p == 0) return 0;
        if (p == 1) return n;
        long result = 0;
        // double 能精确表示每块的整数形状参数；long 总数不截断，也不改变概率。
        while (n > MAX_CHUNK) { result += chunk(random, MAX_CHUNK, p); n -= MAX_CHUNK; }
        return result + chunk(random, n, p);
    }

    private static long chunk(RandomSource random, long n, double p) {
        long result = 0;
        while (n > 32 && p > 0 && p < 1) {
            long k = n / 2 + 1;
            double a = gamma(random, k), b = gamma(random, n + 1 - k);
            double split = a / (a + b);
            if (p < split) {
                n = k - 1;
                p /= split;
            } else {
                result += k;
                n -= k;
                p = (p - split) / (1 - split);
            }
        }
        if (p >= 1) return result + n;
        for (long i = 0; i < n; i++) if (random.nextDouble() < p) result++;
        return result;
    }

    /** Marsaglia-Tsang 的 Gamma 接受拒绝采样，shape >= 1；高形状参数使用稳定 log1p 余项。 */
    private static double gamma(RandomSource random, double shape) {
        double d = shape - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x = random.nextGaussian(), base = 1 + c * x;
            if (base <= 0) continue;
            double v = base * base * base, u = random.nextDouble(), x2 = x * x;
            if (u < 1 - 0.0331 * x2 * x2 || Math.log(u) < 0.5 * x2 + d * log1pmx(v - 1)) return d * v;
        }
    }

    private static double log1pmx(double x) {
        if (Math.abs(x) >= 0.1) return Math.log1p(x) - x;
        double term = x * x, sum = -term / 2;
        for (int k = 3; k <= 18; k++) { term *= -x; sum -= term / k; }
        return sum;
    }
}
