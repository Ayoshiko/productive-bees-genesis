package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Read-only normalized views and immutable-by-ownership snapshots for NBT keys. */
public final class StackTags {
    private static final CompoundTag EMPTY = new CompoundTag();
    private StackTags() {}
    public static CompoundTag view(ItemStack stack) { return stack.hasTag() ? stack.getTag() : EMPTY; }
    public static CompoundTag view(FluidStack stack) { return stack.hasTag() ? stack.getTag() : EMPTY; }
    public static CompoundTag snapshot(ItemStack stack) { return view(stack).copy(); }
    public static int hash(ItemStack stack) { return view(stack).hashCode(); }
    public static int hash(FluidStack stack) { return view(stack).hashCode(); }
}
