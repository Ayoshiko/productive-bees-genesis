package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

/** 全服有界轮转的磁力脉冲；仅改变现存实体速度，资产交付仍由原版拾取决定。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class WirelessMagnetService {
	private static final class Pending {
		final UUID player;
		long sequence, acceptedAt = Long.MIN_VALUE, receivedAt, due;
		boolean queued, running, halted;
		int playerEntity;
		ResourceLocation dimension;
		String notice = "";
		WirelessMagnetRequest request;
		Pending(UUID player) { this.player = player; }
	}
	private static final class State {
		final Map<UUID, Pending> players = new HashMap<>();
		final ArrayDeque<Pending> queue = new ArrayDeque<>();
		final Set<UUID> moved = new HashSet<>();
		long window = Long.MIN_VALUE;
		int used;
	}
	private static final Map<MinecraftServer, State> STATES = new HashMap<>();

	public static void handle(ServerPlayer player, WirelessMagnetRequest request) {
		if (!player.server.isSameThread()) return;
		var state = STATES.computeIfAbsent(player.server, ignored -> new State());
		var pending = state.players.computeIfAbsent(player.getUUID(), Pending::new);
		if (request.sequence() <= pending.sequence) return;
		pending.sequence = request.sequence();
		long now = clock(player.server);
		if (!request.enabled()) {
			pending.request = null; pending.halted = false; pending.notice = "";
			return;
		}
		// 新筛选即使受限流拒绝，也不能继续沿用旧规则。
		if (!request.sameIntent(pending.request)) pending.request = null;
		if (pending.running || pending.halted || !worldMenu(player)
				|| pending.acceptedAt != Long.MIN_VALUE && now - pending.acceptedAt < 20 || !TerminalPayloads.allow(player)) return;
		pending.acceptedAt = now; pending.receivedAt = now; pending.request = request;
		pending.playerEntity = player.getId(); pending.dimension = player.level().dimension().location();
		enqueue(state, pending);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
		var server = event.getServer(); if (!server.isSameThread()) return;
		var state = STATES.get(server); if (state == null) return;
		long now = clock(server);
		if (state.window == Long.MIN_VALUE || now - state.window >= 20) {
			state.window = now; state.used = 0; state.moved.clear();
		}
		int checks = Math.min(4, state.queue.size());
		for (int i = 0; i < checks; i++) {
			var pending = state.queue.removeFirst(); pending.queued = false;
			var player = server.getPlayerList().getPlayer(pending.player);
			if (pending.request == null) continue;
			if (pending.halted || state.players.get(pending.player) != pending || player == null
					|| player.getId() != pending.playerEntity || !player.level().dimension().location().equals(pending.dimension)
					|| !worldMenu(player) || now - pending.receivedAt > 40) {
				pending.request = null; continue;
			}
			if (pending.request.filter().rejectsAll()) { report(player, pending, "filter_empty"); continue; }
			if (now < pending.due) { enqueue(state, pending); continue; }
			if (state.used >= 2 || !MeTerminalBudget.expensive(server)) {
				pending.queued = true; state.queue.addFirst(pending); return;
			}
			state.used++; pending.due = now + 20; pending.running = true;
			try { attract(player, state, pending); }
			catch (RuntimeException | LinkageError error) {
				pending.halted = true;
				com.mojang.logging.LogUtils.getLogger().error("Wireless magnet stopped for {}; no automatic retry or item transfer", pending.player, error);
				report(player, pending, "failed");
			} finally {
				pending.running = false;
				if (!pending.halted && pending.request != null && state.players.get(pending.player) == pending) enqueue(state, pending);
			}
			return;
		}
	}

	private static void attract(ServerPlayer player, State state, Pending pending) {
		if (!clearCursor(player)) { report(player, pending, "retained"); return; }
		var request = pending.request; long sequence = pending.sequence;
		var device = new WirelessDeviceSession(player, request.slot());
		if (!device.valid(player) || !device.binding().device().equals(request.device()) || !device.binding().token().equals(request.token())) {
			unavailable(player, pending); return;
		}
		var access = WirelessHostAccess.capture(player, device);
		if (access == null) { unavailable(player, pending); return; }
		var targets = WirelessMagnetTargets.find(player, state.moved, request.filter());
		if (targets.isEmpty()) return;
		if (!current(player, state, pending, sequence) || !access.valid(player) || !clearCursor(player) || !device.charge(player, true)) {
			unavailable(player, pending); return;
		}
		// 只在有候选时收费一次；保留完整来源背包，用私有副本累计本脉冲的容量检查。
		var expected = TerminalCraftingPlan.copy(player.getInventory().items);
		var inventory = TerminalCraftingPlan.copy(expected);
		for (var target : targets) {
			if (!current(player, state, pending, sequence) || !access.valid(player) || !clearCursor(player)) return;
			if (state.moved.contains(target.entity().getUUID()) || !WirelessMagnetTargets.current(player, target, request.filter())) continue;
			var next = TerminalCraftingPlan.copy(inventory);
			if (!TerminalCraftingPlan.insert(next, target.stack()).isEmpty()) continue;
			if (!current(player, state, pending, sequence) || !access.valid(player) || !clearCursor(player)
					|| !ItemStack.listMatches(expected, player.getInventory().items)) return;
			if (!WirelessMagnetTargets.unchanged(target)) continue;
			state.moved.add(target.entity().getUUID());
			target.entity().setDeltaMovement(WirelessMagnetTargets.velocity(player, target));
			target.entity().hurtMarked = true;
			inventory = next;
			report(player, pending, "running");
		}
	}
	private static boolean current(ServerPlayer player, State state, Pending pending, long sequence) {
		return state.players.get(pending.player) == pending && player.server.getPlayerList().getPlayer(pending.player) == player
				&& player.getId() == pending.playerEntity && player.level().dimension().location().equals(pending.dimension)
				&& pending.sequence == sequence && pending.request != null && !pending.halted && worldMenu(player);
	}
	private static boolean worldMenu(ServerPlayer player) {
		return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.getAbilities().instabuild && !player.isShiftKeyDown()
				&& player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
	}
	private static boolean clearCursor(ServerPlayer player) {
		var cursor = TerminalCursor.get(player);
		return !cursor.containerBusy && !TerminalCursorExchange.unknown(player) && cursor.item().isEmpty() && cursor.pending.isEmpty();
	}
	private static void enqueue(State state, Pending pending) {
		if (!pending.queued) { pending.queued = true; state.queue.addLast(pending); }
	}
	private static long clock(MinecraftServer server) { return Integer.toUnsignedLong(server.getTickCount()); }
	private static void unavailable(ServerPlayer player, Pending pending) {
		pending.due = clock(player.server) + 100; report(player, pending, "unavailable");
	}
	private static void report(ServerPlayer player, Pending pending, String key) {
		if (pending.notice.equals(key)) return;
		pending.notice = key;
		player.displayClientMessage(Component.translatable("item.productivebeesgenesis.wireless_terminal.magnet." + key), true);
	}
	@SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isSameThread()) return;
		var state = STATES.get(player.server); if (state == null) return;
		var pending = state.players.remove(player.getUUID());
		if (pending != null) { pending.request = null; if (pending.queued) state.queue.remove(pending); }
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.remove(event.getServer()); }
	private WirelessMagnetService() { }
}
