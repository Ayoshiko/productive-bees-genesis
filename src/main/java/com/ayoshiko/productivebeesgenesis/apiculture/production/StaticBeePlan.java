package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;

/** D13 首个已审查路径只有一个必定产物；随机和副产物路径须另行准入。 */
public record StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
		int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
		BeeWorkConditions.Traits traits, ProductKey output, int count, float productionMultiplier, ProductKey sourceOutput) {
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
				|| !Float.isFinite(productionMultiplier) || productionMultiplier <= 0) throw new IllegalArgumentException("Invalid static bee plan");
	}
	/** 周期边界切换能力与实际输出键，原配方键、蜂种和基因保持不变。 */
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, ProductKey nextOutput) {
		return cycleTicks == ticks && energyPerTick == energy && productionMultiplier == multiplier && output.equals(nextOutput) ? this
				: new StaticBeePlan(beeType, recipe, recipeRevision, Math.incrementExact(capabilityRevision),
						ticks, energy, productivity, genesAffectWork, traits, nextOutput, count, multiplier, sourceOutput);
	}
	public int countPerRoll() { return BeeProductionSampling.adjustStackCount(count, productivity); }
}
