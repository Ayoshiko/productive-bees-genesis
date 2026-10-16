package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.mek.WeightedAllocation;
import java.util.HashMap;
import java.util.Map;

/** 从已保存的单事件候选池续算；一个池内升级轮次相关，预算划分不重新调用世界战利品表。 */
final class GeneratedBeeSampler {
	static final int CHOICE_ROLLS_PER_STEP = 4096;
	static BeeRecord sample(BeeRecord bee, int budget) {
		var generation = bee.generation();
		if (generation == null || generation.phase() != BeeGeneration.Phase.READY || budget == 0) return bee;
		var plan = bee.plan();
		if (!generation.fixedDone()) {
			var fixed = BeeOutputSampler.sample(bee, plan, 1, budget);
			return copy(bee, fixed.outputs(), fixed.cursor(), generation.progress(fixed.pending() == 0, ProductAmount.ZERO), false);
		}
		var outputs = new HashMap<>(bee.frozenOutputs());
		var rolls = rolls(plan, bee.random());
		var remaining = rolls.subtract(generation.consumed());
		var pool = generation.pool();
		ProductAmount consumed;
		if (plan.generator() == BeeGenerator.MYRIAD) {
			if (!pool.isEmpty()) {
				var weights = pool.stream().mapToDouble(BeeGeneration.Drop::weight).toArray();
				var allocated = WeightedAllocation.allocateExact(remaining.exact(), weights);
				for (int index = 0; index < pool.size(); index++) {
					var amount = ProductAmount.of(allocated[index]);
					if (!amount.isZero()) outputs.merge(pool.get(index).key(), amount, ProductAmount::add);
				}
			}
			consumed = rolls;
		} else if (plan.generator() == BeeGenerator.MULTI_FLOWER || pool.size() <= 1) {
			for (var drop : pool) add(outputs, drop, remaining, plan.productivity());
			consumed = rolls;
		} else {
			int count = (int) Math.min(CHOICE_ROLLS_PER_STEP, remaining.longSaturated());
			var random = bee.random().outputStream(Integer.MAX_VALUE, generation.consumed());
			var selected = new HashMap<Integer, Integer>();
			for (int draw = 0; draw < count; draw++) selected.merge(random.nextInt(pool.size()), 1, Integer::sum);
			for (var entry : selected.entrySet()) add(outputs, pool.get(entry.getKey()), ProductAmount.of(entry.getValue()), plan.productivity());
			consumed = generation.consumed().add(ProductAmount.of(count));
		}
		return copy(bee, outputs, null, generation.progress(true, consumed), consumed.equals(rolls));
	}
	static ProductAmount rolls(StaticBeePlan plan, BeeCycleRandom random) {
		if (plan.generator() == BeeGenerator.MYRIAD) {
			var exact = new java.math.BigDecimal((double) plan.productionMultiplier())
					.multiply(java.math.BigDecimal.valueOf(BeeProductionSampling.adjustStackCount(1, plan.productivity())));
			var amount = ProductAmount.of(exact.toBigInteger());
			if (plan.generatedCombBlock()) amount = amount.multiply(4);
			return amount;
		}
		return BeeProductionRollPlan.fromMultiplier(plan.productionMultiplier()).sampleCycle(random.draw(0));
	}
	private static void add(Map<ProductKey, ProductAmount> outputs, BeeGeneration.Drop drop, ProductAmount rolls, int productivity) {
		var amount = rolls.multiply(BeeProductionSampling.adjustStackCount(drop.count(), productivity));
		if (!amount.isZero()) outputs.merge(drop.key(), amount, ProductAmount::add);
	}
	private static BeeRecord copy(BeeRecord bee, Map<ProductKey, ProductAmount> outputs, BeeSamplingCursor sampling,
			BeeGeneration generation, boolean complete) {
		return new BeeRecord(bee.id(), bee.member(), bee.slot(), bee.originalSlot(), bee.plan(), Math.incrementExact(bee.revision()),
				bee.progress(), complete ? 0 : 1, outputs, complete ? bee.random().advance(1) : bee.random(), bee.enabled(), sampling,
				complete && outputs.isEmpty() ? null : generation);
	}
	private GeneratedBeeSampler() { }
}
