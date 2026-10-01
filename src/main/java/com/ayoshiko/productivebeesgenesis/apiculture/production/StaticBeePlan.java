package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;

/** D13 首个已审查路径只有一个必定产物；随机和副产物路径须另行准入。 */
public record StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
		int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
		BeeWorkConditions.Traits traits, ProductKey output, int count, float productionMultiplier) {
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, ProductKey output, int count) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, output, count, 1);
	}
	public StaticBeePlan {
		Objects.requireNonNull(beeType); Objects.requireNonNull(recipe); Objects.requireNonNull(traits); Objects.requireNonNull(output);
		if (beeType.isBlank() || recipe.isBlank() || recipeRevision < 0 || capabilityRevision < 0 || cycleTicks < 1
				|| energyPerTick < 0 || productivity < 0 || productivity > 3 || count < 1
				|| !Float.isFinite(productionMultiplier) || productionMultiplier <= 0) throw new IllegalArgumentException("Invalid static bee plan");
	}
	/** 周期边界只切换耗时与单价，不改蜂种、基因、产物或配方身份。 */
	public StaticBeePlan retime(int ticks, long energy) {
		return cycleTicks == ticks && energyPerTick == energy ? this
				: new StaticBeePlan(beeType, recipe, recipeRevision, Math.incrementExact(capabilityRevision),
						ticks, energy, productivity, genesAffectWork, traits, output, count, productionMultiplier);
	}
	public int countPerRoll() { return BeeProductionSampling.adjustStackCount(count, productivity); }
}
