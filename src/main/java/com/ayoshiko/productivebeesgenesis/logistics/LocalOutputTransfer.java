package com.ayoshiko.productivebeesgenesis.logistics;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 各本地槽位独立交接，保留已知前缀；结果未知的单次请求进入宿主保管。 */
public final class LocalOutputTransfer {
    private LocalOutputTransfer() {}

    public static int insert(List<? extends IInventorySlot> slots, ItemStack stack, OutputCustody custody) {
        int remaining = stack.getCount();
        for (int pass = 0; pass < 2 && remaining > 0 && !custody.blocked(); pass++) {
            for (IInventorySlot slot : slots) {
                if (remaining <= 0 || custody.blocked()) break;
                ItemStack current;
                try { current = slot.getStack(); } catch (RuntimeException error) { return stack.getCount() - remaining; }
                if (pass == 0 ? current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack) : !current.isEmpty()) continue;
                ItemStack offered = stack.copyWithCount(remaining);
                int handed = custody.handoffItem(offered, "machine-slot", () ->
                        ItemPushHelper.accepted(offered, slot.insertItem(offered.copy(), Action.EXECUTE, AutomationType.INTERNAL)));
                remaining -= handed;
            }
        }
        return stack.getCount() - remaining;
    }
}
