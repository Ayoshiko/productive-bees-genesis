package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.atomic.AtomicLong;

/**
	 * Allows work to run once for each real level game tick.
	 * Time accelerators may invoke a block entity hundreds of times while {@code gameTime} is unchanged.
	 */
public final class GameTickGate {

	private final AtomicLong lastGameTick = new AtomicLong(Long.MIN_VALUE);

	/**
	 * @return {@code true} only for the first caller observing {@code gameTick}
	 */
	public boolean tryEnter(long gameTick) {
		long observed = lastGameTick.get();
		while (observed != gameTick) {
			if (lastGameTick.compareAndSet(observed, gameTick)) {
				return true;
			}
			observed = lastGameTick.get();
		}
		return false;
	}

	public void reset() {
		lastGameTick.set(Long.MIN_VALUE);
	}
}
