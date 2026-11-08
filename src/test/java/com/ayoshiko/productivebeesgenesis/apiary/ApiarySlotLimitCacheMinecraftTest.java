package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("minecraft")
class ApiarySlotLimitCacheMinecraftTest {
    @Test
    void publicInvalidationRefreshesBothLegallyChangedSlots() {
        BasicInventorySlot first = BasicInventorySlot.at(null, 0, 0);
        BasicInventorySlot second = BasicInventorySlot.at(null, 1, 0);
        ItemStack firstStack = new ItemStack(Items.STONE);
        ItemStack secondStack = new ItemStack(Items.COBBLESTONE);
        first.setStack(firstStack);
        second.setStack(secondStack);
        firstStack = first.getStack();
        secondStack = second.getStack();
        SlotLimitCache cache = new SlotLimitCache(2);
        int originalFirst = cache.getCachedSlotLimit(0, first, firstStack);
        int originalSecond = cache.getCachedSlotLimit(1, second, secondStack);

        firstStack.set(DataComponents.MAX_STACK_SIZE, 32);
        secondStack.set(DataComponents.MAX_STACK_SIZE, 16);
        int expectedFirst = first.getLimit(firstStack);
        int expectedSecond = second.getLimit(secondStack);
        assertNotEquals(originalFirst, expectedFirst);
        assertNotEquals(originalSecond, expectedSecond);

        TileEntityMekApiary.invalidateSlotManagerCache();
        assertEquals(expectedFirst, cache.getCachedSlotLimit(0, first, firstStack));
        assertEquals(expectedSecond, cache.getCachedSlotLimit(1, second, secondStack));
        assertEquals(expectedFirst, cache.getCachedSlotLimit(0, first, firstStack));
        assertEquals(expectedSecond, cache.getCachedSlotLimit(1, second, secondStack));
    }
}
