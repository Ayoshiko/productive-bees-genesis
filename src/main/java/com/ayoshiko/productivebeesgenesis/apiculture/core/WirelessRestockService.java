package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
        int nextSlot, misses, playerEntity;
        ResourceLocation dimension;
        boolean queued, halted, running;
        String notice = "";
        WirelessRestockRequest request, identity;
        WirelessRestockTemplates templates;
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
            pending.request = null; pending.identity = null; clearTemplates(pending); pending.halted = false; pending.misses = 0; pending.notice = "";
            return;
        }
        // 设置变化即使受限流，也先撤销旧待办。
        if (!request.sameIntent(pending.identity) || player.getId() != pending.playerEntity
                || !player.level().dimension().location().equals(pending.dimension) || now - pending.receivedAt > 40) {
            pending.request = null; clearTemplates(pending);
        }
        if (pending.running || pending.halted || !worldMenu(player)
                || pending.acceptedAt != Long.MIN_VALUE && now - pending.acceptedAt < 20 || !TerminalPayloads.allow(player)) return;
        pending.acceptedAt = now; pending.receivedAt = now; pending.request = request; pending.identity = request;
        pending.playerEntity = player.getId(); pending.dimension = player.level().dimension().location();
        if (!pending.queued) { pending.queued = true; state.queue.addLast(pending); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); if (!server.isSameThread()) return; var state = STATES.get(server);
        if (state == null || state.queue.isEmpty()) return;
        long now = server.overworld().getGameTime();
        if (state.window == Long.MIN_VALUE || now - state.window >= 20) { state.window = now; state.used = 0; }
        int checks = Math.min(4, state.queue.size());
        for (int i = 0; i < checks; i++) {
            var pending = state.queue.removeFirst(); pending.queued = false;
            var request = pending.request; var player = server.getPlayerList().getPlayer(pending.player);
            if (request == null) { clearTemplates(pending); continue; }
            if (player == null || !current(player, state, pending, pending.sequence) || now - pending.receivedAt > 40) {
                pending.request = null; clearTemplates(pending); continue;
            }
            if (now < pending.due) { pending.queued = true; state.queue.addLast(pending); continue; }
            pending.request = null; pending.running = true; long watermark = pending.sequence;
            boolean completed = true;
            try { completed = restock(player, state, pending, request, now); }
            catch (RuntimeException | LinkageError error) {
                pending.halted = true; clearTemplates(pending);
                com.mojang.logging.LogUtils.getLogger().error("Wireless restock stopped for {}; inspect custody before re-enabling", pending.player, error);
                report(player, pending, "failed");
            } finally { pending.running = false; }
            if (pending.sequence == watermark && current(player, state, pending, watermark) && (!completed || request.emptySlots())) {
                // 预算等待保持队首；模板观察保留同一意图到续租失效，空闲不领提取额度。
                pending.request = request; pending.queued = true;
                if (completed) state.queue.addLast(pending); else state.queue.addFirst(pending);
            } else { clearTemplates(pending); }
            return;
        }
    }

    private static boolean restock(ServerPlayer player, State state, Pending pending, WirelessRestockRequest request, long now) {
        long watermark = pending.sequence;
        var cursor = TerminalCursor.get(player);
        if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
            clearTemplates(pending); pending.due = now + 200; report(player, pending, "retained"); return true;
        }
        var device = new WirelessDeviceSession(player, request.slot());
        if (!device.valid(player) || !device.binding().device().equals(request.device()) || !device.binding().token().equals(request.token())
                || !current(player, state, pending, watermark)) {
            backoff(player, pending, now, "unavailable"); return true;
        }
        if (request.emptySlots()) {
            if (pending.templates == null) pending.templates = new WirelessRestockTemplates();
            pending.templates.observe(player.getInventory().items, player.getOffhandItem(), request.offhand());
        }
        int slot = WirelessRestockSlots.find(player.getInventory().items, player.getOffhandItem(), pending.nextSlot, request.target(), request.offhand(), pending.templates);
        if (slot < 0) {
            pending.due = now + 20; pending.misses = 0;
            if (request.emptySlots()) report(player, pending, "remembering");
            return true;
        }
        if (state.used >= 4 || !MeTerminalBudget.expensive(player.server)) return false;
        state.used++; pending.due = now + 20; pending.nextSlot = WirelessRestockSlots.next(slot);
        var expected = player.getInventory().getItem(slot).copy();
        var sample = pending.templates == null ? expected.copyWithCount(1) : pending.templates.sample(slot, expected);
        var access = WirelessInventoryAccess.capture(player, device);
        if (access == null || !access.valid(player) || !current(player, state, pending, watermark)
                || !ItemStack.matches(expected, player.getInventory().getItem(slot)) || !device.charge(player, true)) { backoff(player, pending, now, "unavailable"); return true; }
        if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.item().isEmpty() || !cursor.pending.isEmpty()) {
            clearTemplates(pending); pending.due = now + 200; report(player, pending, "retained"); return true;
        }
        boolean[] attempted = { false };
        var result = TerminalCursorExchange.restockSlot(player, player.inventoryMenu, slot, expected, sample, request.target(), access.source().description(), requested -> {
            if (!access.valid(player) || !current(player, state, pending, watermark) || !ItemStack.matches(expected, player.getInventory().getItem(slot))) return 0;
            attempted[0] = true;
            return access.source().extract(requested);
        }, () -> access.valid(player) && current(player, state, pending, watermark));
        switch (result.outcome()) {
            case MOVED -> { pending.misses = 0; report(player, pending, "running"); }
            case NO_SPACE -> backoff(player, pending, now, attempted[0] ? "missing" : "changed");
            case RETAINED, UNKNOWN -> { pending.halted = true; clearTemplates(pending); report(player, pending, "retained"); }
            case INVALID -> backoff(player, pending, now, "changed");
        }
        return true;
    }

    private static boolean current(ServerPlayer player, State state, Pending pending, long sequence) {
        return state.players.get(pending.player) == pending && player.server.getPlayerList().getPlayer(pending.player) == player
                && player.getId() == pending.playerEntity && player.level().dimension().location().equals(pending.dimension)
                && pending.sequence == sequence && !pending.halted && worldMenu(player);
    }
    private static boolean worldMenu(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.getAbilities().instabuild
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
    }
    private static void backoff(ServerPlayer player, Pending pending, long now, String key) {
        if (key.equals("unavailable")) clearTemplates(pending);
        pending.misses = Math.min(4, pending.misses + 1); pending.due = now + Math.min(200, 20 << pending.misses);
        report(player, pending, key);
    }
    private static void clearTemplates(Pending pending) {
        if (pending.templates != null) { pending.templates.clear(); pending.templates = null; }
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
        if (pending != null) { pending.request = null; clearTemplates(pending); if (pending.queued) state.queue.remove(pending); }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.remove(event.getServer()); }
    private WirelessRestockService() { }
}
