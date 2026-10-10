package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 主背包与可选副手共用候选规则；只规划原槽增量，不修改真实库存或保留空槽模板。 */
public final class WirelessRestockSlots {
    public record Delivery(ItemStack slot, ItemStack remainder) { }
    public static boolean validTarget(int target) { return target >= 1 && target <= 64; }
    public static int missing(ItemStack stack, int target) {
        if (!validTarget(target)) throw new IllegalArgumentException("Invalid restock target");
        if (stack.isEmpty() || stack.getItem() instanceof WirelessTerminalItem) return 0;
        return Math.max(0, Math.min(target, stack.getMaxStackSize()) - stack.getCount());
    }
    public static int find(List<ItemStack> inventory, ItemStack offhand, int start, int target, boolean includeOffhand) {
        if (inventory.size() != 36 || !validTarget(target)) throw new IllegalArgumentException("Invalid restock inventory");
        int slots = includeOffhand ? 37 : 36;
        for (int offset = 0; offset < slots; offset++) {
            int index = (Math.floorMod(start, slots) + offset) % slots;
            if (missing(index == 36 ? offhand : inventory.get(index), target) > 0) return index == 36 ? 40 : index;
        }
        return -1;
    }
    public static int next(int slot) { return slot == 40 ? 0 : slot + 1; }
    public static Delivery deliver(ItemStack expected, ItemStack current, ItemStack received, int target) {
        if (!ItemStack.matches(expected, current) || !ItemStack.isSameItemSameComponents(expected, received)) return null;
        int moved = Math.min(missing(current, target), received.getCount());
        return moved <= 0 ? null : new Delivery(current.copyWithCount(current.getCount() + moved),
                received.copyWithCount(received.getCount() - moved));
    }
    private WirelessRestockSlots() { }
}
