package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetSection;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import java.util.function.IntConsumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** 未扣料的暂存计划不转移；原进度和 committed pending 的剩余结果原样封存。 */
final class CentrifugeOwnershipAssets implements MachineAssetSection {
	private final TileEntityMekCentrifuge tile;
	private final PbRecipeProcessor processor;
	private final MekCentrifugePbUpgradeHandler upgrades;
	private final IntConsumer progress;
	CentrifugeOwnershipAssets(TileEntityMekCentrifuge tile, PbRecipeProcessor processor, MekCentrifugePbUpgradeHandler upgrades, IntConsumer progress) {
		this.tile = tile; this.processor = processor; this.upgrades = upgrades; this.progress = progress;
	}
	@Override public CompoundTag capture(HolderLookup.Provider registries) {
		var tag = new CompoundTag(); processor.saveAdditional(tag, registries); upgrades.saveCounts(tag); upgrades.saveSlots(tag, registries);
		tag.putInt("nativeProgress", tile.getOperatingTicks()); tag.put("fluid", tile.fluidOutputTank().serializeNBT(registries)); return tag;
	}
	@Override public void restore(CompoundTag tag, HolderLookup.Provider registries) {
		processor.loadAdditional(tag, registries); upgrades.loadCounts(tag); upgrades.loadSlots(tag, registries);
		progress.accept(tag.getInt("nativeProgress")); tile.fluidOutputTank().deserializeNBT(registries, tag.getCompound("fluid"));
	}
	@Override public void clear(HolderLookup.Provider registries) {
		var zero = new CompoundTag();
		zero.putIntArray("productivebeesgenesis_pb_progress", new int[tile.processes()]);
		zero.putByteArray("productivebeesgenesis_pb_processing", new byte[tile.processes()]);
		zero.putIntArray("productivebeesgenesis_pb_processing_time", new int[tile.processes()]);
		zero.putLongArray("productivebeesgenesis_myriad_pending_fluid", new long[tile.processes()]);
		processor.loadAdditional(zero, registries); processor.clearSmeltingCacheAll(); upgrades.loadCounts(zero);
		upgrades.getInputSlot().setStack(ItemStack.EMPTY); upgrades.getOutputSlot().setStack(ItemStack.EMPTY);
		progress.accept(0); tile.fluidOutputTank().setStack(FluidStack.EMPTY);
	}
	@Override public void validateWorld(ServerLevel level) { }
	@Override public boolean empty() {
		for (var type : PbUpgradeType.values()) if (!type.isBuiltin() && tile.getPbUpgradeInstalledCount(type) > 0) return false;
		return tile.getOperatingTicks() == 0 && !processor.hasOwnershipWork() && tile.fluidOutputTank().isEmpty()
				&& upgrades.getInputSlot().isEmpty() && upgrades.getOutputSlot().isEmpty();
	}
}
