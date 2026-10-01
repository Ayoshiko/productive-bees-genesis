package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.Objects;
import java.util.UUID;

/** 持久化采样版本 1：SplitMix64 计数流；无可变 RNG，也不使用世界随机状态。 */
public record BeeCycleRandom(long seed, long cursor) {
	private static final long STEP = 0x9e3779b97f4a7c15L;
	public BeeCycleRandom {
		if (cursor < 0) throw new IllegalArgumentException("Negative bee sample cursor");
	}
	public static BeeCycleRandom initial(UUID bee) {
		Objects.requireNonNull(bee);
		return new BeeCycleRandom(mix(bee.getMostSignificantBits() ^ Long.rotateLeft(bee.getLeastSignificantBits(), 32)), 0);
	}
	public double draw(int offset) {
		if (offset < 0) throw new IllegalArgumentException("Negative bee sample offset");
		long cycle = Math.addExact(cursor, offset);
		// 流混合中的模 2^64 运算有意回绕，持久化序号本身禁止回绕。
		return (mix(seed + STEP * (cycle + 1)) >>> 11) * 0x1.0p-53;
	}
	public BeeCycleRandom advance(long count) {
		if (count < 0) throw new IllegalArgumentException("Negative sampled cycles");
		return count == 0 ? this : new BeeCycleRandom(seed, Math.addExact(cursor, count));
	}
	private static long mix(long value) {
		value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
		value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
		return value ^ (value >>> 31);
	}
}
