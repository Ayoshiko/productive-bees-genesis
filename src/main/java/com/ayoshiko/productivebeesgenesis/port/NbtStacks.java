package com.ayoshiko.productivebeesgenesis.port;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;

/** Immutable cache snapshots and native item tag comparisons on Minecraft 1.20.1. */
public final class NbtStacks {
    private NbtStacks() {}
    public static CompoundTag snapshot(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().copy() : new CompoundTag();
    }
    public static int hash(ItemStack stack) {
        return 31 * stack.getItem().hashCode() + Objects.hashCode(stack.getTag());
    }
    public static int size(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().size() : 0;
    }
    public static boolean hasOnlyBeeType(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag().size() != 1) return false;
        CompoundTag entity = stack.getTagElement("EntityTag");
        return entity != null && entity.size() == 1 && entity.contains("type", 8);
    }
}
