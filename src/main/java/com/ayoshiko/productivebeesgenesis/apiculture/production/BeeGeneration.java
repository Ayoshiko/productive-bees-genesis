package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.List;
import java.util.Objects;

/** 已付款事件的生成凭据；STARTED 在恢复时保持隔离，不推断外部调用是否完成。 */
public record BeeGeneration(AssetImage source, Phase phase, List<Drop> pool,
		boolean fixedDone, ProductAmount consumed, String failure) {
	public enum Phase { STARTED, READY, UNKNOWN }
	public record Drop(ProductKey key, int count) {
		public Drop {
			Objects.requireNonNull(key);
			if (count < 1) throw new IllegalArgumentException("Nonpositive generated bee drop");
		}
	}
	public BeeGeneration {
		Objects.requireNonNull(source);
		Objects.requireNonNull(phase);
		Objects.requireNonNull(consumed);
		Objects.requireNonNull(failure);
		pool = List.copyOf(pool);
		if (source.isEmpty() || failure.length() > 256
				|| phase != Phase.READY && (!pool.isEmpty() || fixedDone || !consumed.isZero())
				|| phase == Phase.UNKNOWN && failure.isBlank()
				|| phase != Phase.UNKNOWN && !failure.isEmpty()) {
			throw new IllegalArgumentException("Invalid bee generation state");
		}
	}
	public static BeeGeneration started(AssetImage source) {
		return new BeeGeneration(source, Phase.STARTED, List.of(), false, ProductAmount.ZERO, "");
	}
	public BeeGeneration ready(List<Drop> drops) {
		if (phase != Phase.STARTED) throw new IllegalStateException("Bee generation already resolved");
		return new BeeGeneration(source, Phase.READY, drops, false, ProductAmount.ZERO, "");
	}
	public BeeGeneration unknown(String reason) {
		if (phase != Phase.STARTED) throw new IllegalStateException("Bee generation already resolved");
		return new BeeGeneration(source, Phase.UNKNOWN, List.of(), false, ProductAmount.ZERO,
				reason.substring(0, Math.min(reason.length(), 256)));
	}
	public BeeGeneration progress(boolean fixed, ProductAmount nextConsumed) {
		if (phase != Phase.READY) throw new IllegalStateException("Bee generation has no frozen pool");
		return new BeeGeneration(source, phase, pool, fixed, nextConsumed, "");
	}
}
