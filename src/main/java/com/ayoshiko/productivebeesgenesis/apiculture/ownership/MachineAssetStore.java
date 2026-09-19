package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryOwnershipAssets;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** 有限物理库存的迁移保管映像；所有权协议由外层服务负责，预检副本不加入世界。 */
public final class MachineAssetStore {
	private final TileEntityMekanism tile;
	private final IAe2OutputHostBase host;
	private final MachineAssetSection section;
	public static boolean supports(TileEntityMekanism tile) { return tile.getClass() == TileEntityMekApiary.class || tile.getClass() == TileEntityMekCentrifuge.class; }
	public MachineAssetStore(TileEntityMekanism tile) {
		this.tile = tile;
		// 工厂的多罐和等级升级矩阵在对应适配器验收后扩展，不能继承基础机假装支持。
		if (tile.getClass() == TileEntityMekApiary.class) section = new ApiaryOwnershipAssets((TileEntityMekApiary) tile);
		else if (tile.getClass() == TileEntityMekCentrifuge.class) section = ((TileEntityMekCentrifuge) tile).ownershipAssets();
		else throw new IllegalArgumentException("No verified ownership asset adapter for " + tile.getClass().getName());
		host = (IAe2OutputHostBase) tile;
	}
	public boolean prepared() { return host.productivebeesgenesis$getAe2StateHolder().getOutputLedger().size() == 0; }
	public AssetImage capture(HolderLookup.Provider registries) {
		if (!prepared()) throw new IllegalStateException("Outstanding external output settlement");
		var tag = new CompoundTag(); tag.putInt("schema", 1); tag.putString("machine", BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).toString());
		var items = new ListTag(); for (var slot : tile.getInventorySlots(null)) items.add(slot.serializeNBT(registries)); tag.put("items", items);
		tag.put("upgrades", tile.getComponent().serialize(registries)); tag.putLong("energy", host.energyContainer().getEnergy());
		tag.putLong("energyCapacity", host.energyContainer().getMaxEnergy()); tag.put("extra", section.capture(registries));
		var pending = new CompoundTag(); host.productivebeesgenesis$getAe2StateHolder().savePendingItems(pending); tag.put("pendingExternal", pending);
		return new AssetImage(tag);
	}
	public void validate(AssetImage image, ServerLevel level) {
		var duplicate = tile.getType().create(tile.getBlockPos(), tile.getBlockState());
		if (!(duplicate instanceof TileEntityMekanism machine) || duplicate.getLevel() != null) throw new IllegalStateException("Cannot create detached validation target");
		var candidate = new MachineAssetStore(machine); candidate.restoreUnchecked(image, level.registryAccess());
		candidate.section.validateWorld(level);
		for (var slot : machine.getInventorySlots(null)) if (!slot.isEmpty() && slot.getCount() > slot.getLimit(slot.getStack())) throw new IllegalArgumentException("Inventory exceeds physical slot capacity");
		for (var upgrade : Upgrade.values()) if (machine.getComponent().getUpgrades(upgrade) > 0 && !machine.getComponent().supports(upgrade)) throw new IllegalArgumentException("Unsupported native upgrade");
		if (!image.equals(candidate.capture(level.registryAccess()))) throw new IllegalArgumentException("Asset data cannot round-trip on the current machine and registries");
	}
	public void clear(HolderLookup.Provider registries) {
		section.clear(registries);
		for (var slot : tile.getInventorySlots(null)) slot.setStack(ItemStack.EMPTY);
		tile.getComponent().getUpgradeSlot().setStack(ItemStack.EMPTY); tile.getComponent().getUpgradeOutputSlot().setStack(ItemStack.EMPTY);
		tile.getComponent().deserialize(new CompoundTag(), registries); host.energyContainer().setEnergy(0);
		host.productivebeesgenesis$getAe2StateHolder().loadPendingItems(new CompoundTag());
		tile.setChanged();
		if (!empty()) throw new IllegalStateException("Physical source did not clear completely");
	}
	public boolean empty() {
		if (host.energyContainer().getEnergy() != 0 || !section.empty()) return false;
		for (var slot : tile.getInventorySlots(null)) if (!slot.isEmpty()) return false;
		for (var upgrade : Upgrade.values()) if (tile.getComponent().getUpgrades(upgrade) != 0) return false;
		var state = host.productivebeesgenesis$getAe2StateHolder();
		return tile.getComponent().getUpgradeSlot().isEmpty() && tile.getComponent().getUpgradeOutputSlot().isEmpty()
				&& state.getPendingItemBuffer().size() == 0 && state.getOutputLedger().size() == 0;
	}
	public void restore(AssetImage image, ServerLevel level) {
		if (!empty()) throw new IllegalStateException("Return target is not empty");
		validate(image, level); restoreUnchecked(image, level.registryAccess()); tile.setChanged();
	}
	private void restoreUnchecked(AssetImage image, HolderLookup.Provider registries) {
		var tag = image.copy();
		if (!tag.contains("schema", Tag.TAG_INT) || tag.getInt("schema") != 1 || tag.getAllKeys().size() != 8
				|| !tag.contains("machine", Tag.TAG_STRING) || !tag.getString("machine").equals(BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).toString())
				|| !tag.contains("items", Tag.TAG_LIST) || !tag.contains("upgrades", Tag.TAG_COMPOUND) || !tag.contains("extra", Tag.TAG_COMPOUND)
				|| !tag.contains("pendingExternal", Tag.TAG_COMPOUND) || !tag.contains("energy", Tag.TAG_LONG) || !tag.contains("energyCapacity", Tag.TAG_LONG)) throw new IllegalArgumentException("Invalid machine asset schema");
		var items = tag.getList("items", Tag.TAG_COMPOUND); var slots = tile.getInventorySlots(null);
		if (items.size() != slots.size()) throw new IllegalArgumentException("Different physical inventory layout");
		tile.getComponent().deserialize(tag.getCompound("upgrades"), registries);
		section.restore(tag.getCompound("extra"), registries);
		for (int i = 0; i < slots.size(); i++) slots.get(i).deserializeNBT(registries, items.getCompound(i));
		long energy = tag.getLong("energy");
		if (energy < 0 || energy > host.energyContainer().getMaxEnergy()) throw new IllegalArgumentException("Insufficient return energy capacity");
		host.energyContainer().setEnergy(energy); host.productivebeesgenesis$getAe2StateHolder().loadPendingItems(tag.getCompound("pendingExternal"));
	}
}
