package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.Map;

/** 万象周期使用原选型规则；已完成的随机模板和数量交给普通离心机的已付缓冲。 */
public class MyriadCreationsHandler {
	private static final int OUTPUT_SLOT_COUNT = 3;
	private static final int WEIGHTED_SELECTOR_THRESHOLD = 1024;

	private final PbRecipeContext context;
	private final int[] pbOperatingTicks;
	private final boolean[] pbProcessing;
	private final int[] pbProcessingTime;
	private final RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes;
	private final PbRecipeCompleter[] completers;
	private final MyriadFluidOutputHandler fluidOutputHandler;
	private final MyriadProductPool[] myriadProductPools;
	private final MyriadCreationsCache cache = new MyriadCreationsCache();
	private final MyriadCreationsLogger logger;

	public MyriadCreationsHandler(PbRecipeContext context, String logPrefix,
			int[] pbOperatingTicks, boolean[] pbProcessing, int[] pbProcessingTime,
			RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes, PbRecipeFinder recipeFinder,
			PbRecipeCompleter[] completers) {
		this.context = context;
		this.pbOperatingTicks = pbOperatingTicks;
		this.pbProcessing = pbProcessing;
		this.pbProcessingTime = pbProcessingTime;
		this.cachedPbRecipes = cachedPbRecipes;
		this.completers = completers;
		fluidOutputHandler = new MyriadFluidOutputHandler(context, recipeFinder, logPrefix, context.processes());
		logger = new MyriadCreationsLogger(logPrefix);
		myriadProductPools = new MyriadProductPool[context.processes()];
		for (int process = 0; process < myriadProductPools.length; process++) {
			myriadProductPools[process] = new MyriadProductPool(context);
			completers[process].bindMyriadFluidOutput(fluidOutputHandler);
		}
	}

	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long energyPerTick, int operationsPerTick, int virtualTicks) {
		return tryProcessMyriadCreations(processIndex, input, energyPerTick,
				operationsPerTick, virtualTicks, Long.MAX_VALUE);
	}

	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long energyPerTick, int operationsPerTick, int virtualTicks, long energyBudget) {
		if (!drainPendingOutputs(processIndex) || !MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			pbProcessing[processIndex] = false;
			return false;
		}
		PbRecipeCompleter completer = completers[processIndex];
		if (completer.hasPaidPendingOutputs() && !PbRecipeFlushHelper.flushCompleted(completer, processIndex)) return false;
		long availableEnergy = Math.min(context.energyContainer().getEnergy(), Math.max(0L, energyBudget));
		return tryProcessMyriadCreations(processIndex, context.inputSlot(processIndex).getStack(), energyPerTick,
				operationsPerTick, virtualTicks, availableEnergy, false);
	}

	boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long energyPerTick, int operationsPerTick, int virtualTicks, long energyBudget, boolean resumePaidCycle) {
		PbRecipeCompleter completer = completers[processIndex];
		Level level = context.level();
		if (level == null || level.isClientSide) return false;
		int processingTime = cache.getCachedTicksForBase(context);
		pbProcessingTime[processIndex] = processingTime;
		cachedPbRecipes[processIndex] = null;
		int maxOps = cache.refreshAndGetMaxOps(level);
		int effectiveOps = maxOps > 0 ? Math.min(operationsPerTick, maxOps) : operationsPerTick;
		int inputCount = input.getCount();
		effectiveOps = Math.min(effectiveOps, inputCount);
		long energy = energyBudget;
		if (effectiveOps <= 0) return false;
		boolean blocked = false;
		if (!context.productivebeesgenesis$isDirectAeOutputEnabled()) {
			if (MyriadCreationsCache.areOutputSlotsFull(context, processIndex)) {
				blocked = true;
			} else {
				fluidOutputHandler.initFluidTankFullCache();
				blocked = fluidOutputHandler.isFluidTankFull();
			}
		}
		if (blocked || resumePaidCycle) {
			virtualTicks = Math.min(virtualTicks, Math.max(1, processingTime - pbOperatingTicks[processIndex]));
		}
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(pbOperatingTicks[processIndex], Math.max(1, virtualTicks),
				processingTime, effectiveOps, inputCount, energyPerTick, energy);
		if (plan.executedTicks() <= 0 && plan.completedOperations() <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}
		PreparedOutputs prepared = null;
		if (plan.completedOperations() > 0) {
			int modifier = Math.max(1, context.productivityModifier());
			FluidStack fluid = fluidOutputHandler.resolveFluidOutput(input, processIndex);
			if (context.suppressesUselessByproducts() && UselessByproductUpgradeHelper.isHoney(fluid)) {
				fluid = FluidStack.EMPTY;
			}
			long perInput = Math.max(MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input) ? 4L : 1L,
					fluid.getAmount()) * modifier;
			int limit = (int) Math.min(inputCount, Long.MAX_VALUE / perInput);
			if (plan.completedOperations() > limit) {
				plan = PbVirtualTickPlan.create(pbOperatingTicks[processIndex], Math.max(1, virtualTicks),
						processingTime, Math.min(effectiveOps, limit), limit, energyPerTick, energy);
			}
			prepared = prepareOutputs(input, processIndex, plan.completedOperations(), modifier, fluid);
			if (prepared == null) return false;
		}
		int completed = plan.completedOperations();
		int remainingProgress = plan.remainingProgress();
		if (!completer.payForPrepared(prepared == null ? null : prepared.batch(), input, completed,
				plan, () -> pbOperatingTicks[processIndex] = remainingProgress)) {
			pbProcessing[processIndex] = false;
			return false;
		}
		if (prepared != null) WeightedTypeSelector.getInstance().recordOutputs(prepared.allocation());
		pbProcessing[processIndex] = true;
		context.productivebeesgenesis$markForSave();
		if (completed > 0) completer.flushCompletedBatch(processIndex);
		return true;
	}

	private record PreparedOutputs(PbRecipeCompleter batch, Map<ResourceLocation, Long> allocation) {}

	private PreparedOutputs prepareOutputs(ItemStack input, int processIndex, int inputs, int modifier, FluidStack fluid) {
		Level level = context.level();
		boolean block = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		long total = Math.multiplyExact(Math.multiplyExact((long) inputs, block ? 4 : 1), modifier);
		List<ResourceLocation> selected;
		if (inputs >= WEIGHTED_SELECTOR_THRESHOLD) {
			List<ResourceLocation> allTypes = MyriadBeeTypeCache.cachedBeeTypes(block);
			selected = allTypes.isEmpty() ? List.of() : WeightedTypeSelector.getInstance()
					.selectForProcess(processIndex, context.processes(), level, allTypes, context);
		} else {
			selected = MyriadCreationsEventHandler.selectDistinctBeeTypesCached(
					(int) Math.min(OUTPUT_SLOT_COUNT, total), level, block);
			if (!selected.isEmpty()) selected = myriadProductPools[processIndex]
					.getOrRefresh(selected, level.getGameTime(), inputs, level);
		}
		if (selected.isEmpty()) {
			logger.logEmptyCacheAndPreserve(processIndex);
			return null;
		}
		selected = selected.subList(0, (int) Math.min(selected.size(), Math.min(OUTPUT_SLOT_COUNT, total)));
		Map<ResourceLocation, Long> allocation = WeightedAllocation.allocateByWeight(
				total, selected, WeightedTypeSelector.getInstance().getWeightsFor(selected));
		var snapshot = MyriadBeeTypeCache.snapshot();
		var variants = block ? snapshot.combBlockVariants() : snapshot.honeycombVariants();
		long fluidAmount = Math.multiplyExact(Math.multiplyExact((long) fluid.getAmount(), inputs), modifier);
		PbRecipeCompleter batch = completers[processIndex].prepareMyriadCompleted(
				selected, allocation, variants, level.getRandom(), fluid, fluidAmount, input, inputs);
		return batch == null ? null : new PreparedOutputs(batch, allocation);
	}

	public boolean hasUnresolvedState() {
		return fluidOutputHandler.hasUnresolvedState();
	}

	public boolean drainPendingOutputs(int processIndex) {
		return fluidOutputHandler.flushPendingFluid(processIndex);
	}

	public void clearCachedTicksForBase() {
		cache.clearCachedTicksForBase();
	}

	void clearTransientFailures() {
		fluidOutputHandler.clearTransientFailures();
	}

	public void saveAdditional(CompoundTag nbt) {
		fluidOutputHandler.saveAdditional(nbt);
	}

	public void loadAdditional(CompoundTag nbt) {
		fluidOutputHandler.loadAdditional(nbt);
	}
}
