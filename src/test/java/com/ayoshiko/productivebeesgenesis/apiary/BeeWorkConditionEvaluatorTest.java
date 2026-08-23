package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BeeWorkConditionEvaluatorTest {

	@Test
	void diurnalAndNocturnalBeesObeyOppositeDayCycles() {
		var diurnal = traits("behavior.diurnal", "weather_tolerance.any");
		var nocturnal = traits("behavior.nocturnal", "weather_tolerance.any");
		var metaturnal = traits("behavior.metaturnal", "weather_tolerance.any");

		assertNull(blocking(diurnal, false, false, false));
		assertEquals(BeeState.WAITING_DAY_CYCLE, blocking(diurnal, true, false, false));
		assertEquals(BeeState.WAITING_DAY_CYCLE, blocking(nocturnal, false, false, false));
		assertNull(blocking(nocturnal, true, false, false));
		assertNull(blocking(metaturnal, false, false, false));
		assertNull(blocking(metaturnal, true, false, false));
	}

	@Test
	void weatherToleranceDistinguishesRainFromThunder() {
		var none = traits("behavior.metaturnal", "weather_tolerance.none");
		var rain = traits("behavior.metaturnal", "weather_tolerance.rain");
		var any = traits("behavior.metaturnal", "weather_tolerance.any");

		assertEquals(BeeState.WAITING_RAIN, blocking(none, false, true, false));
		assertEquals(BeeState.WAITING_THUNDER, blocking(none, false, true, true));
		assertNull(blocking(rain, false, true, false));
		assertEquals(BeeState.WAITING_THUNDER, blocking(rain, false, true, true));
		assertNull(blocking(any, false, true, true));
	}

	@Test
	void fixedTimeDimensionsIgnoreDayAndWeatherRestrictions() {
		var restrictive = traits("behavior.diurnal", "weather_tolerance.none");

		assertNull(BeeWorkConditionEvaluator.blockingState(
				restrictive, true, true, true, true));
	}

	@Test
	void missingOrMalformedAttributeValuesUseProductiveBeesDefaults() {
		var missing = traits("", "");
		var malformed = traits("unknown", "unknown");

		assertEquals(BeeState.WAITING_DAY_CYCLE, blocking(missing, true, false, false));
		assertEquals(BeeState.WAITING_RAIN, blocking(malformed, false, true, false));
	}

	private static BeeWorkConditionEvaluator.WorkTraits traits(String behavior, String weatherTolerance) {
		return BeeWorkConditionEvaluator.readTraits(behavior, weatherTolerance);
	}

	private static BeeState blocking(
			BeeWorkConditionEvaluator.WorkTraits traits,
			boolean night,
			boolean raining,
			boolean thundering) {
		return BeeWorkConditionEvaluator.blockingState(
				traits, false, night, raining, thundering);
	}
}
