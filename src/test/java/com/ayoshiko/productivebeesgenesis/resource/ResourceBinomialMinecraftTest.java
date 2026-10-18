package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceBinomialMinecraftTest {
    @Test void distributionMatchesBinomialMassIncludingTails() {
        int n = 80, trials = 200000;
        for (double p : new double[]{0.02, 0.25, 0.5, 0.97}) {
            int[] observed = new int[n + 1];
            var random = RandomSource.create(173);
            for (int i = 0; i < trials; i++) observed[(int) ExactBinomialSampler.sample(random, n, p)]++;
            double mass = Math.pow(1 - p, n), chi = 0, expectedBin = 0, actualBin = 0;
            int bins = 0;
            for (int k = 0; k <= n; k++) {
                expectedBin += mass * trials; actualBin += observed[k];
                if (expectedBin >= 20) { chi += Math.pow(actualBin - expectedBin, 2) / expectedBin; bins++; expectedBin = 0; actualBin = 0; }
                mass *= (double) (n - k) / (k + 1) * p / (1 - p);
            }
            assertTrue(chi < bins + 8 * Math.sqrt(2 * bins), "binomial distribution p=" + p + ", chi=" + chi);
        }
    }

    @Test void sharedFractionalRollsPreserveCrossOutputCorrelationAndBounds() {
        var outputs = List.of(new ResourceProductionRecipe.Output(ResourceKey.energy(), 1, 1),
                new ResourceProductionRecipe.Output(ResourceKey.source(), 1, 1));
        for (int seed = 0; seed < 1000; seed++) {
            var plan = new ResourceRollPlan(outputs, 1.5, 0, seed);
            var sampled = plan.sampleWithRolls(1024);
            assertEquals(sampled.amounts().get(ResourceKey.energy()), sampled.amounts().get(ResourceKey.source()));
            assertEquals(sampled.rolls(), sampled.amounts().get(ResourceKey.energy()));
            assertTrue(sampled.rolls() >= 1024 && sampled.rolls() <= 2048);
            assertEquals(sampled, plan.sampleWithRolls(1024));
        }
    }

    @Test void probabilisticOutputsKeepCovarianceFromTheSharedRoundCount() {
        var outputs = List.of(new ResourceProductionRecipe.Output(ResourceKey.energy(), 1, .3),
                new ResourceProductionRecipe.Output(ResourceKey.source(), 1, .7));
        var seeds = RandomSource.create(612);
        int samples = 100000;
        double x = 0, y = 0, xy = 0;
        for (int i = 0; i < samples; i++) {
            var result = new ResourceRollPlan(outputs, 1.5, 0, seeds.nextLong()).sample(64);
            double a = result.getOrDefault(ResourceKey.energy(), 0L), b = result.getOrDefault(ResourceKey.source(), 0L);
            x += a; y += b; xy += a * b;
        }
        assertEquals(28.8, x / samples, .15);
        assertEquals(67.2, y / samples, .15);
        assertEquals(3.36, xy / samples - x / samples * y / samples, .65);
    }

    @Test void longCountsAndDeterministicEdgesNeverOverflowOrTruncate() {
        var random = RandomSource.create(4);
        assertEquals(0, ExactBinomialSampler.sample(random, Long.MAX_VALUE, 0));
        assertEquals(Long.MAX_VALUE, ExactBinomialSampler.sample(random, Long.MAX_VALUE, 1));
        long n = (1L << 41) + 123;
        long sampled = ExactBinomialSampler.sample(random, n, .5);
        assertTrue(Math.abs(sampled - n / 2) < 8 * Math.sqrt(n * .25));
    }
}
