package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeInputStackMultipliers;
import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeOutputStackMultipliers;
import com.ayoshiko.productivebeesgenesis.inventory.FactoryExternalInsertPolicy;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import mekanism.api.IContentsListener;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.api.recipes.outputs.OutputHelper;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.FactoryInputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntSupplier;

/**
	 * 原版工厂版MEK离心机方块实体 — 继承 {@link AbstractMekCentrifugeFactory}
	 * <br/>
	 * 仅实现原版 Mekanism 工厂的 tier 差异逻辑：
	 * <ul>
	 *   <li>构造函数：调用抽象基类构造（传入 TRACKED_ERROR_TYPES / GLOBAL_ERROR_TYPES）</li>
	 *   <li>{@link #addSlots}：使用原版 OutputInventorySlot / FactoryInputInventorySlot，
	 *       布局通过 {@link FactoryLayoutHelper} 动态计算（支持原版4等级）</li>
	 *   <li>{@link #getPbProcessorName}：返回 "工厂离心机" 用于日志标识</li>
	 * </ul>
	 * 所有公共逻辑（配方查找、PB处理、AE2生命周期、NBT序列化、CAS状态管理等）
	 * 由 {@link AbstractMekCentrifugeFactory} 提供。
	 */
public class TileEntityMekCentrifugeFactory extends AbstractMekCentrifugeFactory {

	public TileEntityMekCentrifugeFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state);
	}

	@Override
	protected String getPbProcessorName() {
		return "工厂离心机";
	}

	/** 每进程3个输出槽（y=57/77/97），副输出槽2用单独数组管理，布局通过 FactoryLayoutHelper 计算 */
	@Override
	protected void addSlots(InventorySlotHelper builder, IContentsListener listener, IContentsListener updateSortingListener) {
		inputHandlers = new IInputHandler[tier.processes];
		outputHandlers = new IOutputHandler[tier.processes];
		processInfoSlots = new ProcessInfo[tier.processes];
		tertiaryOutputSlots = new OutputInventorySlot[tier.processes];
		delegate = FactoryPbContextDelegate.create(this, updateSortingListener, recipeCacheLookupMonitors);

		int baseX = FactoryLayoutHelper.getBaseX(tier);
		int baseXMult = FactoryLayoutHelper.getBaseXMult(tier);

		// 判断是否为 EM 工厂（EM 通过 Mixin 扩展 FactoryTier 枚举，ordinal >= 4）
		// EM 工厂复用本类，但 tier.ordinal() 为 4-8（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
		// 原版工厂 ordinal 0-3 走 forVanillaFactory，EM 工厂 ordinal 4-8 走 forEMFactory（传入相对序号 ordinal-4）
		boolean isEMFactory = tier.ordinal() >= 4
				&& com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks.isEvolvedMekanismLoaded();
		IntSupplier outputMultiplier = isEMFactory
				? CentrifugeOutputStackMultipliers.forEMFactory(tier.ordinal() - 4)
				: CentrifugeOutputStackMultipliers.forVanillaFactory(tier.ordinal());
		IntSupplier inputMultiplier = isEMFactory
				? CentrifugeInputStackMultipliers.forEMFactory(tier.ordinal() - 4)
				: CentrifugeInputStackMultipliers.forVanillaFactory(tier.ordinal());
		FactoryExternalInsertPolicy externalInputPolicy = new FactoryExternalInsertPolicy(
				() -> level == null ? Long.MIN_VALUE : level.getGameTime(),
				() -> FactoryExternalInsertPolicy.recommendedWorkingSet(
						operationsPerTick(), productivebeesgenesis$getTickBatchSkipState().getBatchMultiplier(),
						productivityParallelModifier()));

		for (int i = 0; i < tier.processes; i++) {
			int xPos = baseX + (i * baseXMult);
			var lookupMonitor = recipeCacheLookupMonitors[i];
			IContentsListener updateSortingAndUnpause = delegate.createOutputSlotListener(i);

			OutputInventorySlot outputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 57);
			OutputInventorySlot secondaryOutputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 77);
			OutputInventorySlot tertiaryOutputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 97);
			// Task 8: 工厂版输出槽同步应用 stack_multiplier（蜜脾成倍产出，输出槽需要更大堆叠上限）
			((TieredInputSlot) outputSlot).productivebeesgenesis$setInputStackMultiplier(outputMultiplier);
			((TieredInputSlot) secondaryOutputSlot).productivebeesgenesis$setInputStackMultiplier(outputMultiplier);
			((TieredInputSlot) tertiaryOutputSlot).productivebeesgenesis$setInputStackMultiplier(outputMultiplier);
			tertiaryOutputSlots[i] = tertiaryOutputSlot;

			FactoryInputInventorySlot inputSlot = FactoryInputInventorySlot.create(this, i, outputSlot, secondaryOutputSlot, lookupMonitor, xPos, 13);
			// Task 7: 注入输入槽分等级堆叠倍率（按 FactoryTier.ordinal 索引配置）
			((TieredInputSlot) inputSlot).productivebeesgenesis$setInputStackMultiplier(inputMultiplier);
			externalInputPolicy.register(inputSlot);

			int index = i;
			builder.addSlot(inputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE, getWarningCheck(RecipeError.NOT_ENOUGH_INPUT, index)));
			builder.addSlot(outputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT, getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE, index)));
			builder.addSlot(secondaryOutputSlot);
			builder.addSlot(tertiaryOutputSlot);

			inputHandlers[i] = InputHelper.getInputHandler(inputSlot, RecipeError.NOT_ENOUGH_INPUT);
			outputHandlers[i] = OutputHelper.getOutputHandler(outputSlot, RecipeError.NOT_ENOUGH_OUTPUT_SPACE);
			processInfoSlots[i] = new ProcessInfo(i, inputSlot, outputSlot, secondaryOutputSlot);
		}
	}
}
