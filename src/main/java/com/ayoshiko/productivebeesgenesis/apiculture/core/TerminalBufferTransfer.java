package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.List;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.*;

/** 私人缓冲与外部库存之间的一次交接；请求先保管，实际返回之后才结算。 */
public final class TerminalBufferTransfer {
    public static Result exchange(ServerPlayer player, AbstractContainerMenu menu, TerminalPatternBuffer.Snapshot snapshot,
            int slot, ItemStack wanted, boolean insert, String source, Function<ItemStack, ItemStack> external) {
        var cursor = TerminalCursor.get(player);
        if (cursor.containerBusy || unknown(player)) return new Result(Outcome.UNKNOWN, 0);
        cursor.containerBusy = true;
        try {
            if (snapshot == null || !snapshot.current(player) || insert && (slot < 0 || slot >= TerminalPatternBuffer.SLOTS)
                    || player.containerMenu != menu || !menu.stillValid(player) || wanted.isEmpty() || wanted.getCount() < 1 || wanted.getCount() > 64)
                return new Result(Outcome.INVALID, 0);
            if (!cursor.pending.isEmpty()) return new Result(Outcome.RETAINED, 0);
            var before = cursor.patternBuffer; var held = insert ? before.get(slot) : ItemStack.EMPTY;
            if (insert) {
                if (!ItemStack.isSameItemSameComponents(held, wanted) || held.getCount() < wanted.getCount()) return new Result(Outcome.INVALID, 0);
            } else if (!TerminalCraftingPlan.insert(TerminalCraftingPlan.copy(before), wanted).isEmpty()) return new Result(Outcome.NO_SPACE, 0);
            var requested = wanted.copy(); var record = new Request(requested, insert, source);
            var reserved = before;
            if (insert) {
                var next = TerminalCraftingPlan.copy(before); next.set(slot, held.copyWithCount(held.getCount() - requested.getCount()));
                reserved = List.copyOf(next);
            }
            cursor.request = record;
            cursor.patternBuffer = reserved;
            int actual;
            try { actual = transferItems(cursor, requested.copy(), insert, external); }
            catch (RuntimeException | LinkageError failure) {
                com.mojang.logging.LogUtils.getLogger().error("Pattern buffer transfer outcome unknown for {} at {}: insert={}, item={}; retained without retry",
                        player.getUUID(), source, insert, requested, failure);
                if (player.containerMenu == menu) menu.broadcastFullState();
                return new Result(Outcome.UNKNOWN, 0);
            }
            cursor.pending = requested.copyWithCount(insert ? requested.getCount() - actual : actual);
            cursor.request = null;
            // 已知余量先登记，再尝试放回当前缓冲；回调改变了根或接收空间时继续保管。
            if (!cursor.pending.isEmpty() && cursor.available()) {
                var current = cursor.patternBuffer;
                if (!insert) {
                    var next = TerminalCraftingPlan.copy(current); var rest = TerminalCraftingPlan.insert(next, cursor.pending);
                    cursor.patternBuffer = List.copyOf(next); cursor.pending = rest;
                } else {
                    var target = current.get(slot);
                    if (target.isEmpty() || ItemStack.isSameItemSameComponents(target, cursor.pending)) {
                        int accepted = Math.min(cursor.pending.getCount(), Math.max(0, Math.min(64, cursor.pending.getMaxStackSize()) - target.getCount()));
                        if (accepted > 0) {
                            var next = TerminalCraftingPlan.copy(current); next.set(slot, cursor.pending.copyWithCount(target.getCount() + accepted));
                            var rest = cursor.pending.copyWithCount(cursor.pending.getCount() - accepted);
                            cursor.patternBuffer = List.copyOf(next); cursor.pending = rest;
                        }
                    }
                }
            }
            if (player.containerMenu == menu) menu.broadcastFullState();
            return new Result(!cursor.pending.isEmpty() ? Outcome.RETAINED : actual == 0 ? Outcome.NO_SPACE : Outcome.MOVED, actual);
        } finally { cursor.containerBusy = false; }
    }
    private TerminalBufferTransfer() { }
}
