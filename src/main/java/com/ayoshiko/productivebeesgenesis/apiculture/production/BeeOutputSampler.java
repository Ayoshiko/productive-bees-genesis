package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;

/** 私有结果采样；整数轮数不截断，随机分布按固定段续采，预算大小不改变随机结果。 */
final class BeeOutputSampler {
	static final int RANDOM_ROLLS = 1_000_000;
	record Result(Map<ProductKey, ProductAmount> outputs, BeeCycleRandom random, long pending, BeeSamplingCursor cursor) { }
	static Result sample(BeeRecord bee, StaticBeePlan plan, long pending, int budget) {
		var outputs = new HashMap<>(bee.frozenOutputs()); var random = bee.random();
		if (plan.deterministic()) {
			long cycles = Math.min(pending, budget); var rolls = BeeProductionRollPlan.fromMultiplier(plan.productionMultiplier());
			long extra = 0;
			if (rolls.extraChance() > 0) {
				cycles = Math.min(cycles, BeeWorkExecutor.MAX_RANDOM_CYCLES_PER_STEP);
				for (int i = 0; i < cycles; i++) if (random.draw(i) < rolls.extraChance()) extra++;
			}
			var total = rolls.fixedRolls().multiply(cycles).add(ProductAmount.of(extra));
			for (var output : plan.outputs()) {
				if (output.chance() == 0) continue;
				var amount = output.basis() == BeeOutputPlan.Basis.CYCLE ? ProductAmount.of(cycles).multiply(output.minimum())
						: total.multiply(BeeProductionSampling.adjustStackCount(output.minimum(), plan.productivity()));
				add(outputs, output.output(), amount);
			}
			return new Result(outputs, random.advance(cycles), pending - cycles, null);
		}
		var cursor = bee.sampling();
		for (int step = 0; step < Math.min(budget, BeeWorkExecutor.MAX_RANDOM_CYCLES_PER_STEP) && pending > 0; step++) {
			if (cursor == null) cursor = new BeeSamplingCursor(BeeProductionRollPlan.fromMultiplier(plan.productionMultiplier()).sampleCycle(random.draw(0)), 0, ProductAmount.ZERO);
			var output = plan.outputs().get(cursor.output()); var remaining = cursor.rolls().subtract(cursor.consumed());
			ProductAmount amount, consumed;
			if (output.basis() == BeeOutputPlan.Basis.CYCLE) { amount = ProductAmount.of(output.minimum()); consumed = remaining; }
			else if (output.deterministic()) {
				amount = output.chance() == 0 ? ProductAmount.ZERO : remaining.multiply(BeeProductionSampling.adjustStackCount(output.minimum(), plan.productivity())); consumed = remaining;
			} else {
				int count = (int) Math.min(RANDOM_ROLLS, remaining.longSaturated()); consumed = ProductAmount.of(count);
				amount = ProductAmount.of(BeeProductionSampling.sampleOutput(random.outputStream(cursor.output(), cursor.consumed()), count,
						output.minimum(), output.maximum(), output.chance(), 0, plan.productivity()));
			}
			add(outputs, output.output(), amount);
			var used = cursor.consumed().add(consumed);
			if (used.equals(cursor.rolls())) {
				if (cursor.output() + 1 == plan.outputs().size()) { pending--; random = random.advance(1); cursor = null; }
				else cursor = new BeeSamplingCursor(cursor.rolls(), cursor.output() + 1, ProductAmount.ZERO);
			} else cursor = new BeeSamplingCursor(cursor.rolls(), cursor.output(), used);
		}
		return new Result(outputs, random, pending, cursor);
	}
	private static void add(Map<ProductKey, ProductAmount> output, ProductKey key, ProductAmount amount) { if (!amount.isZero()) output.merge(key, amount, ProductAmount::add); }
	private BeeOutputSampler() { }
}
