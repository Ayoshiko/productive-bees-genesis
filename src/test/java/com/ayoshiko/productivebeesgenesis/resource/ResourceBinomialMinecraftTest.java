package com.ayoshiko.productivebeesgenesis.resource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceBinomialMinecraftTest {

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

}
