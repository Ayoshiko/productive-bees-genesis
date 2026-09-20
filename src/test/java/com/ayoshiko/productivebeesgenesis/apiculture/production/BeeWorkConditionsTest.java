package com.ayoshiko.productivebeesgenesis.apiculture.production;

import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions.*;
import static org.junit.jupiter.api.Assertions.*;

class BeeWorkConditionsTest {
	@Test void snapshotsRemainIndependentForDifferentMemberEnvironments() {
		var bee = new Traits(Behavior.NOCTURNAL, WeatherTolerance.RAIN);
		assertEquals(BlockedBy.DAY_CYCLE, evaluate(bee, new Environment(false, false, false, false)));
		assertEquals(BlockedBy.NONE, evaluate(bee, new Environment(false, true, true, false)));
		assertEquals(BlockedBy.THUNDER, evaluate(bee, new Environment(false, true, true, true)));
		assertEquals(BlockedBy.DAY_CYCLE, evaluate(null, new Environment(false, true, false, false)));
	}
}
