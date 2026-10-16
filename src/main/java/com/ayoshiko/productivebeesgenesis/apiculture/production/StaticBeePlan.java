package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.*;

/** 周期固定合同；普通输出分布直接采样，动态结果另由生成凭据保管。 */
public record StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
		int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
		BeeWorkConditions.Traits traits, float productionMultiplier, List<BeeOutputPlan> outputs, BeeGenerator generator,
		boolean generatedCombBlock, BeeProductionEffects effects) {
	public StaticBeePlan {
		Objects.requireNonNull(beeType); Objects.requireNonNull(recipe); Objects.requireNonNull(traits); outputs = List.copyOf(outputs);
		Objects.requireNonNull(generator);
		Objects.requireNonNull(effects);
		generatedCombBlock = generator != BeeGenerator.STATIC && generatedCombBlock;
		if (beeType.isBlank() || recipe.isBlank() || recipeRevision < 0 || capabilityRevision < 0 || cycleTicks < 1
				|| energyPerTick < 0 || productivity < 0 || productivity > 3 || outputs.isEmpty() && generator == BeeGenerator.STATIC
				|| !Float.isFinite(productionMultiplier) || productionMultiplier <= 0)
			throw new IllegalArgumentException("Invalid static bee plan");
	}
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, float productionMultiplier, List<BeeOutputPlan> outputs,
			BeeGenerator generator, boolean generatedCombBlock) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, productionMultiplier, outputs, generator, generatedCombBlock, BeeProductionEffects.NONE);
	}
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, float productionMultiplier, List<BeeOutputPlan> outputs, BeeGenerator generator) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, productionMultiplier, outputs, generator, false);
	}
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork,
			BeeWorkConditions.Traits traits, float productionMultiplier, List<BeeOutputPlan> outputs) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity,
				genesAffectWork, traits, productionMultiplier, outputs, BeeGenerator.STATIC);
	}
	/** 旧双产物构造入口，只用于旧格式迁移与既有固定配方调用。 */
	public StaticBeePlan(String beeType, String recipe, long recipeRevision, long capabilityRevision,
			int cycleTicks, long energyPerTick, int productivity, boolean genesAffectWork, BeeWorkConditions.Traits traits,
			ProductKey output, int count, float multiplier, ProductKey source, ProductKey fluid, int fluidCount) {
		this(beeType, recipe, recipeRevision, capabilityRevision, cycleTicks, energyPerTick, productivity, genesAffectWork, traits, multiplier, legacyOutputs(output, count, source, fluid, fluidCount));
	}
	public StaticBeePlan(String type, String recipe, long recipeRevision, long capabilityRevision, int ticks, long energy, int productivity,
			boolean genes, BeeWorkConditions.Traits traits, ProductKey output, int count, float multiplier, ProductKey source) {
		this(type, recipe, recipeRevision, capabilityRevision, ticks, energy, productivity, genes, traits, output, count, multiplier, source, null, 0);
	}
	public StaticBeePlan(String type, String recipe, long recipeRevision, long capabilityRevision, int ticks, long energy, int productivity,
			boolean genes, BeeWorkConditions.Traits traits, ProductKey output, int count, float multiplier) {
		this(type, recipe, recipeRevision, capabilityRevision, ticks, energy, productivity, genes, traits, output, count, multiplier, output);
	}
	public StaticBeePlan(String type, String recipe, long recipeRevision, long capabilityRevision, int ticks, long energy, int productivity,
			boolean genes, BeeWorkConditions.Traits traits, ProductKey output, int count) {
		this(type, recipe, recipeRevision, capabilityRevision, ticks, energy, productivity, genes, traits, output, count, 1);
	}
	static List<BeeOutputPlan> legacyOutputs(ProductKey output, int count, ProductKey source, ProductKey fluid, int fluidCount) {
		if (count < 1 || (fluid == null ? fluidCount != 0 : fluid.kind() != ProductKey.Kind.FLUID || fluidCount < 1)) throw new IllegalArgumentException("Invalid legacy bee output");
		var item = new BeeOutputPlan(source, output, count, count, 1, BeeOutputPlan.Basis.ROLL);
		return fluid == null ? List.of(item) : List.of(item, new BeeOutputPlan(fluid, fluid, fluidCount, fluidCount, 1, BeeOutputPlan.Basis.CYCLE));
	}
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, List<BeeOutputPlan> nextOutputs) {
		return withCycle(ticks, energy, multiplier, nextOutputs, generatedCombBlock);
	}
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, List<BeeOutputPlan> nextOutputs, boolean combBlock) {
		return withCycle(ticks, energy, multiplier, nextOutputs, combBlock, effects);
	}
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, List<BeeOutputPlan> nextOutputs,
			boolean combBlock, BeeProductionEffects nextEffects) {
		boolean generatedBlock = generator != BeeGenerator.STATIC && combBlock;
		var before = outputs.stream().filter(p -> p.basis() == BeeOutputPlan.Basis.ROLL).toList();
		var after = nextOutputs.stream().filter(p -> p.basis() == BeeOutputPlan.Basis.ROLL).toList();
		if (before.size() != after.size()) throw new IllegalArgumentException("Bee recipe changed at capability boundary");
		for (int i = 0; i < before.size(); i++) if (!before.get(i).sameRecipe(after.get(i))) throw new IllegalArgumentException("Bee recipe changed at capability boundary");
		return cycleTicks == ticks && energyPerTick == energy && productionMultiplier == multiplier && outputs.equals(nextOutputs)
				&& generatedCombBlock == generatedBlock && effects.equals(nextEffects) ? this
				: new StaticBeePlan(beeType, recipe, recipeRevision, Math.incrementExact(capabilityRevision), ticks, energy,
						productivity, genesAffectWork, traits, multiplier, nextOutputs, generator, generatedBlock, nextEffects);
	}
	public boolean requiresGeneration() { return generator != BeeGenerator.STATIC || effects.enabled(); }
	public boolean deterministic() { return !requiresGeneration() && outputs.stream().allMatch(BeeOutputPlan::deterministic); }
	/** 标量兼容访问拒绝多输出，防止旧调用方静默遗漏新产物。 */
	private BeeOutputPlan legacyItem() {
		var items = outputs.stream().filter(p -> p.basis() == BeeOutputPlan.Basis.ROLL).toList();
		if (items.size() != 1 || !items.getFirst().deterministic() || items.getFirst().chance() != 1) throw new IllegalStateException("Use complete bee outputs");
		return items.getFirst();
	}
	public ProductKey output() { return legacyItem().output(); }
	public ProductKey sourceOutput() { return legacyItem().source(); }
	public int count() { return legacyItem().minimum(); }
	public int countPerRoll() { return BeeProductionSampling.adjustStackCount(count(), productivity); }
	private BeeOutputPlan legacyFluid() {
		var fluids = outputs.stream().filter(p -> p.output().kind() == ProductKey.Kind.FLUID).toList();
		if (fluids.size() > 1) throw new IllegalStateException("Use complete bee outputs");
		return fluids.isEmpty() ? null : fluids.getFirst();
	}
	public ProductKey fluidOutput() { var fluid = legacyFluid(); return fluid == null ? null : fluid.output(); }
	public int fluidPerCycle() { var fluid = legacyFluid(); return fluid == null ? 0 : fluid.minimum(); }
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, ProductKey output) { return withCycle(ticks, energy, multiplier, output, fluidOutput(), fluidPerCycle()); }
	public StaticBeePlan withCycle(int ticks, long energy, float multiplier, ProductKey output, ProductKey fluid, int count) {
		return withCycle(ticks, energy, multiplier, legacyOutputs(output, count(), sourceOutput(), fluid, count));
	}
}
