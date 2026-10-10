package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 主背包与可选副手共用候选规则；样本只参与计划，不修改真实库存。 */
public final class WirelessRestockSlots {
    public record Delivery(ItemStack slot, ItemStack remainder) { }
    public static boolean validTarget(int target) { return target >= 1 && target <= 64; }
    public static int missing(ItemStack stack, int target) {
        return missing(stack, stack, target);
    }
    static int missing(ItemStack stack, ItemStack sample, int target) {
        if (!validTarget(target)) throw new IllegalArgumentException("Invalid restock target");
        if (sample.isEmpty() || sample.getItem() instanceof WirelessTerminalItem
                || !stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, sample)) return 0;
        return Math.max(0, Math.min(target, sample.getMaxStackSize()) - stack.getCount());
    }
    public static int find(List<ItemStack> inventory, ItemStack offhand, int start, int target, boolean includeOffhand) {
        return find(inventory, offhand, start, target, includeOffhand, null);
    }
    static int find(List<ItemStack> inventory, ItemStack offhand, int start, int target, boolean includeOffhand, WirelessRestockTemplates templates) {
        if (inventory.size() != 36 || !validTarget(target)) throw new IllegalArgumentException("Invalid restock inventory");
        int slots = includeOffhand ? 37 : 36;
        for (int offset = 0; offset < slots; offset++) {
            int index = (Math.floorMod(start, slots) + offset) % slots;
            int slot = index == 36 ? 40 : index; var stack = index == 36 ? offhand : inventory.get(index);
            if ((templates == null ? missing(stack, target) : templates.missing(slot, stack, target)) > 0) return slot;
        }
        return -1;
    }
    public static int next(int slot) { return slot == 40 ? 0 : slot + 1; }
    public static Delivery deliver(ItemStack expected, ItemStack current, ItemStack received, int target) {
        return deliver(expected, current, expected, received, target);
    }
    static Delivery deliver(ItemStack expected, ItemStack current, ItemStack sample, ItemStack received, int target) {
        if (!ItemStack.matches(expected, current) || !ItemStack.isSameItemSameComponents(sample, received)) return null;
        int moved = Math.min(missing(current, sample, target), received.getCount());
        return moved <= 0 ? null : new Delivery(sample.copyWithCount(current.getCount() + moved),
                received.copyWithCount(received.getCount() - moved));
    }
    private WirelessRestockSlots() { }
}
