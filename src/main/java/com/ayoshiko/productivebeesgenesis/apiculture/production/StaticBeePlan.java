package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;

/** D13 首个已审查路径只有一个必定产物；随机和副产物路径须另行准入。 */
public record StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
		int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
		BeeWorkConditions.Traits traits, ProductKey output, int count) {
	public StaticBeePlan {
		Objects.requireNonNull(beeType); Objects.requireNonNull(recipe); Objects.requireNonNull(traits); Objects.requireNonNull(output);
		if (beeType.isBlank() || recipe.isBlank() || recipeRevision < 0 || capabilityRevision < 0 || cycleTicks < 1
				|| energyPerTick < 0 || productivity < 0 || productivity > 3 || count < 1) throw new IllegalArgumentException("Invalid static bee plan");
	}
	public int countPerCycle() { return BeeProductionSampling.adjustStackCount(count, productivity); }
}
