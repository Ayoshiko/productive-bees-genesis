package com.ayoshiko.productivebeesgenesis.mek;

/**
 * Internal bridge for optional JDTE coalescing on centrifuge factories that inherit
 * a third-party Mekanism factory base. The interface itself has no optional-mod types.
 */
public interface IJdteCentrifugeFactory {

	/** Credits accelerated virtual ticks into the factory's shared tracker. */
	void productivebeesgenesis$accumulateAcceleratedTicks(int ticks);

	/** Flushes one complete, budgeted factory pass for the current real game tick. */
	void productivebeesgenesis$flushAcceleratedTicks();
}
