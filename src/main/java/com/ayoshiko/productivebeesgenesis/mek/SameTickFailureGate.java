package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.atomic.AtomicLong;

/**
	 * Suppresses repeated failed work while both the real game tick and observed content version are unchanged.
	 */
public final class SameTickFailureGate {

	private final AtomicLong failedContentsVersion = new AtomicLong(Long.MIN_VALUE);
	private final AtomicLong failedGameTick = new AtomicLong(Long.MIN_VALUE);

	/**
	 * @return {@code true} when the same state already failed during this real game tick
	 */
	public boolean shouldSkip(long gameTick, long contentsVersion) {
		return failedGameTick.get() == gameTick && failedContentsVersion.get() == contentsVersion;
	}

	/** Records a failed attempt. A content change or a new real tick automatically permits another attempt. */
	public void recordFailure(long gameTick, long contentsVersion) {
		failedContentsVersion.set(contentsVersion);
		failedGameTick.set(gameTick);
	}

	/** Clears the last failure after successful work. */
	public void clear() {
		failedGameTick.set(Long.MIN_VALUE);
	}
}
