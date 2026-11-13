package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.PbVirtualTickPlan;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.LinkedHashMap;
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
		}
	}

	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long energyPerTick, int operationsPerTick, int virtualTicks) {
		return tryProcessMyriadCreations(processIndex, input, energyPerTick,
				operationsPerTick, virtualTicks, Long.MAX_VALUE);
	}

	public boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long energyPerTick, int operationsPerTick, int virtualTicks, long energyBudget) {
		return tryProcessMyriadCreations(processIndex, input, energyPerTick,
				operationsPerTick, virtualTicks, energyBudget, false);
	}

	boolean tryProcessMyriadCreations(int processIndex, ItemStack input,
			long energyPerTick, int operationsPerTick, int virtualTicks, long energyBudget, boolean resumePaidCycle) {
		if (!drainPendingOutputs(processIndex) || !MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			pbProcessing[processIndex] = false;
			return false;
		}
		PbRecipeCompleter completer = completers[processIndex];
		if (completer.hasPaidPendingOutputs() && !PbRecipeFlushHelper.flushCompleted(completer, processIndex)) return false;
		Level level = context.level();
		if (level == null || level.isClientSide) return false;
		int processingTime = cache.getCachedTicksForBase(context);
		pbProcessingTime[processIndex] = processingTime;
		cachedPbRecipes[processIndex] = null;
		int maxOps = cache.refreshAndGetMaxOps(level);
		int effectiveOps = maxOps > 0 ? Math.min(operationsPerTick, maxOps) : operationsPerTick;
		int inputCount = context.inputSlot(processIndex).getCount();
		effectiveOps = Math.min(effectiveOps, inputCount);
		long energy = Math.min(context.energyContainer().getEnergy(), Math.max(0L, energyBudget));
		if (effectiveOps <= 0) return false;
		fluidOutputHandler.initFluidTankFullCache();
		boolean blocked = !context.productivebeesgenesis$isDirectAeOutputEnabled()
				&& (MyriadCreationsCache.areOutputSlotsFull(context, processIndex) || fluidOutputHandler.isFluidTankFull());
		if (blocked || resumePaidCycle) {
			virtualTicks = Math.min(virtualTicks, Math.max(1, processingTime - pbOperatingTicks[processIndex]));
		}
		PbVirtualTickPlan plan = PbVirtualTickPlan.create(pbOperatingTicks[processIndex], Math.max(1, virtualTicks),
				processingTime, effectiveOps, inputCount, energyPerTick, energy);
		if (plan.executedTicks() <= 0 && plan.completedOperations() <= 0) {
			pbProcessing[processIndex] = false;
			return false;
		}
		int completed = plan.completedOperations();
		if (completed > 0 && !freezeOutputs(input, processIndex, completed)) return false;
		if (!completer.payForPlan(plan, () -> pbOperatingTicks[processIndex] = plan.remainingProgress())) {
			pbProcessing[processIndex] = false;
			return false;
		}
		pbProcessing[processIndex] = true;
		context.productivebeesgenesis$markForSave();
		if (completed > 0) completer.flushCompletedBatch(processIndex);
		fluidOutputHandler.refreshFluidTankFullCache();
		return true;
	}

	private boolean freezeOutputs(ItemStack input, int processIndex, int inputs) {
		Level level = context.level();
		boolean block = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		int modifier = Math.max(1, context.productivityModifier());
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
			return false;
		}
		selected = selected.subList(0, (int) Math.min(selected.size(), Math.min(OUTPUT_SLOT_COUNT, total)));
		Map<ResourceLocation, Long> allocation = WeightedAllocation.allocateByWeight(
				total, selected, WeightedTypeSelector.getInstance().getWeightsFor(selected));
		Map<ResourceLocation, ItemStack> templates = MyriadBeeTypeCache.snapshot()
				.selectTemplates(selected, block, level.getRandom());
		Map<ItemStack, Long> outputs = new LinkedHashMap<>();
		for (var entry : allocation.entrySet()) {
			ItemStack template = templates.get(entry.getKey());
			if (template == null || template.isEmpty()) return false;
			outputs.put(template.copyWithCount(1), entry.getValue());
		}
		FluidStack fluid = fluidOutputHandler.resolveFluidOutput(input, processIndex);
		long fluidAmount = Math.multiplyExact(Math.multiplyExact((long) fluid.getAmount(), inputs), modifier);
		completers[processIndex].freezeMyriadBatch(outputs, fluid, fluidAmount, input, inputs);
		WeightedTypeSelector.getInstance().recordOutputs(allocation);
		return true;
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

	public void saveAdditional(CompoundTag nbt) {
		fluidOutputHandler.saveAdditional(nbt);
	}

	public void loadAdditional(CompoundTag nbt) {
		fluidOutputHandler.loadAdditional(nbt);
	}
}
