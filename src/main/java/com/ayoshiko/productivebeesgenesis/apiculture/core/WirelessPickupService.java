package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
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
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 只处理原版已实际交付的主背包增量；不改地面实体或强制允许拾取。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class WirelessPickupService {
    private static final class Intent {
        final UUID player;
        long sequence, generation, acceptedAt = Long.MIN_VALUE, receivedAt;
        WirelessPickupRequest request;
        Batch batch;
        boolean halted, queued;
        String notice = "";
        Intent(UUID player) { this.player = player; }
    }
    private record Session(int playerEntity, ResourceLocation dimension, long generation,
            WirelessPickupRequest request, WirelessTerminalItem.Binding binding) { }
    private static final class Capture {
        final UUID player, entity;
        final long tick;
        final Session session;
        final List<ItemStack> before;
        final ItemStack sample;
        final boolean eligible;
        Capture(ServerPlayer player, UUID entity, ItemStack sample, Intent intent, long tick,
                WirelessTerminalItem.Binding binding, boolean eligible) {
            this.player = player.getUUID(); this.entity = entity; this.tick = tick; this.eligible = eligible;
            session = new Session(player.getId(), player.level().dimension().location(), intent.generation, intent.request, binding);
            before = TerminalCraftingPlan.copy(player.getInventory().items); this.sample = sample.copyWithCount(1);
        }
    }
    private static final class Batch {
        final long createdAt;
        final Session session;
        final WirelessPickupBatch contents;
        Batch(Capture capture) {
            createdAt = capture.tick; session = capture.session; contents = new WirelessPickupBatch(capture.before);
        }
    }
    private static final class State {
        final Map<UUID, Intent> players = new HashMap<>();
        final ArrayDeque<Intent> queue = new ArrayDeque<>();
        Capture capture;
        long window = Long.MIN_VALUE, capturedAt = Long.MIN_VALUE, processedAt = Long.MIN_VALUE;
        int used, captures;
        boolean running;
    }
    private static final Map<MinecraftServer, State> STATES = new HashMap<>();

    public static void handle(ServerPlayer player, WirelessPickupRequest request) {
        if (!player.server.isSameThread()) return;
        var state = STATES.computeIfAbsent(player.server, ignored -> new State());
        var intent = state.players.computeIfAbsent(player.getUUID(), Intent::new);
        if (request.sequence() <= intent.sequence) return;
        intent.sequence = request.sequence(); long now = clock(player.server);
        if (!request.enabled()) {
            intent.generation++; intent.request = null; intent.batch = null; intent.halted = false; intent.notice = "";
            if (state.capture != null && state.capture.player.equals(player.getUUID())) state.capture = null;
            return;
        }
        // 在限流之前撤销旧捕获；改变筛选不能让旧规则的待办继续入网。
        if (!request.sameIntent(intent.request)) {
            intent.generation++; intent.request = null; intent.batch = null;
            if (state.capture != null && state.capture.player.equals(player.getUUID())) state.capture = null;
        }
        if (intent.halted || !worldMenu(player) || intent.acceptedAt != Long.MIN_VALUE && now - intent.acceptedAt < 20
                || !TerminalPayloads.allow(player)) return;
        intent.request = request; intent.acceptedAt = now; intent.receivedAt = now;
        if (request.filter().rejectsAll()) report(player, intent, "filter_empty");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void before(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !player.server.isSameThread() || !worldMenu(player)) return;
        var state = STATES.get(player.server); if (state == null || state.capture != null || state.running) return;
        var intent = state.players.get(player.getUUID()); long now = clock(player.server);
        if (!active(intent, now)) return;
        var entity = event.getItemEntity();
        if (event.canPickup().isFalse() || entity.isRemoved() || entity.level() != player.level() || entity.hasPickUpDelay()
                || entity.getTarget() != null && !entity.getTarget().equals(player.getUUID())) return;
        try {
            var sample = entity.getItem();
            if (sample.isEmpty() || sample.getItem() instanceof WirelessTerminalItem) return;
            if (state.capturedAt != now) { state.capturedAt = now; state.captures = 0; }
            if (state.captures >= 8) return;
            state.captures++;
            if (intent.batch != null && (!current(player, intent, intent.batch)
                    || !intent.batch.contents.matches(player.getInventory().items))) intent.batch = null;
            boolean eligible = intent.request.filter().allows(sample);
            if (!eligible) {
                report(player, intent, intent.request.filter().rejectsAll() ? "filter_empty" : "filtered");
                if (intent.batch == null) return;
            }
            if (!clearCursor(player)) { intent.batch = null; return; }
            var device = device(player, intent.request); if (device == null) { intent.batch = null; return; }
            if (intent.batch != null && !intent.batch.session.binding().equals(device.binding())) intent.batch = null;
            state.capture = new Capture(player, entity.getUUID(), sample, intent, now, device.binding(), eligible);
        } catch (RuntimeException | LinkageError error) { fail(player, state, intent, error); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void after(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !player.server.isSameThread()) return;
        var state = STATES.get(player.server); if (state == null) return;
        var capture = state.capture;
        if (capture == null || !capture.player.equals(player.getUUID()) || !capture.entity.equals(event.getItemEntity().getUUID())) return;
        state.capture = null;
        var intent = state.players.get(player.getUUID());
        try {
            if (clock(player.server) != capture.tick || !current(player, intent, capture.session)) { if (intent != null) intent.batch = null; return; }
            var original = event.getOriginalStack(); var remaining = event.getCurrentStack();
            if (!ItemStack.isSameItemSameComponents(capture.sample, original) || !remaining.isEmpty()
                    && (!ItemStack.isSameItemSameComponents(original, remaining) || remaining.getCount() > original.getCount())) { intent.batch = null; return; }
            long received = (long) original.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());
            var batch = intent.batch;
            if (batch == null) batch = new Batch(capture);
            if (!batch.contents.matches(capture.before) || !batch.contents.observe(player.getInventory().items, capture.sample, received, capture.eligible)) {
                intent.batch = null; return;
            }
            if (!batch.contents.empty()) {
                intent.batch = batch;
                if (!intent.queued) { intent.queued = true; state.queue.addLast(intent); }
            }
        } catch (RuntimeException | LinkageError error) { fail(player, state, intent, error); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void finish(ServerTickEvent.Post event) {
        var server = event.getServer(); if (!server.isSameThread()) return;
        var state = STATES.get(server); if (state == null || state.running) return;
        state.capture = null;
        long now = clock(server);
        if (state.processedAt == now) return;
        if (state.window == Long.MIN_VALUE || now < state.window || now - state.window >= 20) { state.window = now; state.used = 0; }
        int checks = Math.min(4, state.queue.size());
        for (int i = 0; i < checks; i++) {
            var intent = state.queue.removeFirst(); intent.queued = false;
            var batch = intent.batch; var player = server.getPlayerList().getPlayer(intent.player);
            if (batch == null) continue;
            try {
                if (player == null || state.players.get(intent.player) != intent || !current(player, intent, batch)) { intent.batch = null; continue; }
                if (!batch.contents.matches(player.getInventory().items)) { intent.batch = null; report(player, intent, "changed"); continue; }
                if (!clearCursor(player)) { intent.batch = null; report(player, intent, "retained"); continue; }
                if (state.used >= 2 || !MeTerminalBudget.expensive(server)) {
                    intent.queued = true; state.queue.addFirst(intent); report(player, intent, "waiting"); return;
                }
                // 待办先脱离队列和玩家状态；回调只能撤销身份，不能重入或领取第二份增量。
                intent.batch = null; state.running = true; state.processedAt = now; state.used++;
                if (deposit(player, intent, batch) && current(player, intent, batch) && state.players.get(intent.player) == intent) {
                    intent.batch = batch; intent.queued = true; state.queue.addLast(intent);
                }
            } catch (RuntimeException | LinkageError error) { fail(player, state, intent, error); }
            finally { state.running = false; }
            if (state.processedAt == now) return;
        }
    }

    private static boolean deposit(ServerPlayer player, Intent intent, Batch batch) {
        var session = batch.session; var expected = batch.contents.snapshot(); var wanted = batch.contents.wanted();
        var device = device(player, session.request());
        var access = device == null || !device.binding().equals(session.binding()) ? null : WirelessInventoryAccess.capture(player, device);
        if (access == null || !access.valid(player)) { report(player, intent, "unavailable"); return false; }
        if (!current(player, intent, batch) || !ItemStack.listMatches(expected, player.getInventory().items)
                || !device.charge(player, true)) { report(player, intent, "unavailable"); return false; }
        // 设备收费只改变原设备组件；其余背包变化不能挪用旧物品补足拾取增量。
        if (!sameExceptDevice(expected, player.getInventory().items, session.request().slot())) { report(player, intent, "changed"); return false; }
        var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
        var result = TerminalCursorExchange.depositInventory(player, player.inventoryMenu, inventory, wanted, access.source().description(), requested -> {
            if (!current(player, intent, batch) || !access.valid(player)) return 0;
            return access.source().insert(requested);
        });
        switch (result.outcome()) {
            case MOVED -> {
                report(player, intent, result.amount() == wanted.getCount() ? "stored" : "partial");
                return result.amount() == wanted.getCount() && clearCursor(player)
                        && batch.contents.settled(inventory, player.getInventory().items, wanted) && !batch.contents.empty();
            }
            case NO_SPACE -> report(player, intent, "rejected");
            case RETAINED, UNKNOWN -> { intent.halted = true; report(player, intent, "retained"); }
            case INVALID -> report(player, intent, "changed");
        }
        return false;
    }

    private static boolean active(Intent intent, long now) {
        return intent != null && !intent.halted && intent.request != null && now >= intent.receivedAt && now - intent.receivedAt <= 40;
    }
    private static boolean current(ServerPlayer player, Intent intent, Batch batch) {
        long now = clock(player.server);
        return now >= batch.createdAt && now - batch.createdAt <= 40 && current(player, intent, batch.session);
    }
    private static boolean current(ServerPlayer player, Intent intent, Session session) {
        return active(intent, clock(player.server)) && worldMenu(player) && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && player.getId() == session.playerEntity() && intent.generation == session.generation()
                && session.request().sameIntent(intent.request) && session.dimension().equals(player.level().dimension().location());
    }
    private static WirelessDeviceSession device(ServerPlayer player, WirelessPickupRequest request) {
        var device = new WirelessDeviceSession(player, request.slot());
        return device.valid(player) && device.binding().device().equals(request.device()) && device.binding().token().equals(request.token()) ? device : null;
    }
    private static boolean sameExceptDevice(List<ItemStack> expected, List<ItemStack> actual, int slot) {
        if (expected.size() != 36 || actual.size() != 36) return false;
        for (int i = 0; i < 36; i++) if (i != slot && !ItemStack.matches(expected.get(i), actual.get(i))) return false;
        return true;
    }
    private static boolean clearCursor(ServerPlayer player) {
        var cursor = TerminalCursor.get(player);
        return !cursor.containerBusy && !TerminalCursorExchange.unknown(player) && cursor.item().isEmpty() && cursor.pending.isEmpty();
    }
    private static boolean worldMenu(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.getAbilities().instabuild
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
    }
    private static long clock(MinecraftServer server) { return Integer.toUnsignedLong(server.getTickCount()); }
    private static void report(ServerPlayer player, Intent intent, String key) {
        if (intent == null || intent.notice.equals(key)) return;
        intent.notice = key;
        player.displayClientMessage(Component.translatable("item.productivebeesgenesis.wireless_terminal.pickup." + key), true);
    }
    private static void fail(ServerPlayer player, State state, Intent intent, Throwable error) {
        state.capture = null;
        if (intent != null) { intent.halted = true; intent.batch = null; }
        com.mojang.logging.LogUtils.getLogger().error("Wireless pickup deposit stopped for {}; original pickup and custody preserved", player.getUUID(), error);
        report(player, intent, "failed");
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var state = STATES.get(player.server); if (state == null) return;
        var intent = state.players.remove(player.getUUID());
        if (intent != null) { intent.request = null; intent.batch = null; intent.generation++; if (intent.queued) state.queue.remove(intent); }
        if (state.capture != null && state.capture.player.equals(player.getUUID())) state.capture = null;
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.remove(event.getServer()); }
    private WirelessPickupService() { }
}
