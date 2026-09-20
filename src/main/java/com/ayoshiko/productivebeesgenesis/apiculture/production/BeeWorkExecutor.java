package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;

/** 私有候选计算；只有外层发布整个 checkpoint 才产生付款或生产。 */
public final class BeeWorkExecutor {
	public enum Status { READY, STALE_PLAN, UNLOADED, DISABLED, FLOWER, ENVIRONMENT, ENERGY, DRAIN_FIRST, BUDGET }
	public record Context(boolean loaded, boolean enabled, boolean flower, long recipeRevision, long capabilityRevision,
			BeeWorkConditions.Environment environment) { }
	public record Result(Status status, BeeMemberState candidate) { }
	public static Result advance(BeeMemberState state, int slot, long expectedRevision, Context context, int ticks, int samplingBudget) {
		var bee = state.bee(slot); var plan = bee.plan();
		if (ticks < 0 || samplingBudget < 0) throw new IllegalArgumentException("Negative work budget");
		if (bee.revision() != expectedRevision) return new Result(Status.STALE_PLAN, state);
		if (!context.loaded()) return new Result(Status.UNLOADED, state);
		// 已付费积压使用保存的配方，不因重载或天气变化再次收费或丢弃。
		if (ticks == 0 && bee.pendingCycles() > 0) return sample(state, bee, bee.progress(), bee.pendingCycles(), state.energy(), samplingBudget);
		if (!bee.drained()) return new Result(Status.DRAIN_FIRST, state);
		if (plan.recipeRevision() != context.recipeRevision() || plan.capabilityRevision() != context.capabilityRevision()) return new Result(Status.STALE_PLAN, state);
		if (!context.enabled()) return new Result(Status.DISABLED, state);
		if (!context.flower()) return new Result(Status.FLOWER, state);
		if (plan.genesAffectWork() && BeeWorkConditions.evaluate(plan.traits(), context.environment()) != BeeWorkConditions.BlockedBy.NONE) return new Result(Status.ENVIRONMENT, state);
		if (ticks == 0) return new Result(Status.BUDGET, state);
		var progress = BeeProgressPlan.plan(bee.progress(), ticks, plan.cycleTicks(), plan.energyPerTick(), 1);
		if (progress.energyCost() > state.energy()) return new Result(Status.ENERGY, state);
		return sample(state, bee, progress.remainingTicks(), progress.productionCycles(), state.energy() - progress.energyCost(), samplingBudget);
	}
	private static Result sample(BeeMemberState state, BeeRecord bee, int progress, long pending, long energy, int budget) {
		long sampled = Math.min(pending, budget);
		if (sampled == 0 && progress == bee.progress() && pending == bee.pendingCycles() && energy == state.energy()) return new Result(Status.BUDGET, state);
		var amount = ProductAmount.of(sampled).multiply(bee.plan().countPerCycle());
		return new Result(Status.READY, state.update(bee.work(progress, pending - sampled, bee.frozen().add(amount)), energy));
	}
	private BeeWorkExecutor() { }
}
