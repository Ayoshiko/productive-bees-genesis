package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;

/** 私有候选计算；只有外层发布整个 checkpoint 才产生付款或生产。 */
public final class BeeWorkExecutor {
	public static final int MAX_RANDOM_CYCLES_PER_STEP = 64;
	public enum Status { READY, STALE_PLAN, UNLOADED, DISABLED, FLOWER, ENVIRONMENT, ENERGY, DRAIN_FIRST, BUDGET }
	public record Cycle(int cycleTicks, long energyPerTick, float productionMultiplier, ProductKey output) {
		public Cycle {
			Objects.requireNonNull(output);
			if (cycleTicks < 1 || energyPerTick < 0 || !Float.isFinite(productionMultiplier) || productionMultiplier <= 0)
				throw new IllegalArgumentException("Invalid bee cycle");
		}
		public boolean matches(StaticBeePlan plan) {
			return cycleTicks == plan.cycleTicks() && energyPerTick == plan.energyPerTick()
					&& productionMultiplier == plan.productionMultiplier() && output.equals(plan.output());
		}
	}
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
	/** 单蜂计算证明；不绑定基础三蜂位容器，旧蜂箱与一体机共用同一付款／采样内核。 */
	public static final class BeeResult {
		private final Status status;
		private final BeeRecord source, candidate;
		private final long energyUsed;
		private BeeResult(Status status, BeeRecord source, BeeRecord candidate, long energyUsed) {
			this.status = status; this.source = source; this.candidate = candidate; this.energyUsed = energyUsed;
		}
		public Status status() { return status; }
		public BeeRecord candidate() { return candidate; }
		public long energyUsed() { return energyUsed; }
		public boolean matches(BeeRecord current) { return source == current; }
	}
	public static Result advance(BeeMemberState state, int slot, long expectedRevision, Context context, int ticks, int samplingBudget) {
		return advance(state, slot, expectedRevision, context, ticks, samplingBudget, state.energy());
	}
	public static Result advance(BeeMemberState state, int slot, long expectedRevision, Context context, int ticks, int samplingBudget, long energyBudget) {
		return advance(state, slot, expectedRevision, context, ticks, samplingBudget, energyBudget, null);
	}
	/** 新能力只在本次确实获准生产时与付款、进度一起进入候选。 */
	public static Result advance(BeeMemberState state, int slot, long expectedRevision, Context context, int ticks,
			int samplingBudget, long energyBudget, Cycle cycle) {
		if (energyBudget < 0 || !state.networkPowered() && energyBudget != state.energy()) throw new IllegalArgumentException("Foreign bee energy budget");
		var bee = state.bee(slot);
		var result = advanceBee(bee, expectedRevision, context, ticks, samplingBudget, energyBudget, cycle);
		if (result.status() != Status.READY) return new Result(result.status(), state, state, 0);
		long remaining = state.networkPowered() ? 0 : state.energy() - result.energyUsed();
		var next = result.candidate();
		return new Result(Status.READY, state, next.plan() == bee.plan() ? state.update(next, remaining) : state.updateCycle(next, remaining), result.energyUsed());
	}
	public static BeeResult advanceBee(BeeRecord bee, long expectedRevision, Context context, int ticks,
			int samplingBudget, long energyBudget, Cycle cycle) {
		Objects.requireNonNull(bee); Objects.requireNonNull(context);
		if (energyBudget < 0) throw new IllegalArgumentException("Negative bee energy budget");
		var plan = bee.plan();
		if (ticks < 0 || samplingBudget < 0) throw new IllegalArgumentException("Negative work budget");
		if (bee.revision() != expectedRevision) return new BeeResult(Status.STALE_PLAN, bee, bee, 0);
		if (!context.loaded()) return new BeeResult(Status.UNLOADED, bee, bee, 0);
		// 已付费积压使用保存的配方，不因重载或天气变化再次收费或丢弃。
		if (ticks == 0 && bee.pendingCycles() > 0) return sample(bee, plan, bee.progress(), bee.pendingCycles(), 0, samplingBudget);
		if (!bee.drained()) return new BeeResult(Status.DRAIN_FIRST, bee, bee, 0);
		if (plan.recipeRevision() != context.recipeRevision() || plan.capabilityRevision() != context.capabilityRevision()) return new BeeResult(Status.STALE_PLAN, bee, bee, 0);
		if (!context.enabled()) return new BeeResult(Status.DISABLED, bee, bee, 0);
		if (!context.flower()) return new BeeResult(Status.FLOWER, bee, bee, 0);
		if (plan.genesAffectWork() && BeeWorkConditions.evaluate(plan.traits(), context.environment()) != BeeWorkConditions.BlockedBy.NONE) return new BeeResult(Status.ENVIRONMENT, bee, bee, 0);
		if (ticks == 0) return new BeeResult(Status.BUDGET, bee, bee, 0);
		if (cycle != null) {
			if (bee.progress() != 0) throw new IllegalArgumentException("New bee capability inside an active cycle");
			plan = plan.withCycle(cycle.cycleTicks(), cycle.energyPerTick(), cycle.productionMultiplier(), cycle.output());
		}
		if (plan.energyPerTick() > 0 && ticks > energyBudget / plan.energyPerTick()) return new BeeResult(Status.ENERGY, bee, bee, 0);
		var progress = BeeProgressPlan.plan(bee.progress(), ticks, plan.cycleTicks(), plan.energyPerTick(), 1);
		if (progress.energyCost() > energyBudget) return new BeeResult(Status.ENERGY, bee, bee, 0);
		return sample(bee, plan, progress.remainingTicks(), progress.productionCycles(), progress.energyCost(), samplingBudget);
	}
	private static BeeResult sample(BeeRecord bee, StaticBeePlan plan, int progress, long pending, long energyUsed, int budget) {
		Math.addExact(bee.random().cursor(), pending);
		long sampled = Math.min(pending, budget);
		if (sampled == 0 && progress == bee.progress() && pending == bee.pendingCycles() && energyUsed == 0) return new BeeResult(Status.BUDGET, bee, bee, 0);
		var amount = ProductAmount.ZERO;
		if (sampled > 0) {
			if (plan.productionMultiplier() == 1) amount = ProductAmount.of(sampled).multiply(plan.countPerRoll());
			else {
				var rolls = BeeProductionRollPlan.fromMultiplier(plan.productionMultiplier());
				long extra = 0;
				if (rolls.extraChance() > 0) {
					sampled = Math.min(sampled, MAX_RANDOM_CYCLES_PER_STEP);
					for (int i = 0; i < sampled; i++) if (bee.random().draw(i) < rolls.extraChance()) extra++;
				}
				amount = rolls.fixedRolls().multiply(sampled).add(ProductAmount.of(extra)).multiply(plan.countPerRoll());
			}
		}
		var next = new BeeRecord(bee.id(), bee.member(), bee.slot(), bee.originalSlot(), plan, Math.incrementExact(bee.revision()),
				progress, pending - sampled, bee.frozen().add(amount), bee.random().advance(sampled));
		return new BeeResult(Status.READY, bee, next, energyUsed);
	}
	private BeeWorkExecutor() { }
}
