package com.ayoshiko.productivebeesgenesis.mek;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.DoubleToIntFunction;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchDistributionContractTest {
    @Test
    void exactBinomialBranchesMatchEveryConvolvedMass() {
        for (int n : new int[]{2, 7, 30}) {
            for (double chance : new double[]{0.125, 0.5, 0.875}) {
                double[] expected = bernoulliConvolution(n, chance);
                double[] actual = quantileMass(n,
                        u -> BatchProbabilitySampler.sampleBinomial(new Uniform(u), n, chance));
                assertArrayEquals(expected, actual, 3.0E-14);
                assertEquals(1.0, Arrays.stream(actual).sum(), 2.0E-15);
            }
        }
    }

    @Test
    void guaranteedOutputsKeepTheWholeJointDistributionRatherThanIndependentBernoulli() {
        int n = 7;
        double[] first = guaranteedMass(n, 0.25);
        double[] second = guaranteedMass(n, 0.75);
        double[] expectedFirst = shifted(bernoulliConvolution(6, 0.75 / 6), 1, n + 1);
        double[] expectedSecond = shifted(bernoulliConvolution(2, 0.25 / 2), 5, n + 1);
        double total = 0;
        for (int a = 0; a <= n; a++) {
            for (int b = 0; b <= n; b++) {
                double actualJoint = first[a] * second[b];
                assertEquals(expectedFirst[a] * expectedSecond[b], actualJoint, 3.0E-14);
                total += actualJoint;
            }
        }
        assertEquals(1.0, total, 2.0E-14);
        assertEquals(0.0, first[0]);
        assertEquals(0.0, second[4]);
    }

    @Test
    void truncatedPoissonKeepsEveryStoppingBucketIncludingNMinusOne() {
        int n = 31;
        double lambda = 4.0;
        double[] expected = new double[n + 1];
        double[] actual = new double[n + 1];
        double mass = Math.exp(-lambda);
        for (int hits = 0; hits < n; hits++) {
            expected[hits] = mass;
            // Knuth 第 hits+1 次越过阈值；每个停止次数代表整个等价随机区域。
            int result = BatchProbabilitySampler.sampleBinomial(new StopAt(hits + 1), n, lambda / n);
            actual[result] += mass;
            mass *= lambda / (hits + 1.0);
        }
        double tail = 0;
        for (int hits = n; hits < n + 64; hits++) {
            tail += mass;
            mass *= lambda / (hits + 1.0);
        }
        expected[n] = tail;
        actual[BatchProbabilitySampler.sampleBinomial(new StopAt(n + 1), n, lambda / n)] += tail;
        assertArrayEquals(expected, actual, 0.0);
        assertTrue(actual[n - 1] > 0, "The n-1 bucket must not be folded into n");
        assertEquals(1.0, Arrays.stream(actual).sum(), 2.0E-15);
    }

    @Test
    void normalApproximationKeepsItsCompleteClampedMassAtTheBranchBoundary() {
        for (double chance : new double[]{5.0 / 31, 0.5}) {
            int n = 31;
            double mean = n * chance;
            double sigma = Math.sqrt(n * chance * (1 - chance));
            double[] actual = normalMass(0, n,
                    z -> BatchProbabilitySampler.sampleBinomial(new Gaussian(z), n, chance));
            double[] expected = normalRoundedMass(0, n, mean, sigma);
            assertArrayEquals(expected, actual, 2.0E-12);
        }
    }

    @Test
    void uniformSumApproximationIsMeasuredAgainstAllExactOutcomes() {
        double[] actual = normalMass(4, 6,
                z -> (int) SampleUniformSum.sample(new Gaussian(z), 2, 3, 2, 1));
        double[] expectedNormal = normalRoundedMass(4, 6, 5, Math.sqrt(0.5));
        assertArrayEquals(expectedNormal, actual, 2.0E-12);
        // 两个独立 {2,3} 之和的精确分布是 {1/4,1/2,1/4}，旧 CLT 合同并不与它相同。
        double[] exact = {0.25, 0.5, 0.25};
        double variation = 0;
        for (int i = 0; i < exact.length; i++) variation += Math.abs(actual[i] - exact[i]) / 2;
        assertEquals(0.0204998778, variation, 2.0E-7);
    }

    private static double[] guaranteedMass(int n, double chance) {
        return quantileMass(n,
                u -> (int) BatchProbabilitySampler.sampleBinomialWithGuarantee(new Uniform(u), n, chance));
    }

    private static double[] bernoulliConvolution(int n, double chance) {
        double[] mass = new double[n + 1];
        mass[0] = 1;
        for (int event = 0; event < n; event++) {
            double[] next = new double[n + 1];
            for (int hits = 0; hits <= event; hits++) {
                next[hits] += mass[hits] * (1 - chance);
                next[hits + 1] += mass[hits] * chance;
            }
            mass = next;
        }
        return mass;
    }

    private static double[] shifted(double[] source, int offset, int size) {
        double[] result = new double[size];
        System.arraycopy(source, 0, result, offset, source.length);
        return result;
    }

    /** 二分实际单调采样函数的全部阈值，不从实现的 CDF 递推重新生成期望值。 */
    private static double[] quantileMass(int maximum, DoubleToIntFunction sample) {
        double[] result = new double[maximum + 1];
        double previous = 0;
        int minimum = sample.applyAsInt(0.0);
        int reachableMaximum = sample.applyAsInt(Math.nextDown(1.0));
        for (int output = 0; output < maximum; output++) {
            if (output < minimum) {
                result[output] = 0;
                continue;
            }
            if (output >= reachableMaximum) {
                result[output] = 1 - previous;
                previous = 1;
                continue;
            }
            double low = 0;
            double high = 1;
            for (int iteration = 0; iteration < 60; iteration++) {
                double midpoint = (low + high) / 2;
                if (sample.applyAsInt(midpoint) <= output) low = midpoint;
                else high = midpoint;
            }
            double threshold = (low + high) / 2;
            result[output] = threshold - previous;
            previous = threshold;
        }
        result[maximum] = 1 - previous;
        return result;
    }

    private static double[] normalMass(int minimum, int maximum, DoubleToIntFunction sample) {
        double[] mass = new double[maximum - minimum + 1];
        double previous = 0;
        for (int output = minimum; output < maximum; output++) {
            double low = -40;
            double high = 40;
            for (int iteration = 0; iteration < 60; iteration++) {
                double midpoint = (low + high) / 2;
                if (sample.applyAsInt(midpoint) <= output) low = midpoint;
                else high = midpoint;
            }
            double cumulative = normalCdf((low + high) / 2);
            mass[output - minimum] = cumulative - previous;
            previous = cumulative;
        }
        mass[maximum - minimum] = 1 - previous;
        return mass;
    }

    private static double[] normalRoundedMass(int minimum, int maximum, double mean, double sigma) {
        double[] mass = new double[maximum - minimum + 1];
        double previous = 0;
        for (int output = minimum; output < maximum; output++) {
            double cumulative = normalCdf((output + 0.5 - mean) / sigma);
            mass[output - minimum] = cumulative - previous;
            previous = cumulative;
        }
        mass[maximum - minimum] = 1 - previous;
        return mass;
    }

    private static double normalCdf(double z) {
        double absolute = Math.abs(z);
        if (absolute < 1.0E-14) return 0.5;
        double t = 1 / (1 + 0.2316419 * absolute);
        double polynomial = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        double tail = Math.exp(-absolute * absolute / 2) / Math.sqrt(2 * Math.PI) * polynomial;
        return z >= 0 ? 1 - tail : tail;
    }

    private record Uniform(double value) implements RandomGenerator {
        @Override
        public double nextDouble() {
            return value;
        }

        @Override
        public long nextLong() {
            throw new AssertionError("Unexpected random method");
        }
    }

    private record Gaussian(double value) implements RandomGenerator {
        @Override
        public double nextGaussian() {
            return value;
        }

        @Override
        public long nextLong() {
            throw new AssertionError("Unexpected random method");
        }
    }

    private static final class StopAt implements RandomGenerator {
        private final int crossingDraw;
        private int calls;

        private StopAt(int crossingDraw) {
            this.crossingDraw = crossingDraw;
        }

        @Override
        public double nextDouble() {
            calls++;
            return calls < crossingDraw ? 0.999 : 0.001;
        }

        @Override
        public long nextLong() {
            throw new AssertionError("Unexpected random method");
        }
    }
}
