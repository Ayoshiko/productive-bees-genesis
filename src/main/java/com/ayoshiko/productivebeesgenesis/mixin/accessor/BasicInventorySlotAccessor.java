package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = BasicInventorySlot.class, remap = false)
public interface BasicInventorySlotAccessor {
    @org.spongepowered.asm.mixin.gen.Accessor("limit") int productivebeesgenesis$getLimit();
    @org.spongepowered.asm.mixin.gen.Accessor("obeyStackLimit") boolean productivebeesgenesis$getObeyStackLimit();
    @org.spongepowered.asm.mixin.gen.Accessor("obeyStackLimit") void productivebeesgenesis$setObeyStackLimit(boolean value);
    @Invoker("setStackUnchecked") void productivebeesgenesis$setStackUnchecked(ItemStack stack);
}
