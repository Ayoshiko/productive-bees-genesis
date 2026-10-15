package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;

/** 普通一轮夹具的独立逐项 oracle；不调用正式批量采样器。 */
public final class BeeOutputProbeOracle {
	public static Map<ProductKey, ProductAmount> produced(BeeRecord before, BeeRecord after) {
		var plan = before.plan(); var result = new HashMap<ProductKey, ProductAmount>();
		if (plan.productionMultiplier() != 1 || plan.productivity() != 0) throw new IllegalArgumentException("Unexpected probe multiplier");
		for (long cycle = before.random().cursor(); cycle <= after.random().cursor(); cycle++) {
			int start = cycle == before.random().cursor() && before.sampling() != null ? before.sampling().output() : 0;
			int end = cycle < after.random().cursor() ? plan.outputs().size() : after.sampling() == null ? 0 : after.sampling().output();
			for (int index = start; index < end; index++) {
				var output = plan.outputs().get(index); int amount;
				if (output.basis() == BeeOutputPlan.Basis.CYCLE) amount = output.minimum();
				else {
					if (output.minimum() != 1 || output.maximum() != 1) throw new IllegalArgumentException("Unexpected probe count");
					amount = output.chance() == 1 || new BeeCycleRandom(before.random().seed(), cycle).outputStream(index, ProductAmount.ZERO).nextFloat() < output.chance() ? 1 : 0;
				}
				if (amount > 0) result.merge(output.output(), ProductAmount.of(amount), ProductAmount::add);
			}
		}
		return result;
	}
	private BeeOutputProbeOracle() { }
}
