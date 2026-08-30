package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic;
import com.ayoshiko.productivebeesgenesis.mek.FactoryPbUpgradeDelegate;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.mek.MultiFluidTankHostDelegate;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeProcessor;
import com.ayoshiko.productivebeesgenesis.mek.ZeroTickCoalesceState;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.util.InputOutputCompatibilityCache;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.recipe.lookup.monitor.FactoryRecipeCacheLookupMonitor;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;

/**
	 * TileEntityEMExtraFactoryDelegates — 承载从 TileEntityEMExtraMekCentrifugeFactory 搬移的配方/追踪器/持久化委托方法组。
	 * 纯代码搬移：方法体与原类保持一致，private/protected 字段通过参数传入，行为不变。
	 */
final class TileEntityEMExtraFactoryDelegates {

	private TileEntityEMExtraFactoryDelegates() {
	}

	/** 清空输入校验缓存并重置配方监视器 — smelting-compat 开关切换时调用 */
	static void onSmeltingCompatChanged(InputValidationCache validInputCache,
			InputOutputCompatibilityCache inputProducesOutputCache, PbRecipeProcessor pbProcessor, int processes,
			FactoryRecipeCacheLookupMonitor<?>[] recipeCacheLookupMonitors, boolean smeltingEnabled) {
		validInputCache.clear();
		inputProducesOutputCache.clear();
		for (int i = 0; i < processes; i++) {
			pbProcessor.resetSmeltingCache(i);
			if (smeltingEnabled) {
				MekCentrifugeFactoryHelper.invalidateRecipeMonitor(recipeCacheLookupMonitors[i]);
			}
		}
	}

	/** 只查SMELTING配方，PB配方由tryProcessPbRecipe独立处理 */
	@Nullable
	static ItemStackToItemStackRecipe findRecipe(TileEntityEMExtraMekCentrifugeFactory tile, @NotNull Level level,
			@NotNull ItemStack fallbackInput, @NotNull IInventorySlot outputSlot) {
		return MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(tile)
				? MekCentrifugeFactoryHelper.findSmeltingRecipe(tile.getRecipeType(), level, fallbackInput, outputSlot)
				: null;
	}

	/** 写入配置卡数据 — 添加PB升级数量和AE2 per-tile状态 */
	static void writeSustainedData(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag data,
			@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate, @NotNull Ae2OutputStateHolder ae2StateHolder,
			boolean sorting, @NotNull Runnable superWrite) {
		superWrite.run();
		CentrifugeFactoryCommonLogic.writeSustainedData(data, pbUpgradeDelegate, ae2StateHolder);
		// 修复 SORTING 字段丢失：父类 writeSustainedData 会用 isSorting() 写入 SORTING=false
		// （AE2 拉取期间 isSorting() 锁死为 false），需用 accessor 获取真实 sorting 字段值覆盖
		// EME 的 TileEntityEMExtraFactory 不继承 Mekanism 的 TileEntityFactory，必须用 EME 专用 Accessor
		data.putBoolean(mekanism.api.SerializationConstants.SORTING, sorting);
	}

	/**
	 * 扳手拆卸隐式组件 — 覆盖父类用 isSorting() 写入的 false，持久化实际 sorting 字段值
	 * <br/>
	 * 修复 SORTING 字段丢失：父类 collectImplicitComponents 会写入 SORTING=false
	 * （因 isSorting() 在 AE2 拉取期间锁死为 false），需通过 accessor 获取真实 sorting 状态。
	 * EME 的 TileEntityEMExtraFactory 不继承 Mekanism 的 TileEntityFactory，必须用 EME 专用 Accessor
	 */
	static void collectImplicitComponents(@NotNull DataComponentMap.Builder builder, boolean sorting,
			@NotNull Runnable superCollect) {
		superCollect.run();
		builder.set(mekanism.common.registries.MekanismDataComponents.SORTING, sorting);
	}

	@NotNull
	static IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekCentrifugeFactoryHelper.getSmeltingRecipeType();
	}

	@NotNull
	static IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return MekCentrifugeFactoryHelper.getSmeltingRecipeViewerType();
	}

	/** PB配方存在时返回null，阻止SMELTING管线抢占输入 */
	@Nullable
	static ItemStackToItemStackRecipe getRecipe(TileEntityEMExtraMekCentrifugeFactory tile,
			@NotNull IInputHandler[] inputHandlers, int cacheIndex, @NotNull PbRecipeProcessor pbProcessor) {
		return CentrifugeFactoryCommonLogic.getRecipe(inputHandlers, cacheIndex, pbProcessor, tile::findFirstRecipe,
				MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(tile));
	}

	@NotNull
	static CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(@NotNull ItemStackToItemStackRecipe recipe,
			int cacheIndex, BooleanSupplier[] recheckAllRecipeErrors, IInputHandler[] inputHandlers,
			IOutputHandler[] outputHandlers, @NotNull ObjIntConsumer<Set<RecipeError>> errorsChanged,
			@NotNull BooleanSupplier canFunction, @NotNull ObjIntConsumer<Boolean> setActiveState,
			@NotNull BooleanSupplier hasCreativeUpgrade, @NotNull MachineEnergyContainer<?> energyContainer,
			@NotNull IntSupplier ticksRequired, @NotNull Runnable markForSave, @NotNull IntSupplier operationsPerTick,
			int[] progress, ZeroTickCoalesceState coalesceState) {
		return CentrifugeFactoryCommonLogic.createNewCachedRecipe(recipe, cacheIndex, recheckAllRecipeErrors,
				inputHandlers, outputHandlers, errorsChanged, canFunction, setActiveState, hasCreativeUpgrade,
				energyContainer, ticksRequired, markForSave, operationsPerTick, progress, coalesceState);
	}

	/** PB处理时返回PB进度 */
	static double getScaledProgress(int i, int process, @NotNull PbRecipeProcessor pbProcessor,
			@NotNull DoubleSupplier superProgress) {
		return MekCentrifugeFactoryHelper.getScaledProgress(i, process, pbProcessor, superProgress);
	}

	/** 同步PB进度、PB升级数量、AE2 per-tile状态(含过滤模式)和流体槽状态到客户端 */
	static void addContainerTrackers(@NotNull MekanismContainer container, @NotNull PbRecipeProcessor pbProcessor,
			@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate, @NotNull Ae2OutputStateHolder ae2StateHolder,
			@NotNull MultiFluidTankHostDelegate fluidDelegate, @NotNull Runnable superTrackers) {
		// Task 4: DataSlot 索引一致是升级显示正确的前提
		// ME/EME 工厂统一使用 addContainerTrackersWithFilter 注册 Filter Mode,与原版工厂保持一致
		// 原理:Filter Mode 存储于 Ae2OutputStateHolder(IAe2OutputHostBase),不依赖 IAe2InputHost Mixin,客户端/服务端均可访问
		CentrifugeFactoryCommonLogic.addContainerTrackersWithFilter(container, pbProcessor, pbUpgradeDelegate,
				ae2StateHolder, superTrackers);
		// Task 8: 同步流体槽位数(供客户端 GUI 决定是否显示多流体槽 Tab 及动态布局)
		container.track(SyncableInt.create(fluidDelegate::getFluidOutputTankCount, fluidDelegate::setFluidOutputTankCount));
		// Task 8: SyncableBoolean 同步多流体槽模式状态 — 确保客户端 Tab 显示与服务端一致
		container.track(SyncableBoolean.create(() -> fluidDelegate.getFluidOutputHolder() instanceof MultiFluidTankHolder,
				fluidDelegate::setMultiFluidModeSynced));
	}

	/** AE2 流体推送完成后回收空槽并同步槽位数 */
	static void onAe2FluidPushComplete(@NotNull MultiFluidTankHostDelegate fluidDelegate) {
		if (fluidDelegate.getFluidOutputHolder() instanceof MultiFluidTankHolder multiHolder) {
			multiHolder.reclaimEmptyTanks();
			fluidDelegate.setFluidOutputTankCount(multiHolder.getTankCount());
		}
	}

	static boolean containsSmeltingInput(TileEntityEMExtraMekCentrifugeFactory tile, @NotNull Level level,
			@NotNull ItemStack input) {
		return MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(tile)
				&& MekCentrifugeFactoryHelper.containsSmeltingInput(tile.getRecipeType(), level, input);
	}
}
