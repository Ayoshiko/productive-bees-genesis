package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 客户端展示专用物品副本；不连接网络，不改变玩家库存或真实元件。 */
public final class Ae2ContainerFlowerDisplay {
    private Ae2ContainerFlowerDisplay() {}

    public static ItemStack cell(ContainerFlower requirement, HolderLookup.Provider registries) {
        var fluid = requirement.displayFluid();
        AEKey key = null;
        if (fluid != null) key = AEFluidKey.of(BuiltInRegistries.FLUID.get(fluid));
        else if (requirement.displayResource() != null) key = Ae2ResourceKeyBridge.resolve(registries, requirement.displayResource());
        var stack = fluid == null ? AEItems.ITEM_CELL_256K.stack() : AEItems.FLUID_CELL_256K.stack();
        if (key == null) return stack;
        if (key.getType().getId().toString().equals("appmek:chemical")) {
            var id = new ResourceLocation("appmek:chemical_storage_cell_256k");
            if (BuiltInRegistries.ITEM.containsKey(id)) stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        } else if (requirement.displayResource() != null) {
            var id = new ResourceLocation("ae2lt:infinite_storage_cell");
            if (BuiltInRegistries.ITEM.containsKey(id)) stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        }
        var inventory = StorageCells.getCellInventory(stack, null);
        if (inventory != null && inventory.insert(key, requirement.minimumAmount(), Actionable.SIMULATE, IActionSource.empty())
                == requirement.minimumAmount()) {
            long inserted = inventory.insert(key, requirement.minimumAmount(), Actionable.MODULATE, IActionSource.empty());
            if (inserted == requirement.minimumAmount()) inventory.persist();
        }
        return stack;
    }
}
