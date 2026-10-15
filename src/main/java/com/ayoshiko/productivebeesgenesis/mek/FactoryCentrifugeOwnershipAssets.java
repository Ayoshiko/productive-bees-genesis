package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetSection;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import java.util.Arrays;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

/** 原槽和已生成结果只存在于封存映像；新网络作业不消费这些保管资产。 */
final class FactoryCentrifugeOwnershipAssets implements MachineAssetSection {
	private final AbstractMekCentrifugeFactory tile;
	private final int[] progress;
	FactoryCentrifugeOwnershipAssets(AbstractMekCentrifugeFactory tile, int[] progress) {
		this.tile = tile; this.progress = progress;
	}
	@Override public boolean prepared() {
		// 旧独立周期没有固定的并行数与费用快照，必须在冻结前由原机结清。
		for (int value : progress) if (value != 0) return false;
		return !tile.pbProcessor.hasOwnershipProgress();
	}
	@Override public CompoundTag capture(HolderLookup.Provider registries) {
		var tag = new CompoundTag();
		tag.putInt("factoryProcesses", tile.processes()); tag.putIntArray("nativeProgress", progress.clone());
		tile.pbProcessor.saveAdditional(tag, registries); tile.pbUpgradeDelegate.save(tag, registries);
		if (tile.fluidOutputHolder instanceof MultiFluidTankHolder multi) multi.writeToNBT(tag, registries);
		else tag.put("fluid", tile.fluidOutputTank.serializeNBT(registries));
		var orphan = tile.getOrphanedMultiFluidTanksNbt();
		if (orphan != null) tag.put("orphanedMultiFluidTanks", orphan.copy());
		return tag;
	}
	@Override public void restore(CompoundTag tag, HolderLookup.Provider registries) {
		var nativeProgress = tag.getIntArray("nativeProgress");
		if (tag.getInt("factoryProcesses") != tile.processes() || nativeProgress.length != progress.length)
			throw new IllegalArgumentException("Different centrifuge factory layout");
		tile.pbProcessor.loadAdditional(tag, registries); tile.pbUpgradeDelegate.load(tag, registries);
		System.arraycopy(nativeProgress, 0, progress, 0, progress.length);
		if (tile.fluidOutputHolder instanceof MultiFluidTankHolder multi) multi.readFromNBT(tag, registries);
		else tile.fluidOutputTank.deserializeNBT(registries, tag.getCompound("fluid"));
		tile.setOrphanedMultiFluidTanksNbt(tag.contains("orphanedMultiFluidTanks", 10)
				? tag.getCompound("orphanedMultiFluidTanks").copy() : null);
	}
	@Override public void clear(HolderLookup.Provider registries) {
		var zero = new CompoundTag();
		zero.putIntArray("productivebeesgenesis_pb_progress", new int[tile.processes()]);
		zero.putByteArray("productivebeesgenesis_pb_processing", new byte[tile.processes()]);
		zero.putIntArray("productivebeesgenesis_pb_processing_time", new int[tile.processes()]);
		zero.putLongArray("productivebeesgenesis_myriad_pending_fluid", new long[tile.processes()]);
		tile.pbProcessor.loadAdditional(zero, registries); tile.pbProcessor.clearSmeltingCacheAll();
		tile.pbUpgradeDelegate.load(zero, registries); Arrays.fill(progress, 0);
		tile.pbUpgradeDelegate.getPbUpgradeInputSlot().setStack(net.minecraft.world.item.ItemStack.EMPTY);
		tile.pbUpgradeDelegate.getPbUpgradeOutputSlot().setStack(net.minecraft.world.item.ItemStack.EMPTY);
		if (tile.fluidOutputHolder instanceof MultiFluidTankHolder multi) {
			zero.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, new CompoundTag());
			multi.readFromNBT(zero, registries);
		} else tile.fluidOutputTank.setStack(FluidStack.EMPTY);
		tile.setOrphanedMultiFluidTanksNbt(null);
	}
	@Override public void validateWorld(ServerLevel level) { }
	@Override public boolean empty() {
		if (tile.pbProcessor.hasOwnershipWork() || tile.getOrphanedMultiFluidTanksNbt() != null) return false;
		for (int value : progress) if (value != 0) return false;
		for (int value : tile.pbUpgradeDelegate.getPbUpgradeCounts().values()) if (value != 0) return false;
		if (!tile.pbUpgradeDelegate.getPbUpgradeInputSlot().isEmpty() || !tile.pbUpgradeDelegate.getPbUpgradeOutputSlot().isEmpty()) return false;
		for (int i = 0; i < tile.fluidOutputTankCount(); i++) if (!tile.fluidOutputTank(i).isEmpty()) return false;
		return true;
	}
}
