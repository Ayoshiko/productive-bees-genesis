package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
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
        IndexedOutputSlots indexed = slots instanceof IndexedOutputSlots view ? view : null;
        for (int pass = 0; pass < 2 && remaining > 0 && !custody.blocked(); pass++) {
            int fromIndex = 0;
            while (remaining > 0 && !custody.blocked()) {
                IInventorySlot slot;
                ItemStack current;
                try {
                    int index = fromIndex;
                    if (indexed != null) {
                        index = pass == 0 ? indexed.nextMatchingSlot(stack.getItem(), fromIndex)
                                : indexed.nextEmptySlot(fromIndex);
                    }
                    if (index < 0 || index >= slots.size()) break;
                    fromIndex = index + 1;
                    slot = slots.get(index);
                    current = slot.getStack();
                    if (pass == 0 ? current.isEmpty() || current.getItem() != stack.getItem() : !current.isEmpty()) continue;
                    // 自有槽满载时原插入入口必定零接收，先排除可省组件比较、拷贝及保管往返。
                    if (slot instanceof TieredOutputInventorySlot && slot.getLimit(stack) <= current.getCount()) continue;
                } catch (RuntimeException error) {
                    // 候选查询尚未触碰资产；此前已交付的前缀仍按原规则结算。
                    return stack.getCount() - remaining;
                }
                if (pass == 0 && !ItemStack.isSameItemSameComponents(current, stack)) continue;
                int requested = remaining;
                int handed = (int) custody.handoffItem(stack, requested, "machine-slot", () ->
                        ItemPushHelper.accepted(stack, requested,
                                slot.insertItem(stack.copyWithCount(requested), Action.EXECUTE, AutomationType.INTERNAL)));
                remaining -= handed;
            }
        }
        return stack.getCount() - remaining;
    }
}
