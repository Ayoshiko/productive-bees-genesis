package com.ayoshiko.productivebeesgenesis.inventory;

import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("minecraft")
class SlotLimitCacheMinecraftTest {

    @Test
    void effectiveCacheRejectsChangedOrRemovedLimitAndAcceptsRestoredDefault() {
        SlotLimitCache cache = new SlotLimitCache();
        ItemStack stack = new ItemStack(Items.STONE);
        cache.storeEffectiveLimit(stack, 256);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        assertEquals(256, cache.peekEffectiveLimit(stack));

        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        assertEquals(-1, cache.peekEffectiveLimit(stack));
        assertEquals(16, cache.getBaseLimit(stack, 64, true, 4));
        cache.storeEffectiveLimit(stack, 64);
        assertEquals(-1, cache.peekEffectiveLimit(stack));

        stack.remove(DataComponents.MAX_STACK_SIZE);
        assertEquals(-1, cache.peekEffectiveLimit(stack));
        assertEquals(stack.getMaxStackSize(), cache.getBaseLimit(stack, 64, true, 4));

        stack.set(DataComponents.MAX_STACK_SIZE, 64);
        assertEquals(256, cache.peekEffectiveLimit(stack));
        cache.invalidate();
        assertEquals(-1, cache.peekEffectiveLimit(stack));
    }

    @Test
    void actualTieredSlotsFollowLiveLimitComponentsAndMultiplierVersion() {
        int[] multiplier = {4};
        BasicInventorySlot input = BasicInventorySlot.at(null, 0, 0);
        ((TieredInputSlot) input).productivebeesgenesis$setInputStackMultiplier(() -> multiplier[0]);
        TieredOutputInventorySlot output = TieredOutputInventorySlot.at(() -> multiplier[0], null, 0, 0);
        ItemStack stack = new ItemStack(Items.STONE);
        try {
            assertEquals(256, input.getLimit(stack));
            assertEquals(256, output.getLimit(stack));
            stack.set(DataComponents.MAX_STACK_SIZE, 16);
            assertEquals(64, input.getLimit(stack));
            assertEquals(64, output.getLimit(stack));
            stack.remove(DataComponents.MAX_STACK_SIZE);
            int removedLimit = stack.getMaxStackSize() * multiplier[0];
            assertEquals(removedLimit, input.getLimit(stack));
            assertEquals(removedLimit, output.getLimit(stack));
            stack.set(DataComponents.MAX_STACK_SIZE, 64);
            assertEquals(256, input.getLimit(stack));
            assertEquals(256, output.getLimit(stack));

            multiplier[0] = 2;
            TieredInputSlot.MULTIPLIER_VERSION.incrementAndGet();
            assertEquals(128, input.getLimit(stack));
            assertEquals(128, output.getLimit(stack));
            stack.set(DataComponents.MAX_STACK_SIZE, 32);
            assertEquals(64, input.getLimit(stack));
            assertEquals(64, output.getLimit(stack));
        } finally {
            TieredInputSlot.MULTIPLIER_VERSION.incrementAndGet();
        }
    }
}
