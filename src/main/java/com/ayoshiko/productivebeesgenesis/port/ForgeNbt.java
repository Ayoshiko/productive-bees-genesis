package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
public final class ForgeNbt {
 public static CompoundTag save(ItemStack stack,HolderLookup.Provider registries){return stack.save(new CompoundTag());}
 public static CompoundTag save(FluidStack stack,HolderLookup.Provider registries){return stack.writeToNBT(new CompoundTag());}
}
