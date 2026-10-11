package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;

/** 固定物品配方与每生产周期的独立流体副产物；倍率只作用于物品抽样。 */
public record StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
		int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
		BeeWorkConditions.Traits traits, ProductKey output, int count, float productionMultiplier, ProductKey sourceOutput,
		ProductKey fluidOutput, int fluidPerCycle) {
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, ProductKey output, int count, float productionMultiplier, ProductKey sourceOutput) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, output, count, productionMultiplier, sourceOutput, null, 0);
	}
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, ProductKey output, int count, float productionMultiplier) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, output, count, productionMultiplier, output);
	}
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, ProductKey output, int count) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, output, count, 1);
	}
	public StaticBeePlan {
		Objects.requireNonNull(beeType); Objects.requireNonNull(recipe); Objects.requireNonNull(traits); Objects.requireNonNull(output); Objects.requireNonNull(sourceOutput);
		if (beeType.isBlank() || recipe.isBlank() || recipeRevision < 0 || capabilityRevision < 0 || cycleTicks < 1
				|| energyPerTick < 0 || productivity < 0 || productivity > 3 || count < 1
				|| !Float.isFinite(productionMultiplier) || productionMultiplier <= 0
				|| output.kind() != ProductKey.Kind.ITEM || sourceOutput.kind() != ProductKey.Kind.ITEM)
			throw new IllegalArgumentException("Invalid static bee plan");
		if (fluidOutput == null ? fluidPerCycle != 0 : fluidOutput.kind() != ProductKey.Kind.FLUID || fluidPerCycle < 1)
			throw new IllegalArgumentException("Invalid bee fluid byproduct");
	}
	/** 周期边界切换能力和输出；旧周期及已冻结物品／流体仍使用原计划。 */
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, ProductKey nextOutput) {
		return withCycle(ticks, energy, multiplier, nextOutput, fluidOutput, fluidPerCycle);
	}
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, ProductKey nextOutput, ProductKey fluid, int fluidCount) {
		return cycleTicks == ticks && energyPerTick == energy && productionMultiplier == multiplier && output.equals(nextOutput)
				&& Objects.equals(fluidOutput, fluid) && fluidPerCycle == fluidCount ? this
				: new StaticBeePlan(beeType, recipe, recipeRevision, Math.incrementExact(capabilityRevision),
						ticks, energy, productivity, genesAffectWork, traits, nextOutput, count, multiplier, sourceOutput, fluid, fluidCount);
	}
	public int countPerRoll() { return BeeProductionSampling.adjustStackCount(count, productivity); }
}
