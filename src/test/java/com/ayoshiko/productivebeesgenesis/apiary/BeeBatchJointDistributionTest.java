package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.apiary.FiniteSamplingOracle.Mass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BeeBatchJointDistributionTest {
    private record Totals(long first, long second, long rolls) {
        Totals plus(Totals other) {
            return new Totals(first + other.first, second + other.second, rolls + other.rolls);
        }
    }

    @Test
    void sharedFractionalRoundsAndMixedGenesMatchTheCompleteJointMass() {
        var actual = FiniteSamplingOracle.enumerate(random -> {
            Totals total = new Totals(0, 0, 0);
            for (int gene : new int[]{1, 3}) {
                long rolls = BeeProduceBatchSampler.sampleRollCount(random, 1, 1.5F);
                long firstHits = ExactBinomialSampler.sample(random, rolls, 0.5);
                long first = BeeProduceBatchSampler.sampleGeneAdjustedSum(random, 1, 2, firstHits, gene);
                long secondHits = ExactBinomialSampler.sample(random, rolls, 0.5);
                long second = BeeProduceBatchSampler.sampleGeneAdjustedSum(random, 1, 1, secondHits, gene);
                total = total.plus(new Totals(first, second, rolls));
            }
            return total;
        });
        // 单轮由 PB 单栈公式给定的独立产物定义，随后对每只蜂的 1/2 轮混合做卷积。
        var expected = convolve(sourceMass(2, 5), sourceMass(4, 6));
        assertEquals(expected, actual);
        assertEquals(Mass.ONE, FiniteSamplingOracle.total(actual));
        assertEquals(Mass.of(1, 4), probabilityOfRolls(actual, 2));
        assertEquals(Mass.of(1, 2), probabilityOfRolls(actual, 3));
        assertEquals(Mass.of(1, 4), probabilityOfRolls(actual, 4));

        Mass firstMean = moment(actual, 1, 0);
        Mass secondMean = moment(actual, 0, 1);
        Mass jointMean = moment(actual, 1, 1);
        assertNotEquals(firstMean.multiply(secondMean), jointMean,
                "Shared fractional rounds retain cross-output correlation");
    }

    @Test
    void nonlinearPerStackRoundingPreservesEveryFiniteProbability() {
        var actual = FiniteSamplingOracle.enumerate(random ->
                BeeProduceBatchSampler.sampleGeneAdjustedSum(random, 2, 3, 4, 3));
        Map<Long, Mass> expected = new LinkedHashMap<>();
        int[] coefficients = {1, 4, 6, 4, 1};
        for (int largerStacks = 0; largerStacks <= 4; largerStacks++) {
            expected.put(24L + 4L * largerStacks, Mass.of(coefficients[largerStacks], 16));
        }
        assertEquals(expected, actual);
    }

    private static Map<Totals, Mass> sourceMass(int lower, int upper) {
        Map<Totals, Mass> oneRound = new LinkedHashMap<>();
        long[] firstValues = {0, lower, upper};
        Mass[] firstMass = {Mass.of(1, 2), Mass.of(1, 4), Mass.of(1, 4)};
        for (int first = 0; first < firstValues.length; first++) {
            for (int second = 0; second < 2; second++) {
                oneRound.put(new Totals(firstValues[first], (long) second * lower, 1),
                        firstMass[first].multiply(Mass.of(1, 2)));
            }
        }
        Map<Totals, Mass> source = new LinkedHashMap<>();
        oneRound.forEach((value, mass) -> FiniteSamplingOracle.add(source, value, mass.multiply(Mass.of(1, 2))));
        convolve(oneRound, oneRound).forEach((value, mass) ->
                FiniteSamplingOracle.add(source, value, mass.multiply(Mass.of(1, 2))));
        return source;
    }

    private static Map<Totals, Mass> convolve(Map<Totals, Mass> left, Map<Totals, Mass> right) {
        Map<Totals, Mass> result = new LinkedHashMap<>();
        left.forEach((a, pa) -> right.forEach((b, pb) ->
                FiniteSamplingOracle.add(result, a.plus(b), pa.multiply(pb))));
        return result;
    }

    private static Mass probabilityOfRolls(Map<Totals, Mass> mass, long rolls) {
        return mass.entrySet().stream().filter(entry -> entry.getKey().rolls() == rolls)
                .map(Map.Entry::getValue).reduce(Mass.ZERO, Mass::add);
    }

    private static Mass moment(Map<Totals, Mass> mass, int firstPower, int secondPower) {
        Mass result = Mass.ZERO;
        for (var entry : mass.entrySet()) {
            long value = (firstPower == 0 ? 1 : entry.getKey().first())
                    * (secondPower == 0 ? 1 : entry.getKey().second());
            result = result.add(entry.getValue().multiply(Mass.of(value, 1)));
        }
        return result;
    }
}
