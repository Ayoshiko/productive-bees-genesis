package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.Objects;

/** 工作基因与环境快照的纯判定；世界和 NBT 的读取由适配器负责。 */
public final class BeeWorkConditions {

	public enum Behavior { DIURNAL, NOCTURNAL, METATURNAL }
	public enum WeatherTolerance { NONE, RAIN, ANY }
	public enum BlockedBy { NONE, DAY_CYCLE, RAIN, THUNDER }
	public record Traits(Behavior behavior, WeatherTolerance weatherTolerance) {
		public static final Traits DEFAULT = new Traits(Behavior.DIURNAL, WeatherTolerance.NONE);
		public Traits { Objects.requireNonNull(behavior); Objects.requireNonNull(weatherTolerance); }
	}
	public record Environment(boolean fixedTime, boolean night, boolean raining, boolean thundering) { }

	public static BlockedBy evaluate(Traits traits, Environment environment) {
		Objects.requireNonNull(environment);
		if (environment.fixedTime()) return BlockedBy.NONE;
		var safe = traits == null ? Traits.DEFAULT : traits;
		if (environment.night() && safe.behavior() == Behavior.DIURNAL
				|| !environment.night() && safe.behavior() == Behavior.NOCTURNAL) return BlockedBy.DAY_CYCLE;
		if (environment.thundering() && safe.weatherTolerance() != WeatherTolerance.ANY) return BlockedBy.THUNDER;
		if (environment.raining() && safe.weatherTolerance() == WeatherTolerance.NONE) return BlockedBy.RAIN;
		return BlockedBy.NONE;
	}

	private BeeWorkConditions() { }
}
