package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetSection;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** 复用蜂箱序列化语义，清空时覆盖全部独立持有者，不调用会吞异常的掉落清理。 */
public final class ApiaryOwnershipAssets implements MachineAssetSection {
	private final TileEntityMekApiary tile;
	public ApiaryOwnershipAssets(TileEntityMekApiary tile) { this.tile = tile; }
	@Override public CompoundTag capture(HolderLookup.Provider registries) {
		if (!tile.pendingCyclesReadable()) throw new IllegalStateException("Unreadable apiary pending cycles");
		var tag = new CompoundTag(); tile.nbtSerializer().saveApiaryState(tag, registries); tile.tickHandler.savePendingCycles(tag); return tag;
	}
	@Override public void restore(CompoundTag tag, HolderLookup.Provider registries) {
		tile.nbtSerializer().loadApiaryState(tag, registries); tile.tickHandler.loadPendingCycles(tag);
		if (!tile.pendingCyclesReadable()) throw new IllegalArgumentException("Cannot restore apiary pending cycles");
	}
	@Override public void clear(HolderLookup.Provider registries) {
		for (var slot : tile.getSlotManager().getBeeSlots()) slot.clear();
		for (var slot : tile.feederSlotManager.getFeederInventorySlots()) slot.setStack(ItemStack.EMPTY);
		tile.pbUpgradeHandler.getInputSlot().setStack(ItemStack.EMPTY); tile.pbUpgradeHandler.getOutputSlot().setStack(ItemStack.EMPTY);
		tile.pbUpgradeHandler.loadPbUpgradeCounts(new CompoundTag(), registries); tile.getFluidTank().setStack(FluidStack.EMPTY);
		tile.getOutputBuffer().clear(); tile.clearPendingHoneyFluid(); tile.tickHandler.clearTransferredCycles();
	}
	@Override public void validateWorld(ServerLevel level) {
		for (var slot : tile.getSlotManager().getBeeSlots()) {
			if (slot.isEmpty()) continue;
			var type = BeeNbtHelper.resolveEntityType(slot.getBeeData());
			if (type == null || !(type.create(level) instanceof Bee)) throw new IllegalArgumentException("Unknown or non-bee occupant");
			var key = BeeNbtHelper.resolveBeeTypeKey(slot.getBeeData());
			if (key == null || type == cy.jdkdigital.productivebees.init.ModEntities.CONFIGURABLE_BEE.get()
					&& cy.jdkdigital.productivebees.setup.BeeReloadListener.INSTANCE.getData(key) == null) throw new IllegalArgumentException("Unknown bee type");
		}
	}
	@Override public boolean empty() {
		for (var slot : tile.getSlotManager().getBeeSlots()) if (!slot.isEmpty()) return false;
		for (var slot : tile.feederSlotManager.getFeederInventorySlots()) if (!slot.isEmpty()) return false;
		for (var type : PbUpgradeType.values()) if (!type.isBuiltin() && tile.getPbUpgradeInstalledCount(type) > 0) return false;
		var pending = new CompoundTag(); tile.tickHandler.savePendingCycles(pending);
		return pending.isEmpty() && tile.getPbUpgradeInputSlot().isEmpty() && tile.getPbUpgradeOutputSlot().isEmpty()
				&& tile.getFluidTank().isEmpty() && tile.getOutputBuffer().getBufferedGroupCount() == 0 && tile.getPendingHoneyFluidAmount() == 0;
	}
}
