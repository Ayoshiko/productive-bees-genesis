package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 一个固定并行周期。输入已从公共余额移入本记录，结果结算前不可再次使用。 */
public record CentrifugeJob(UUID id, CentrifugeRecipePlan plan, int operations,
		int progress, long seed, Map<ProductKey, ProductAmount> frozen) {
	public record Advancement(CentrifugeJob job, int executedTicks, long energyUsed) { }
	public CentrifugeJob {
		Objects.requireNonNull(id); Objects.requireNonNull(plan);
		plan.energyPerTick(operations);
		if (progress < 0 || progress > plan.cycleTicks()) throw new IllegalArgumentException("Invalid paid centrifuge progress");
		if (frozen != null) {
			frozen = Map.copyOf(frozen);
			if (progress != plan.cycleTicks()) throw new IllegalArgumentException("Unpaid frozen centrifuge output");
			plan.validateFrozen(operations, frozen);
		}
	}
	public boolean paid() { return progress == plan.cycleTicks(); }
	public boolean sampled() { return frozen != null; }
	public ProductAmount heldInputs() { return ProductAmount.of(operations); }
	public Advancement advance(int ticks, long energy) {
		if (ticks < 0 || energy < 0) throw new IllegalArgumentException("Negative centrifuge budget");
		if (paid() || ticks == 0) return new Advancement(this, 0, 0);
		long perTick = plan.energyPerTick(operations);
		int funded = Math.min(ticks, plan.cycleTicks() - progress);
		if (perTick > 0) funded = (int) Math.min(funded, energy / perTick);
		if (funded == 0) return new Advancement(this, 0, 0);
		var result = PbVirtualTickPlan.create(progress, funded, plan.cycleTicks(), operations,
				operations, plan.unitEnergyPerTick(), energy);
		if (result.executedTicks() != funded || result.completedOperations() != (progress + funded == plan.cycleTicks() ? operations : 0))
			throw new IllegalStateException("Pinned centrifuge cycle diverged from shared kernel");
		return new Advancement(new CentrifugeJob(id, plan, operations, progress + funded, seed, null), funded, result.energyUsed());
	}
	public CentrifugeJob freeze() {
		if (!paid()) throw new IllegalStateException("Cannot sample unpaid centrifuge work");
		return sampled() ? this : new CentrifugeJob(id, plan, operations, progress, seed, plan.sample(operations, seed));
	}
}
