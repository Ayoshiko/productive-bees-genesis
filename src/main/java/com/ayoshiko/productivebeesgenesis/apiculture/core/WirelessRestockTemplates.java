package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 只保存单次连续意图内的单件样本；不是资产，不持玩家、世界或库存引用。 */
final class WirelessRestockTemplates {
    private final ItemStack[] samples = new ItemStack[37];
    WirelessRestockTemplates() { clear(); }
    void observe(List<ItemStack> inventory, ItemStack offhand, boolean includeOffhand) {
        if (inventory.size() != 36) throw new IllegalArgumentException("Invalid template inventory");
        for (int i = 0; i < 36; i++) observe(i, inventory.get(i));
        if (includeOffhand) observe(36, offhand); else samples[36] = ItemStack.EMPTY;
    }
    private void observe(int index, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (stack.getItem() instanceof WirelessTerminalItem) { samples[index] = ItemStack.EMPTY; return; }
        if (!ItemStack.isSameItemSameComponents(samples[index], stack)) samples[index] = stack.copyWithCount(1);
    }
    int missing(int slot, ItemStack stack, int target) {
        return WirelessRestockSlots.missing(stack, stack.isEmpty() ? samples[index(slot)] : stack, target);
    }
    ItemStack sample(int slot, ItemStack stack) {
        return (stack.isEmpty() ? samples[index(slot)] : stack).copyWithCount(1);
    }
    void clear() { Arrays.fill(samples, ItemStack.EMPTY); }
    private static int index(int slot) {
        if (!WirelessPickRequest.validSlot(slot)) throw new IllegalArgumentException("Invalid template slot");
        return slot == 40 ? 36 : slot;
    }
}
