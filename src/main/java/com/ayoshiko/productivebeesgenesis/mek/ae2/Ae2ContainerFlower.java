package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower;
import net.minecraft.core.registries.BuiltInRegistries;
import appeng.api.storage.StorageCells;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** 仅由 AE2 加载守卫调用；只查询独立物品副本，不连接 ME 网络，不接受包裹模板。 */
public final class Ae2ContainerFlower {
    private Ae2ContainerFlower() {}
    public static boolean matchesRequirement(ItemStack snapshot, HolderLookup.Provider registries,
            ContainerFlower requirement) {
        if (snapshot.isEmpty() || GenericStack.isWrapped(snapshot)) return false;
        var fluid = requirement.displayFluid();
        AEKey key = null;
        if (fluid != null) key = AEFluidKey.of(BuiltInRegistries.FLUID.get(fluid));
        else if (requirement.displayResource() != null) key = Ae2ResourceKeyBridge.resolve(registries, requirement.displayResource());
        if (key == null) return false;
        var cell = StorageCells.getCellInventory(snapshot, null);
        return cell != null && cell.extract(key, requirement.minimumAmount(), Actionable.SIMULATE, IActionSource.empty())
                == requirement.minimumAmount();
    }
    public static boolean matches(ItemStack snapshot, HolderLookup.Provider registries, ResourceKey resource, long amount) {
        if (snapshot.isEmpty() || GenericStack.isWrapped(snapshot)) return false;
        var key = Ae2GenericResourceBridge.resolve(registries, resource);
        if (key == null) return false;
        var cell = StorageCells.getCellInventory(snapshot, null);
        if (cell == null) return false;
        // 单键模拟查询避免逐次枚举元件内全部种类；不得调用 MODULATE 或 persist。
        long available = cell.extract(key, amount, Actionable.SIMULATE, IActionSource.empty());
        return available == amount;
    }
}
