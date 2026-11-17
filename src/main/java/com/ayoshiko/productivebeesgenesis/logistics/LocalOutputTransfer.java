package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 各本地槽位独立交接，保留已知前缀；结果未知的单次请求进入宿主保管。 */
public final class LocalOutputTransfer {
    private LocalOutputTransfer() {}

    public static int insert(List<? extends IInventorySlot> slots, ItemStack stack, OutputCustody custody) {
        return insert(slots, stack, custody, true);
    }

    public static int insertExternal(List<? extends IInventorySlot> slots, ItemStack stack, OutputCustody custody) {
        return insert(slots, stack, custody, false);
    }

    private static int insert(List<? extends IInventorySlot> slots, ItemStack stack, OutputCustody custody,
            boolean local) {
        int remaining = stack.getCount();
        IndexedOutputSlots indexed = slots instanceof IndexedOutputSlots view ? view : null;
        for (int pass = 0; pass < 2 && remaining > 0 && !(local ? custody.localOutputBlocked() : custody.externalOutputBlocked()); pass++) {
            int fromIndex = 0;
            while (remaining > 0 && !(local ? custody.localOutputBlocked() : custody.externalOutputBlocked())) {
                int physicalSlot = fromIndex;
                if (indexed != null) {
                    try {
                        physicalSlot = pass == 0 ? indexed.nextMatchingSlot(stack.getItem(), fromIndex)
                                : indexed.nextEmptySlot(fromIndex);
                    } catch (RuntimeException | LinkageError error) {
                        // 索引查询尚未给出可推进的位置；此前交付的前缀继续按返回量结算。
                        LogThrottle.error("local_output_slot_query", "本地输出索引查询失败，已交付前缀保留，待发送数量={}",
                                remaining, error);
                        return stack.getCount() - remaining;
                    }
                }
                if (physicalSlot < 0 || physicalSlot >= slots.size()) break;
                fromIndex = physicalSlot + 1;
                IInventorySlot slot;
                ItemStack current;
                try {
                    if (custody.localItemBlocked(physicalSlot)) continue;
                    slot = slots.get(physicalSlot);
                    current = slot.getStack();
                    if (pass == 0 ? current.isEmpty() || current.getItem() != stack.getItem() : !current.isEmpty()) continue;
                    // 自有槽满载时原插入入口必定零接收，先排除可省组件比较、拷贝及保管往返。
                    if (slot instanceof TieredOutputInventorySlot && slot.getLimit(stack) <= current.getCount()) continue;
                } catch (RuntimeException | LinkageError error) {
                    LogThrottle.error("local_output_slot_prepare", "本地输出槽准备失败，槽位={}，已交付前缀保留，待发送数量={}",
                            physicalSlot, remaining, error);
                    continue;
                }
                if (pass == 0 && !ItemStack.isSameItemSameComponents(current, stack)) continue;
                int requested = remaining;
                OutputCustody owner = local ? custody.localItem(physicalSlot) : custody;
                try {
                    java.util.function.LongSupplier insert = () -> ItemPushHelper.accepted(stack, requested,
                            slot.insertItem(stack.copyWithCount(requested), Action.EXECUTE, AutomationType.INTERNAL));
                    int handed = (int) (local
                            ? owner.handoffLocalItem(stack, requested, current, "machine-slot:" + physicalSlot, slot::getStack, insert)
                            : owner.handoffItem(stack, requested, "machine-slot:" + physicalSlot, insert));
                    remaining -= handed;
                } catch (RuntimeException | LinkageError error) {
                    // 交接准备尚未调用接收方；已写入前缀按返回量结算，后续健康位置继续。
                    LogThrottle.error("local_output_slot_prepare", "本地输出交接准备失败，槽位={}，待发送数量={}",
                            physicalSlot, remaining, error);
                }
            }
        }
        return stack.getCount() - remaining;
    }
}
