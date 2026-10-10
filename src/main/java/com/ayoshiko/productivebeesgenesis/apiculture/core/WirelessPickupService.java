package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
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
        long sequence, generation, acceptedAt = Long.MIN_VALUE, receivedAt;
        WirelessPickupRequest request;
        boolean halted;
        String notice = "";
    }
    private static final class Capture {
        final UUID player, entity;
        final int playerEntity;
        final ResourceLocation dimension;
        final long tick, generation;
        final WirelessPickupRequest request;
        final WirelessTerminalItem.Binding binding;
        final List<ItemStack> before;
        final ItemStack sample;
        List<ItemStack> after;
        ItemStack wanted = ItemStack.EMPTY;
        Capture(ServerPlayer player, UUID entity, ItemStack sample, Intent intent, long tick, WirelessTerminalItem.Binding binding) {
            this.player = player.getUUID(); playerEntity = player.getId(); this.entity = entity; dimension = player.level().dimension().location();
            this.tick = tick; generation = intent.generation; request = intent.request; this.binding = binding;
            before = TerminalCraftingPlan.copy(player.getInventory().items); this.sample = sample.copyWithCount(1);
        }
    }
    private static final class State {
        final Map<UUID, Intent> players = new HashMap<>();
        Capture capture;
        long window = Long.MIN_VALUE, capturedAt = Long.MIN_VALUE;
        int used;
    }
    private static final Map<MinecraftServer, State> STATES = new HashMap<>();

    public static void handle(ServerPlayer player, WirelessPickupRequest request) {
        if (!player.server.isSameThread()) return;
        var state = STATES.computeIfAbsent(player.server, ignored -> new State());
        var intent = state.players.computeIfAbsent(player.getUUID(), ignored -> new Intent());
        if (request.sequence() <= intent.sequence) return;
        intent.sequence = request.sequence(); long now = clock(player.server);
        if (!request.enabled()) {
            intent.generation++; intent.request = null; intent.halted = false; intent.notice = "";
            if (state.capture != null && state.capture.player.equals(player.getUUID())) state.capture = null;
            return;
        }
        // 在限流之前撤销旧捕获；改变筛选不能让旧规则的待办继续入网。
        if (!request.sameIntent(intent.request)) {
            intent.generation++; intent.request = null;
            if (state.capture != null && state.capture.player.equals(player.getUUID())) state.capture = null;
        }
        if (intent.halted || !worldMenu(player) || intent.acceptedAt != Long.MIN_VALUE && now - intent.acceptedAt < 20
                || !TerminalPayloads.allow(player)) return;
        intent.request = request; intent.acceptedAt = now; intent.receivedAt = now;
        if (request.filter().rejectsAll()) report(player, intent, "filter_empty");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void before(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !player.server.isSameThread() || !worldMenu(player)) return;
        var state = STATES.get(player.server); if (state == null || state.capture != null) return;
        var intent = state.players.get(player.getUUID()); long now = clock(player.server);
        if (!active(intent, now) || state.capturedAt == now) return;
        var entity = event.getItemEntity();
        if (event.canPickup().isFalse() || entity.isRemoved() || entity.level() != player.level() || entity.hasPickUpDelay()
                || entity.getTarget() != null && !entity.getTarget().equals(player.getUUID())) return;
        try {
            var sample = entity.getItem();
            if (sample.isEmpty() || sample.getItem() instanceof WirelessTerminalItem) return;
            if (!intent.request.filter().allows(sample)) { report(player, intent, intent.request.filter().rejectsAll() ? "filter_empty" : "filtered"); return; }
            if (!clearCursor(player)) return;
            var device = device(player, intent.request); if (device == null) return;
            if (state.window == Long.MIN_VALUE || now - state.window >= 20) { state.window = now; state.used = 0; }
            if (state.used >= 2 || !MeTerminalBudget.expensive(player.server)) return;
            state.used++; state.capturedAt = now;
            state.capture = new Capture(player, entity.getUUID(), sample, intent, now, device.binding());
        } catch (RuntimeException | LinkageError error) { fail(player, state, intent, error); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void after(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !player.server.isSameThread()) return;
        var state = STATES.get(player.server); if (state == null) return;
        var capture = state.capture;
        if (capture == null || capture.after != null || !capture.player.equals(player.getUUID())
                || !capture.entity.equals(event.getItemEntity().getUUID())) return;
        var intent = state.players.get(player.getUUID());
        try {
            if (!current(player, intent, capture) || !worldMenu(player)) { state.capture = null; return; }
            var original = event.getOriginalStack(); var remaining = event.getCurrentStack();
            if (!ItemStack.isSameItemSameComponents(capture.sample, original) || !remaining.isEmpty()
                    && (!ItemStack.isSameItemSameComponents(original, remaining) || remaining.getCount() > original.getCount())) { state.capture = null; return; }
            long received = (long) original.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());
            var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
            long delta = count(inventory, capture.sample) - count(capture.before, capture.sample);
            if (received <= 0 || delta <= 0) { state.capture = null; return; }
            int amount = (int) Math.min(Math.min(received, delta), Math.min(64, original.getMaxStackSize()));
            if (amount <= 0) { state.capture = null; return; }
            capture.after = inventory; capture.wanted = capture.sample.copyWithCount(amount);
        } catch (RuntimeException | LinkageError error) { fail(player, state, intent, error); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void finish(ServerTickEvent.Post event) {
        var server = event.getServer(); if (!server.isSameThread()) return;
        var state = STATES.get(server); if (state == null) return;
        var capture = state.capture; state.capture = null; // 先释放待办，任何回调都不能重入同一份拾取。
        if (capture == null || capture.after == null) return;
        var player = server.getPlayerList().getPlayer(capture.player); if (player == null) return;
        var intent = state.players.get(capture.player);
        try {
            if (!current(player, intent, capture) || !worldMenu(player)) return;
            if (!ItemStack.listMatches(capture.after, player.getInventory().items)) { report(player, intent, "changed"); return; }
            if (!clearCursor(player)) { report(player, intent, "retained"); return; }
            var device = device(player, capture.request);
            var access = device == null || !device.binding().equals(capture.binding) ? null : WirelessInventoryAccess.capture(player, device);
            if (access == null || !access.valid(player)) { report(player, intent, "unavailable"); return; }
            if (!current(player, intent, capture) || !worldMenu(player) || !ItemStack.listMatches(capture.after, player.getInventory().items)
                    || !device.charge(player, true)) { report(player, intent, "unavailable"); return; }
            // 已核对的设备收费只改变原设备组件；其它背包变动不能挪用旧物品补足拾取增量。
            if (!sameExceptDevice(capture.after, player.getInventory().items, capture.request.slot())) { report(player, intent, "changed"); return; }
            var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
            var result = TerminalCursorExchange.depositInventory(player, player.inventoryMenu, inventory, capture.wanted, access.source().description(), requested -> {
                if (!current(player, intent, capture) || !worldMenu(player) || !access.valid(player)) return 0;
                return access.source().insert(requested);
            });
            switch (result.outcome()) {
                case MOVED -> report(player, intent, result.amount() == capture.wanted.getCount() ? "stored" : "partial");
                case NO_SPACE -> report(player, intent, "rejected");
                case RETAINED, UNKNOWN -> { intent.halted = true; report(player, intent, "retained"); }
                case INVALID -> report(player, intent, "changed");
            }
        } catch (RuntimeException | LinkageError error) { fail(player, state, intent, error); }
    }

    private static boolean active(Intent intent, long now) {
        return intent != null && !intent.halted && intent.request != null && now - intent.receivedAt <= 40;
    }
    private static boolean current(ServerPlayer player, Intent intent, Capture capture) {
        long now = clock(player.server);
        return active(intent, now) && player.getId() == capture.playerEntity && now == capture.tick && intent.generation == capture.generation
                && capture.request.sameIntent(intent.request) && intent.request.filter().allows(capture.sample) && capture.dimension.equals(player.level().dimension().location());
    }
    private static WirelessDeviceSession device(ServerPlayer player, WirelessPickupRequest request) {
        var device = new WirelessDeviceSession(player, request.slot());
        return device.valid(player) && device.binding().device().equals(request.device()) && device.binding().token().equals(request.token()) ? device : null;
    }
    private static long count(List<ItemStack> inventory, ItemStack sample) {
        long total = 0;
        for (var stack : inventory) if (ItemStack.isSameItemSameComponents(stack, sample)) total += stack.getCount();
        return total;
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
        if (intent != null) intent.halted = true;
        com.mojang.logging.LogUtils.getLogger().error("Wireless pickup deposit stopped for {}; original pickup and custody preserved", player.getUUID(), error);
        report(player, intent, "failed");
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var state = STATES.get(player.server); if (state == null) return;
        var intent = state.players.remove(player.getUUID());
        if (intent != null) { intent.request = null; intent.generation++; }
        if (state.capture != null && state.capture.player.equals(player.getUUID())) state.capture = null;
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.remove(event.getServer()); }
    private WirelessPickupService() { }
}
