package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeJob;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** 原冻结样本保留作校验，delivered 只记已交付量；权威待输出量为 frozen - delivered。 */
public record CentrifugeDelivery(CentrifugeJob job, Map<ProductKey, ProductAmount> delivered) {
	public record Delivery(CentrifugeDelivery work, FiniteProductBuffer buffer, long moved) {}
	public CentrifugeDelivery {
		Objects.requireNonNull(job); delivered = Map.copyOf(delivered);
		for (var entry : delivered.entrySet()) if (!job.sampled() || entry.getValue().isZero()
				|| entry.getValue().compareTo(job.frozen().getOrDefault(entry.getKey(), ProductAmount.ZERO)) > 0)
			throw new IllegalArgumentException("Unpaid or excessive delivery");
	}
	public boolean complete() { return job.sampled() && job.frozen().equals(delivered); }
	public ProductAmount remaining(ProductKey key) {
		return job.sampled() ? job.frozen().getOrDefault(key, ProductAmount.ZERO).subtract(delivered.getOrDefault(key, ProductAmount.ZERO)) : ProductAmount.ZERO;
	}
	public Delivery deliver(FiniteProductBuffer buffer, ToIntFunction<ProductKey> itemLimits) {
		Objects.requireNonNull(buffer); Objects.requireNonNull(itemLimits);
		if (!job.sampled()) return new Delivery(this, buffer, 0);
		var next = new HashMap<>(delivered); var seen = new HashSet<ProductKey>(); long moved = 0;
		// 使用冻结计划的产物次序，不依赖 HashMap 枚举或重新抽样。
		for (var output : job.plan().outputs()) {
			var key = output.key(); if (!seen.add(key) || remaining(key).isZero()) continue;
			int limit = key.kind() == ProductKey.Kind.ITEM ? itemLimits.applyAsInt(key) : 1;
			var accepted = buffer.insert(key, remaining(key).longSaturated(), limit);
			if (accepted.moved() == 0) continue;
			buffer = accepted.buffer(); moved = Math.addExact(moved, accepted.moved());
			next.merge(key, ProductAmount.of(accepted.moved()), ProductAmount::add);
		}
		return new Delivery(moved == 0 ? this : new CentrifugeDelivery(job, next), buffer, moved);
	}
}
