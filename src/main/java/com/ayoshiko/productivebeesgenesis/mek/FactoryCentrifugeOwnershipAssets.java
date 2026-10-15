package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetSection;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import java.util.Arrays;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

/** 原槽和已生成结果只存在于封存映像；新网络作业不消费这些保管资产。 */
public final class FactoryCentrifugeOwnershipAssets implements MachineAssetSection {
	private final PbRecipeContext tile;
	private final IMultiFluidTankHost fluidHost;
	private final PbRecipeProcessor processor;
	private final FactoryPbUpgradeDelegate upgrades;
	private final IFluidTankHolder fluids;
	private final int[] progress;
	public FactoryCentrifugeOwnershipAssets(PbRecipeContext tile, PbRecipeProcessor processor,
			FactoryPbUpgradeDelegate upgrades, IFluidTankHolder fluids, int[] progress) {
		this.tile = tile;
		this.fluidHost = (IMultiFluidTankHost) tile;
		this.processor = processor;
		this.upgrades = upgrades;
		this.fluids = fluids;
		this.progress = progress;
	}
	@Override public boolean prepared() {
		// 旧独立周期没有固定的并行数与费用快照，必须在冻结前由原机结清。
		for (int value : progress) if (value != 0) return false;
		return !processor.hasOwnershipProgress();
	}
	@Override public CompoundTag capture(HolderLookup.Provider registries) {
		var tag = new CompoundTag();
		tag.putInt("factoryProcesses", tile.processes());
		tag.putIntArray("nativeProgress", progress.clone());
		processor.saveAdditional(tag, registries);
		upgrades.save(tag, registries);
		if (fluids instanceof MultiFluidTankHolder multi) multi.writeToNBT(tag, registries);
		else tag.put("fluid", tile.fluidOutputTank().serializeNBT(registries));
		var orphan = fluidHost.getOrphanedMultiFluidTanksNbt();
		if (orphan != null) tag.put("orphanedMultiFluidTanks", orphan.copy());
		return tag;
	}
	@Override public void restore(CompoundTag tag, HolderLookup.Provider registries) {
		var nativeProgress = tag.getIntArray("nativeProgress");
		if (tag.getInt("factoryProcesses") != tile.processes() || nativeProgress.length != progress.length)
			throw new IllegalArgumentException("Different centrifuge factory layout");
		processor.loadAdditional(tag, registries);
		upgrades.load(tag, registries);
		System.arraycopy(nativeProgress, 0, progress, 0, progress.length);
		if (fluids instanceof MultiFluidTankHolder multi) multi.readFromNBT(tag, registries);
		else tile.fluidOutputTank().deserializeNBT(registries, tag.getCompound("fluid"));
		fluidHost.setOrphanedMultiFluidTanksNbt(tag.contains("orphanedMultiFluidTanks", 10)
				? tag.getCompound("orphanedMultiFluidTanks").copy() : null);
	}
	@Override public void clear(HolderLookup.Provider registries) {
		var zero = new CompoundTag();
		zero.putIntArray("productivebeesgenesis_pb_progress", new int[tile.processes()]);
		zero.putByteArray("productivebeesgenesis_pb_processing", new byte[tile.processes()]);
		zero.putIntArray("productivebeesgenesis_pb_processing_time", new int[tile.processes()]);
		zero.putLongArray("productivebeesgenesis_myriad_pending_fluid", new long[tile.processes()]);
		processor.loadAdditional(zero, registries);
		processor.clearSmeltingCacheAll();
		upgrades.load(zero, registries);
		Arrays.fill(progress, 0);
		upgrades.getPbUpgradeInputSlot().setStack(net.minecraft.world.item.ItemStack.EMPTY);
		upgrades.getPbUpgradeOutputSlot().setStack(net.minecraft.world.item.ItemStack.EMPTY);
		if (fluids instanceof MultiFluidTankHolder multi) {
			zero.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, new CompoundTag());
			multi.readFromNBT(zero, registries);
		} else tile.fluidOutputTank().setStack(FluidStack.EMPTY);
		fluidHost.setOrphanedMultiFluidTanksNbt(null);
	}
	@Override public void validateWorld(ServerLevel level) { }
	@Override public boolean empty() {
		if (processor.hasOwnershipWork() || fluidHost.getOrphanedMultiFluidTanksNbt() != null) return false;
		for (int value : progress) if (value != 0) return false;
		for (int value : upgrades.getPbUpgradeCounts().values()) if (value != 0) return false;
		if (!upgrades.getPbUpgradeInputSlot().isEmpty() || !upgrades.getPbUpgradeOutputSlot().isEmpty()) return false;
		for (int i = 0; i < tile.fluidOutputTankCount(); i++) if (!tile.fluidOutputTank(i).isEmpty()) return false;
		return true;
	}
}
