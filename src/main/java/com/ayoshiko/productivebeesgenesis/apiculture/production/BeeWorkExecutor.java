package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;

/** 私有候选计算；只有外层发布整个 checkpoint 才产生付款或生产。 */
public final class BeeWorkExecutor {
	public enum Status { READY, STALE_PLAN, UNLOADED, DISABLED, FLOWER, ENVIRONMENT, ENERGY, DRAIN_FIRST, BUDGET }
	public record Context(boolean loaded, boolean enabled, boolean flower, long recipeRevision, long capabilityRevision,
			BeeWorkConditions.Environment environment) { }
	public static final class Result {
		private final Status status;
		private final BeeMemberState source, candidate;
		private final long energyUsed;
		private Result(Status status, BeeMemberState source, BeeMemberState candidate, long energyUsed) {
			this.status = status; this.source = source; this.candidate = candidate; this.energyUsed = energyUsed;
		}
		public Status status() { return status; }
		public BeeMemberState candidate() { return candidate; }
		public long energyUsed() { return energyUsed; }
		public boolean matches(BeeMemberState state) { return source == state; }
	}
	public static Result advance(BeeMemberState state, int slot, long expectedRevision, Context context, int ticks, int samplingBudget) {
		return advance(state, slot, expectedRevision, context, ticks, samplingBudget, state.energy());
	}
	public static Result advance(BeeMemberState state, int slot, long expectedRevision, Context context, int ticks, int samplingBudget, long energyBudget) {
		if (energyBudget < 0 || !state.networkPowered() && energyBudget != state.energy()) throw new IllegalArgumentException("Foreign bee energy budget");
		var bee = state.bee(slot); var plan = bee.plan();
		if (ticks < 0 || samplingBudget < 0) throw new IllegalArgumentException("Negative work budget");
		if (bee.revision() != expectedRevision) return new Result(Status.STALE_PLAN, state, state, 0);
		if (!context.loaded()) return new Result(Status.UNLOADED, state, state, 0);
		// 已付费积压使用保存的配方，不因重载或天气变化再次收费或丢弃。
		if (ticks == 0 && bee.pendingCycles() > 0) return sample(state, bee, bee.progress(), bee.pendingCycles(), 0, samplingBudget);
		if (!bee.drained()) return new Result(Status.DRAIN_FIRST, state, state, 0);
		if (plan.recipeRevision() != context.recipeRevision() || plan.capabilityRevision() != context.capabilityRevision()) return new Result(Status.STALE_PLAN, state, state, 0);
		if (!context.enabled()) return new Result(Status.DISABLED, state, state, 0);
		if (!context.flower()) return new Result(Status.FLOWER, state, state, 0);
		if (plan.genesAffectWork() && BeeWorkConditions.evaluate(plan.traits(), context.environment()) != BeeWorkConditions.BlockedBy.NONE) return new Result(Status.ENVIRONMENT, state, state, 0);
		if (ticks == 0) return new Result(Status.BUDGET, state, state, 0);
		if (plan.energyPerTick() > 0 && ticks > energyBudget / plan.energyPerTick()) return new Result(Status.ENERGY, state, state, 0);
		var progress = BeeProgressPlan.plan(bee.progress(), ticks, plan.cycleTicks(), plan.energyPerTick(), 1);
		if (progress.energyCost() > energyBudget) return new Result(Status.ENERGY, state, state, 0);
		return sample(state, bee, progress.remainingTicks(), progress.productionCycles(), progress.energyCost(), samplingBudget);
	}
	private static Result sample(BeeMemberState state, BeeRecord bee, int progress, long pending, long energyUsed, int budget) {
		long sampled = Math.min(pending, budget);
		if (sampled == 0 && progress == bee.progress() && pending == bee.pendingCycles() && energyUsed == 0) return new Result(Status.BUDGET, state, state, 0);
		var amount = ProductAmount.of(sampled).multiply(bee.plan().countPerCycle());
		return new Result(Status.READY, state, state.update(bee.work(progress, pending - sampled, bee.frozen().add(amount)),
				state.networkPowered() ? 0 : state.energy() - energyUsed), energyUsed);
	}
	private BeeWorkExecutor() { }
}
