package com.ayoshiko.productivebeesgenesis.apiary;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 枚举已知有限随机空间；每条分支按有理数计权，不进行频率抽样。 */
final class FiniteSamplingOracle {
    private FiniteSamplingOracle() {
    }

    record Mass(BigInteger numerator, BigInteger denominator) {
        static final Mass ZERO = of(0, 1);
        static final Mass ONE = of(1, 1);

        Mass {
            if (denominator.signum() <= 0) throw new IllegalArgumentException("Nonpositive denominator");
            BigInteger gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }

        static Mass of(long numerator, long denominator) {
            return new Mass(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }

        Mass add(Mass other) {
            return new Mass(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Mass multiply(Mass other) {
            return new Mass(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Mass pow(int exponent) {
            return new Mass(numerator.pow(exponent), denominator.pow(exponent));
        }

        double doubleValue() {
            return numerator.doubleValue() / denominator.doubleValue();
        }
    }

    record Branch(double value, Mass weight) {
    }

    private static final class NeedBranch extends RuntimeException {
        final List<Branch> branches;

        NeedBranch(List<Branch> branches) {
            super(null, null, false, false);
            this.branches = branches;
        }
    }

    static final class Decisions implements RandomGenerator {
        private final List<Branch> path;
        private int cursor;

        Decisions(List<Branch> path) {
            this.path = path;
        }

        double choose(List<Branch> branches) {
            if (cursor == path.size()) throw new NeedBranch(branches);
            Branch branch = path.get(cursor++);
            if (!branches.contains(branch)) throw new AssertionError("Random call order changed");
            return branch.value();
        }

        @Override
        public double nextDouble() {
            return choose(List.of(new Branch(0.25, Mass.of(1, 2)), new Branch(0.75, Mass.of(1, 2))));
        }

        @Override
        public float nextFloat() {
            // RandomSource.nextFloat 的 2^24 个等概率值，生产核心使用 <= 0.5。
            return (float) choose(List.of(new Branch(0.25, Mass.of(8_388_609, 16_777_216)),
                    new Branch(0.75, Mass.of(8_388_607, 16_777_216))));
        }

        @Override
        public long nextLong(long bound) {
            if (bound <= 0 || bound > 24) throw new AssertionError("Unbounded finite oracle");
            List<Branch> branches = new ArrayList<>();
            for (int i = 0; i < bound; i++) branches.add(new Branch(i, Mass.of(1, bound)));
            return (long) choose(branches);
        }

        @Override
        public int nextInt(int bound) {
            return (int) nextLong(bound);
        }

        @Override
        public long nextLong() {
            throw new AssertionError("Unexpected unbounded random draw");
        }

        @Override
        public double nextGaussian() {
            throw new AssertionError("Approximation entered an exact finite test");
        }

        void exhausted() {
            assertEquals(path.size(), cursor, "All supplied decisions must be consumed");
        }
    }

    static <T> Map<T, Mass> enumerate(Function<Decisions, T> sampler) {
        Map<T, Mass> result = new LinkedHashMap<>();
        visit(sampler, new ArrayList<>(), Mass.ONE, result);
        assertEquals(Mass.ONE, total(result));
        return result;
    }

    private static <T> void visit(Function<Decisions, T> sampler, List<Branch> path,
            Mass weight, Map<T, Mass> result) {
        if (path.size() > 24) throw new AssertionError("Finite scenario grew beyond its declared budget");
        Decisions decisions = new Decisions(path);
        try {
            T value = sampler.apply(decisions);
            decisions.exhausted();
            add(result, value, weight);
        } catch (NeedBranch next) {
            for (Branch branch : next.branches) {
                path.add(branch);
                visit(sampler, path, weight.multiply(branch.weight()), result);
                path.removeLast();
            }
        }
    }

    static <T> void add(Map<T, Mass> target, T value, Mass mass) {
        if (!mass.equals(Mass.ZERO)) target.merge(value, mass, Mass::add);
    }

    static Mass total(Map<?, Mass> distribution) {
        return distribution.values().stream().reduce(Mass.ZERO, Mass::add);
    }
}
