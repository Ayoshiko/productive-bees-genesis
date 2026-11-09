package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetSection;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
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
	@Override public boolean prepared() {
		var production = tile.productionState();
		if (!tile.pendingCyclesReadable() || tile.getOutputBuffer().hasInvalidData()
				|| tile.getSlotManager().hasInvalidBeeData() || tile.hasInvalidFluidData()
				|| tile.upgradeRecovery().blocked()
				|| production.suspended() || production.hasFrozen()
				|| production.retained() != null && production.retained().total() > 0) return false;
		var slots = tile.getSlotManager().getBeeSlots();
		var counts = production.counts;
		boolean effects = tile.getPbUpgradeInstalledCount(PbUpgradeType.GENE_SAMPLER) > 0
				|| tile.getPbUpgradeInstalledCount(PbUpgradeType.ESSENCE_CONVERSION) > 0
				|| tile.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0;
		for (int index = 0; index < slots.length; index++) {
			var slot = slots[index];
			if (!slot.isEmpty() && tile.getLevel() instanceof ServerLevel level
					&& ResourceRuntime.pbCatalog(level).get(BeeNbtHelper.resolveBeeTypeKey(slot.getBeeData())) != null) return false;
			if (counts[index] > 0 && (slot.isEmpty() || !production.canAdvance(index, slot)
					|| effects || DynamicBeeAdapter.generator(BeeNbtHelper.resolveBeeTypeKey(slot.getBeeData()))
					!= com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGenerator.STATIC)) return false;
		}
		return true;
	}
	@Override public CompoundTag capture(HolderLookup.Provider registries) {
		if (!tile.pendingCyclesReadable()) throw new IllegalStateException("Unreadable apiary pending cycles");
		if (!prepared()) throw new IllegalStateException("Settle independent apiary production before joining");
		var tag = new CompoundTag();
		tile.nbtSerializer().saveApiaryState(tag, registries);
		// 封存周期交给网络唯一所有者，残余缓冲不再保留第二份生产快照。
		tag.getCompound(ApiaryOutputBuffer.nbtKey()).remove("production");
		tile.tickHandler.saveOwnershipCycles(tag);
		var restock = tile.getGeneTreatRestock();
		// 默认补货状态用缺省字段表示，保持 1.0.8-hotfix 网络资产映像及回执指纹兼容。
		if (!restock.isEnabled() && !restock.isSuspended() && !restock.hasPending() && restock.template().isEmpty()) {
			tag.remove(GeneTreatRestockState.NBT_KEY);
		}
		return tag;
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
		tile.getOutputBuffer().load(registries, null);
		tile.clearPendingHoneyFluid();
		tile.tickHandler.clearTransferredCycles();
		tile.getGeneTreatRestock().clearAfterTransfer();
	}
	@Override public void validateWorld(ServerLevel level) {
		for (var slot : tile.getSlotManager().getBeeSlots()) {
			if (slot.isEmpty()) continue;
			var type = BeeNbtHelper.resolveEntityType(slot.getBeeData());
			if (type == null || !(type.create(level) instanceof Bee)) throw new IllegalArgumentException("Unknown or non-bee occupant");
			var key = BeeNbtHelper.resolveBeeTypeKey(slot.getBeeData());
			if (ResourceRuntime.pbCatalog(level).get(key) != null) throw new IllegalArgumentException("Resource bee requires independent production");
			if (key == null || type == cy.jdkdigital.productivebees.init.ModEntities.CONFIGURABLE_BEE.get()
					&& cy.jdkdigital.productivebees.setup.BeeReloadListener.INSTANCE.getData(key) == null) throw new IllegalArgumentException("Unknown bee type");
		}
	}
	@Override public boolean empty() {
		var restock = tile.getGeneTreatRestock();
		if (restock.hasPending() || restock.isSuspended() || restock.isEnabled() || !restock.template().isEmpty()) return false;
		for (var slot : tile.getSlotManager().getBeeSlots()) if (!slot.isEmpty()) return false;
		for (var slot : tile.feederSlotManager.getFeederInventorySlots()) if (!slot.isEmpty()) return false;
		for (var type : PbUpgradeType.values()) if (!type.isBuiltin() && tile.getPbUpgradeInstalledCount(type) > 0) return false;
		var production = tile.productionState();
		return tile.pendingCyclesReadable() && !tile.getOutputBuffer().hasInvalidData()
				&& !tile.getSlotManager().hasInvalidBeeData() && !tile.hasInvalidFluidData()
				&& !tile.upgradeRecovery().blocked()
				&& !production.suspended() && !production.hasFrozen() && production.total() == 0 && tile.getPbUpgradeInputSlot().isEmpty() && tile.getPbUpgradeOutputSlot().isEmpty()
				&& tile.getFluidTank().isEmpty() && tile.getOutputBuffer().getBufferedGroupCount() == 0 && tile.getPendingHoneyFluidAmount() == 0;
	}
}
