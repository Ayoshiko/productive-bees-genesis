package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 调用者先撤销本项资格并锁定样本账户；原供应器始终拥有样板，一次写入后不补偿。 */
final class AeProviderPatternWriter {
    static TerminalCursorExchange.Result replace(ServerPlayer player, AePatternProviderTarget target, int slot,
            ItemStack original, ItemStack wanted, BooleanSupplier live) {
        if (!player.server.isSameThread() || !live.getAsBoolean() || !AeProviderPatternEdit.supported(target)
                || slot < 0 || slot >= target.size() || !ItemStack.matches(original, target.inventory().getStackInSlot(slot)))
            return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
        var offered = wanted.copy();
        if (offered.isEmpty() || offered.getItem() != original.getItem() || offered.getCount() != original.getCount()
                || offered.getCount() > target.inventory().getSlotLimit(slot) || !target.inventory().isItemValid(slot, offered)
                || !ItemStack.matches(wanted, offered) || !live.getAsBoolean()
                || !ItemStack.matches(original, target.inventory().getStackInSlot(slot)))
            return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
        try {
            target.inventory().setItemDirect(slot, offered);
            if (!ItemStack.matches(wanted, target.inventory().getStackInSlot(slot)) || !live.getAsBoolean())
                throw new IllegalStateException("Provider pattern readback changed");
            return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.MOVED, wanted.getCount());
        } catch (RuntimeException | LinkageError error) {
            com.mojang.logging.LogUtils.getLogger().error("Provider pattern edit outcome unknown for {} at {} slot={}; no retry or rollback",
                    player.getUUID(), target.location(), slot, error);
            return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.UNKNOWN, 0);
        }
    }
    private AeProviderPatternWriter() { }
}
