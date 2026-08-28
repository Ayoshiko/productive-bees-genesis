package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeFluidTankMultipliers;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityFactoryAccessor;
import java.util.function.IntSupplier;
import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor;
import mekanism.common.tier.FactoryTier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * Initial inventory/fluid-tank assembly, smelting-compat cache invalidation,
	 * multi-fluid mode sync and fluid push lifecycle support,
	 * split from {@link AbstractMekCentrifugeFactory} (SRP).
	 */
final class AbstractMekCentrifugeFactorySetupHelper {

	private AbstractMekCentrifugeFactorySetupHelper() {
	}

	/** Bridge for the superclass-protected {@code addSlots} method (declared in Mekanism's TileEntityFactory). */
	@FunctionalInterface
	interface SlotSetup {
		void setup(InventorySlotHelper builder, IContentsListener listener, IContentsListener updateSortingListener);
	}

	static void onSmeltingCompatChanged(@NotNull AbstractMekCentrifugeFactory factory, int processes,
			@NotNull RecipeCacheLookupMonitor<?>[] monitors) {
		factory.validInputCache.clear();
		factory.inputProducesOutputCache.clear();
		boolean smeltingEnabled = MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(factory);
		for (int i = 0; i < processes; i++) {
			factory.pbProcessor.resetSmeltingCache(i);
			if (smeltingEnabled) MekCentrifugeFactoryHelper.invalidateRecipeMonitor(monitors[i]);
		}
	}

	/** 重写getInitialInventory — 调整energySlot位置（原版4等级保持(7,13)，EM高等级复刻EM原版公式） */
	@NotNull
	static IInventorySlotHolder createInitialInventory(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull IContentsListener listener, @NotNull FactoryTier tier,
			@NotNull MachineEnergyContainer<?> energyContainer, @NotNull SlotSetup addSlots) {
		InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(factory);
		TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) factory;
		addSlots.setup(builder, listener, () -> {
			listener.onContentsChanged();
			accessor.productivebeesgenesis$setSortingNeeded(true);
		});
		int energySlotX = FactoryLayoutHelper.getFactoryEnergySlotX(tier);
		int energySlotY = FactoryLayoutHelper.getFactoryEnergySlotY(tier);
		EnergyInventorySlot energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, factory::getLevel,
				listener, energySlotX,
				energySlotY);
		accessor.productivebeesgenesis$setEnergySlot(energySlot);
		builder.addSlot(energySlot);
		return builder.build();
	}

	/** 重写getInitialFluidTanks — 添加共享流体输出槽，容量随tier.processes和tier倍率缩放 */
	@Nullable
	static IFluidTankHolder createInitialFluidTanks(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull IContentsListener listener, @NotNull FactoryTier tier) {
		// 判断是否为 EM 工厂（EM 通过 Mixin 扩展 FactoryTier 枚举，ordinal >= 4）
		// 原版工厂 ordinal 0-3 走 forVanillaFactory，EM 工厂 ordinal 4-8 走 forEMFactory（传入相对序号 ordinal-4）
		boolean isEMFactory = tier.ordinal() >= 4
				&& com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks.isEvolvedMekanismLoaded();
		IntSupplier fluidTankMultiplier = isEMFactory
				? com.ayoshiko.productivebeesgenesis.inventory.CentrifugeFluidTankMultipliers.forEMFactory(tier.ordinal() - 4)
				: com.ayoshiko.productivebeesgenesis.inventory.CentrifugeFluidTankMultipliers.forVanillaFactory(tier.ordinal());
		// Task 5: 返回的 IFluidTankHolder 通过 setupFluidOutputConfig 暴露给 MEK 原生侧面配置 GUI
		// Task 1: tankCountSetter 构造时设置 fluidOutputTankCount(MULTI=maxTanks,SINGLE=1),避免 Tab 窗口过窄
		Level level = factory.productivebeesgenesis$getAe2Level();
		return factory.fluidOutputHolder =
				MekCentrifugeFactoryHelper.createFluidOutputHolder(factory, listener, tier.processes,
				fluidTankMultiplier, level != null && level.isClientSide(),
				t -> factory.fluidOutputTank = t,
				c -> factory.fluidOutputTankCount = c);
	}

	/** 只查SMELTING配方，PB配方由tryProcessPbRecipe独立处理 */
	@Nullable
	static ItemStackToItemStackRecipe findRecipe(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull ItemStack fallbackInput, @NotNull IInventorySlot outputSlot) {
		return MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(factory)
				? MekCentrifugeFactoryHelper.findSmeltingRecipe(factory.getRecipeType(),
						factory.productivebeesgenesis$getAe2Level(), fallbackInput, outputSlot)
				: null;
	}

	/** 同步PB进度、PB升级数量、AE2 per-tile状态(含输入过滤模式)、流体槽位数和多流体槽模式到客户端 */
	static void addContainerTrackers(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull MekanismContainer container, @NotNull Runnable superCall) {
		// Task 3: DataSlot off-by-one 诊断优先;本方法使用 addContainerTrackersWithFilter(含 Filter Mode),与 ME/EME 路径不同
		CentrifugeFactoryCommonLogic.addContainerTrackersWithFilter(container, factory.pbProcessor,
				factory.pbUpgradeDelegate, factory.productivebeesgenesis$getAe2StateHolder(),
				superCall);
		// Task 8: 同步流体槽位数(供客户端 GUI 决定是否显示多流体槽 Tab 及动态布局)
		container.track(SyncableInt.create(() -> factory.fluidOutputTankCount, v -> factory.fluidOutputTankCount = v));
		// Task 8: SyncableBoolean 同步多流体槽模式状态,确保客户端 Tab 显示与服务端一致
		container.track(SyncableBoolean.create(() -> factory.fluidOutputHolder instanceof MultiFluidTankHolder,
			v -> factory.isMultiFluidModeSynced = v));
	}

	/** Task 3/8: 是否启用多流体槽模式 — 基于模式而非槽位数判断,客户端使用 SyncableBoolean 同步值确保 Tab 显示一致 */
	static boolean isMultiFluidMode(@NotNull AbstractMekCentrifugeFactory factory) {
		Level level = factory.productivebeesgenesis$getAe2Level();
		// 客户端:返回 SyncableBoolean 同步值(确保 Tab 显示与服务端一致)
		if (level != null && level.isClientSide()) {
			return factory.isMultiFluidModeSynced;
		}
		// 服务端或构造期(level 为 null):基于 holder 类型判断
		return MultiFluidTankHostHelper.isMultiFluidMode(factory.fluidOutputHolder);
	}

	static void onAe2FluidPushComplete(@NotNull AbstractMekCentrifugeFactory factory) {
		if (factory.fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
			multiHolder.reclaimEmptyTanks();
			factory.fluidOutputTankCount = multiHolder.getTankCount();
		}
	}
}
