package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 服务器线程上的单次世界选块；明确零提取可进入需确认的合成计划。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class WirelessPickService {
    private static final class Seen { long sequence; long tick = Long.MIN_VALUE; }
    private static final Map<UUID, Seen> REQUESTS = new HashMap<>();
    public static void handle(ServerPlayer player, WirelessPickRequest request) {
        if (!player.server.isSameThread()) return;
        var seen = REQUESTS.computeIfAbsent(player.getUUID(), ignored -> new Seen());
        if (request.sequence() <= seen.sequence) return;
        seen.sequence = request.sequence();
        long now = player.server.overworld().getGameTime();
        if (seen.tick != Long.MIN_VALUE && now - seen.tick < 4 || !TerminalPayloads.allow(player)) return;
        seen.tick = now;
        if (!worldMenu(player, request) || !MeTerminalBudget.expensive(player.server)) return;
        try { pick(player, request); }
        catch (RuntimeException | LinkageError failure) {
            com.mojang.logging.LogUtils.getLogger().error("Wireless pick stopped for {} at {}; no retry", player.getUUID(), request.target(), failure);
            message(player, "failed");
        }
    }
    private static void pick(ServerPlayer player, WirelessPickRequest request) {
        var device = new WirelessDeviceSession(player, request.slot());
        if (!device.valid(player) || !device.binding().device().equals(request.device()) || !device.binding().token().equals(request.token())) {
            message(player, "unavailable"); return;
        }
        var cursor = TerminalCursor.get(player);
        if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
            message(player, "retained"); return;
        }
        var sample = sample(player, request.target());
        if (sample.isEmpty() || !worldMenu(player, request)) { message(player, "target_changed"); return; }
        if (player.getInventory().findSlotMatchingItem(sample) >= 0) { select(player, sample, request); return; }
        var access = WirelessInventoryAccess.capture(player, device);
        if (access == null) { message(player, "unavailable"); return; }
        var source = access.source();
        var wanted = sample.copyWithCount(Math.min(64, sample.getMaxStackSize()));
        if (!TerminalCraftingPlan.insert(TerminalCraftingPlan.copy(player.getInventory().items), sample).isEmpty()) {
            message(player, "full"); return;
        }
        if (!worldMenu(player, request) || !access.valid(player) || !device.charge(player, true)) {
            message(player, "unavailable"); return;
        }
        if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
            message(player, "retained"); return;
        }
        boolean[] attempted = { false };
        var result = TerminalCursorExchange.exchange(player, player.inventoryMenu, wanted, false, true, source.description(), requested -> {
            var currentSample = sample(player, request.target());
            if (!ItemStack.isSameItemSameComponents(sample, currentSample) || !worldMenu(player, request)
                    || !access.valid(player)) return 0;
            attempted[0] = true;
            return source.extract(requested);
        });
        switch (result.outcome()) {
            case MOVED -> { select(player, sample, request); message(player, "moved", result.amount()); }
            case NO_SPACE -> {
                // 仅明确执行且返回零的提取可以计划；异常、跳过和满载不能当作缺货。
                if (!attempted[0]) { message(player, "missing"); return; }
                var currentSample = sample(player, request.target());
                if (!ItemStack.isSameItemSameComponents(sample, currentSample) || !worldMenu(player, request)
                        || !access.valid(player)) {
                    message(player, "target_changed"); return;
                }
                if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
                    message(player, "retained"); return;
                }
                if (!WirelessTerminalAccess.open(player, device, wanted)) message(player, "unavailable");
            }
            case RETAINED, UNKNOWN -> message(player, "retained");
            case INVALID -> message(player, "target_changed");
        }
    }
    private static boolean worldMenu(ServerPlayer player, WirelessPickRequest request) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.getAbilities().instabuild
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty()
                && player.getInventory().selected == request.selected();
    }
    private static ItemStack sample(ServerPlayer player, BlockPos expected) {
        double reach = Math.min(WirelessPickRequest.MAX_REACH, player.blockInteractionRange());
        if (!Double.isFinite(reach) || reach <= 0) return ItemStack.EMPTY;
        var level = player.serverLevel(); var start = player.getEyePosition(); var end = start.add(player.getViewVector(1).scale(reach));
        // 预检包围区间的一格余量，覆盖射线边界偏移及普通连接形状的邻格读取。
        int minX = Mth.floor(Math.min(start.x, end.x) - 1) >> 4, maxX = Mth.floor(Math.max(start.x, end.x) + 1) >> 4;
        int minZ = Mth.floor(Math.min(start.z, end.z) - 1) >> 4, maxZ = Mth.floor(Math.max(start.z, end.z) + 1) >> 4;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return ItemStack.EMPTY;
        var hit = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(expected) || !level.mayInteract(player, expected)) return ItemStack.EMPTY;
        var state = level.getBlockState(expected);
        var value = state.getCloneItemStack(hit, level, expected, player);
        return value == null || value.isEmpty() || value.getMaxStackSize() < 1 || level.getBlockState(expected) != state
                ? ItemStack.EMPTY : value.copyWithCount(1);
    }
    private static void select(ServerPlayer player, ItemStack sample, WirelessPickRequest request) {
        if (!worldMenu(player, request)) return;
        var inventory = player.getInventory(); int slot = inventory.findSlotMatchingItem(sample);
        if (slot < 0) return;
        if (Inventory.isHotbarSlot(slot)) inventory.selected = slot;
        else inventory.pickSlot(slot);
        inventory.setChanged(); player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetCarriedItemPacket(inventory.selected));
    }
    private static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("item.productivebeesgenesis.wireless_terminal.pick." + key, args), true);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { REQUESTS.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { REQUESTS.clear(); }
    private WirelessPickService() { }
}
