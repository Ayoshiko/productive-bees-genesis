package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/** Overflow-safe boundaries shared by AE energy extraction and machine injection. */
final class Ae2EnergyMath {

	private Ae2EnergyMath() {
	}

	static long remainingCapacity(long currentEnergy, long maxEnergy) {
		if (maxEnergy <= 0L) return 0L;
		long current = Math.max(0L, currentEnergy);
		return current >= maxEnergy ? 0L : maxEnergy - current;
	}

	static long clampExtracted(long extracted, long requested) {
		if (extracted <= 0L || requested <= 0L) return 0L;
		return Math.min(extracted, requested);
	}

	/** Returns the bounded FE shortfall needed to reach an active batch budget. */
	static long requiredShortfall(long currentEnergy, long requiredEnergy, long remainingCapacity) {
		long current = Math.max(0L, currentEnergy);
		long required = Math.max(0L, requiredEnergy);
		long remaining = Math.max(0L, remainingCapacity);
		if (current >= required || remaining == 0L) return 0L;
		return Math.min(remaining, required - current);
	}

	static long aeToFe(double extractedAe, long requestedFe, double ratio) {
		if (extractedAe <= 0D || requestedFe <= 0L || ratio <= 0D || Double.isNaN(extractedAe)) return 0L;
		double extractedFe = extractedAe * ratio;
		if (Double.isNaN(extractedFe) || extractedFe <= 0D) return 0L;
		if (Double.isInfinite(extractedFe) || extractedFe >= requestedFe) return requestedFe;
		return Math.min(requestedFe, Math.max(0L, (long) extractedFe));
	}

	static InjectionResult apply(long currentEnergy, long maxEnergy, long firstExtracted, long secondExtracted) {
		long current = Math.max(0L, currentEnergy);
		long remaining = remainingCapacity(current, maxEnergy);
		if (remaining <= 0L) return new InjectionResult(Math.min(current, Math.max(0L, maxEnergy)), 0L);

		long first = clampExtracted(firstExtracted, remaining);
		long second = clampExtracted(secondExtracted, remaining - first);
		long injected = Math.min(remaining, SaturatingMath.saturatingAdd(first, second));
		long newEnergy = Math.min(maxEnergy, SaturatingMath.saturatingAdd(current, injected));
		return new InjectionResult(newEnergy, Math.max(0L, newEnergy - current));
	}

	record InjectionResult(long energy, long injected) {
	}
}
