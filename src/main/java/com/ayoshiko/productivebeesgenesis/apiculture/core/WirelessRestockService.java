package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 单服务器队列只保存在线玩家的最新意图；每次重新读取真实背包和来源。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class WirelessRestockService {
    private static final class Pending {
        final UUID player;
        long sequence, acceptedAt = Long.MIN_VALUE, receivedAt, due;
        int nextSlot, misses;
        boolean queued, halted, running;
        String notice = "";
        WirelessRestockRequest request;
        Pending(UUID player) { this.player = player; }
    }
    private static final class State {
        final Map<UUID, Pending> players = new HashMap<>();
        final ArrayDeque<Pending> queue = new ArrayDeque<>();
        long window = Long.MIN_VALUE;
        int used;
    }
    private static final Map<MinecraftServer, State> STATES = new HashMap<>();

    public static void handle(ServerPlayer player, WirelessRestockRequest request) {
        if (!player.server.isSameThread()) return;
        var state = STATES.computeIfAbsent(player.server, ignored -> new State());
        var pending = state.players.computeIfAbsent(player.getUUID(), Pending::new);
        if (request.sequence() <= pending.sequence) return;
        pending.sequence = request.sequence();
        long now = player.server.overworld().getGameTime();
        if (!request.enabled()) {
            pending.request = null; pending.halted = false; pending.misses = 0; pending.notice = "";
            return;
        }
        if (pending.running || pending.halted || !worldMenu(player)
                || pending.acceptedAt != Long.MIN_VALUE && now - pending.acceptedAt < 20 || !TerminalPayloads.allow(player)) return;
        pending.acceptedAt = now; pending.receivedAt = now; pending.request = request;
        if (!pending.queued) { pending.queued = true; state.queue.addLast(pending); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); var state = STATES.get(server);
        if (state == null || state.queue.isEmpty()) return;
        long now = server.overworld().getGameTime();
        if (state.window == Long.MIN_VALUE || now - state.window >= 20) { state.window = now; state.used = 0; }
        int checks = Math.min(4, state.queue.size());
        for (int i = 0; i < checks; i++) {
            var pending = state.queue.removeFirst(); pending.queued = false;
            var request = pending.request; var player = server.getPlayerList().getPlayer(pending.player);
            if (request == null) continue;
            if (state.players.get(pending.player) != pending || player == null || !worldMenu(player) || now - pending.receivedAt > 40) {
                pending.request = null; continue;
            }
            if (now < pending.due) { pending.queued = true; state.queue.addLast(pending); continue; }
            pending.request = null; pending.running = true; long watermark = pending.sequence;
            boolean completed = true;
            try { completed = restock(player, state, pending, request, now); }
            catch (RuntimeException | LinkageError error) {
                pending.halted = true;
                com.mojang.logging.LogUtils.getLogger().error("Wireless restock stopped for {}; inspect custody before re-enabling", pending.player, error);
                report(player, pending, "failed");
            } finally { pending.running = false; }
            if (!completed && pending.sequence == watermark) {
                // 预算尚未执行任何外部交接；原队首保持优先，不丢失或绕过后来撤销。
                pending.request = request; pending.queued = true; state.queue.addFirst(pending);
            }
            return;
        }
    }

    private static boolean restock(ServerPlayer player, State state, Pending pending, WirelessRestockRequest request, long now) {
        long watermark = pending.sequence;
        var cursor = TerminalCursor.get(player);
        if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
            pending.due = now + 200; report(player, pending, "retained"); return true;
        }
        int slot = partialSlot(player, pending.nextSlot);
        if (slot < 0) { pending.due = now + 20; pending.misses = 0; return true; }
        if (state.used >= 4 || !MeTerminalBudget.expensive(player.server)) return false;
        state.used++; pending.due = now + 20; pending.nextSlot = (slot + 1) % 36;
        var expected = player.getInventory().getItem(slot).copy();
        var device = new WirelessDeviceSession(player, request.slot());
        if (!device.valid(player) || !device.binding().device().equals(request.device()) || !device.binding().token().equals(request.token())) {
            backoff(player, pending, now, "unavailable"); return true;
        }
        var access = WirelessInventoryAccess.capture(player, device);
        if (access == null || pending.sequence != watermark || !worldMenu(player) || !ItemStack.matches(expected, player.getInventory().getItem(slot))
                || !access.valid(player) || !device.charge(player, true)) { backoff(player, pending, now, "unavailable"); return true; }
        if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
            pending.due = now + 200; report(player, pending, "retained"); return true;
        }
        var wanted = expected.copyWithCount(Math.min(64, expected.getMaxStackSize()) - expected.getCount());
        boolean[] attempted = { false };
        var result = TerminalCursorExchange.exchange(player, player.inventoryMenu, wanted, false, true, access.source().description(), requested -> {
            if (pending.sequence != watermark || !worldMenu(player) || !ItemStack.matches(expected, player.getInventory().getItem(slot)) || !access.valid(player)) return 0;
            attempted[0] = true;
            return access.source().extract(requested);
        });
        switch (result.outcome()) {
            case MOVED -> { pending.misses = 0; report(player, pending, "running"); }
            case NO_SPACE -> backoff(player, pending, now, attempted[0] ? "missing" : "changed");
            case RETAINED, UNKNOWN -> { pending.halted = true; report(player, pending, "retained"); }
            case INVALID -> backoff(player, pending, now, "changed");
        }
        return true;
    }

    private static int partialSlot(ServerPlayer player, int start) {
        for (int offset = 0; offset < 36; offset++) {
            int slot = (start + offset) % 36; var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && !(stack.getItem() instanceof WirelessTerminalItem) && stack.getCount() < Math.min(64, stack.getMaxStackSize())) return slot;
        }
        return -1;
    }
    private static boolean worldMenu(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.getAbilities().instabuild
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
    }
    private static void backoff(ServerPlayer player, Pending pending, long now, String key) {
        pending.misses = Math.min(4, pending.misses + 1); pending.due = now + Math.min(200, 20 << pending.misses);
        report(player, pending, key);
    }
    private static void report(ServerPlayer player, Pending pending, String key) {
        if (pending.notice.equals(key)) return;
        pending.notice = key;
        player.displayClientMessage(Component.translatable("item.productivebeesgenesis.wireless_terminal.restock." + key), true);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var state = STATES.get(player.server); if (state == null) return;
        var pending = state.players.remove(player.getUUID());
        if (pending != null) { pending.request = null; if (pending.queued) state.queue.remove(pending); }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.remove(event.getServer()); }
    private WirelessRestockService() { }
}
