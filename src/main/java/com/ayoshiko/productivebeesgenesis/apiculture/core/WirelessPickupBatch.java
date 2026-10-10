package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 只记录仍在主背包中的已拾取增量；快照失配即放弃，不预先占有资产。 */
final class WirelessPickupBatch {
    private static final int MAX_KEYS = 8;
    private final List<ItemStack> credits = new ArrayList<>();
    private List<ItemStack> expected;

    WirelessPickupBatch(List<ItemStack> inventory) {
        if (inventory.size() != 36) throw new IllegalArgumentException("Invalid pickup inventory");
        expected = TerminalCraftingPlan.copy(inventory);
    }
    boolean matches(List<ItemStack> inventory) { return ItemStack.listMatches(expected, inventory); }
    List<ItemStack> snapshot() { return TerminalCraftingPlan.copy(expected); }
    boolean empty() { return credits.isEmpty(); }
    ItemStack wanted() { return credits.isEmpty() ? ItemStack.EMPTY : credits.getFirst().copy(); }

    boolean observe(List<ItemStack> inventory, ItemStack sample, long received, boolean eligible) {
        long added = increase(expected, inventory, sample);
        if (added < 0 || received <= 0) return false;
        expected = TerminalCraftingPlan.copy(inventory);
        int limit = Math.min(64, sample.getMaxStackSize());
        int amount = (int) Math.min(Math.min(received, added), limit);
        if (!eligible || amount <= 0 || sample.getItem() instanceof WirelessTerminalItem) return true;
        for (int i = 0; i < credits.size(); i++) {
            var old = credits.get(i);
            if (ItemStack.isSameItemSameComponents(old, sample)) {
                credits.set(i, sample.copyWithCount(Math.min(limit, old.getCount() + amount)));
                return true;
            }
        }
        if (credits.size() < MAX_KEYS) credits.add(sample.copyWithCount(amount));
        return true;
    }

    /** 仅在一组全部接收且背包只少了这组物品时延续剩余计划。 */
    boolean settled(List<ItemStack> charged, List<ItemStack> inventory, ItemStack wanted) {
        if (credits.isEmpty() || !ItemStack.matches(credits.getFirst(), wanted)
                || increase(inventory, charged, wanted) != wanted.getCount()) return false;
        credits.removeFirst();
        expected = TerminalCraftingPlan.copy(inventory);
        return true;
    }

    /** -1 表示出现了本次拾取无法解释的变化；不接受换位、减少或其它组件变化。 */
    private static long increase(List<ItemStack> before, List<ItemStack> after, ItemStack sample) {
        if (before.size() != 36 || after.size() != 36 || sample.isEmpty()) return -1;
        long added = 0;
        for (int i = 0; i < 36; i++) {
            var old = before.get(i); var now = after.get(i);
            if (ItemStack.matches(old, now)) continue;
            if (!ItemStack.isSameItemSameComponents(now, sample)
                    || !old.isEmpty() && !ItemStack.isSameItemSameComponents(old, sample)
                    || now.getCount() < old.getCount()) return -1;
            added += (long) now.getCount() - old.getCount();
        }
        return added;
    }
}
