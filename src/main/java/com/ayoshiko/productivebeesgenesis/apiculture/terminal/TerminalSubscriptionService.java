package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WorldBeeInputRequest;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.FairDueQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 全服预算下的公平菜单订阅；队列仅持 UUID／会话值，不保留玩家、世界或菜单。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class TerminalSubscriptionService {
	private record Key(UUID player, UUID session, boolean world) { }
	private static final class Session {
		final FairDueQueue<Key> due = new FairDueQueue<>();
		final TerminalSyncBudget bytes = new TerminalSyncBudget();
		final Map<UUID, WorldBeeInputRequest> world = new HashMap<>();
		boolean stepping;
	}
	private static final Map<MinecraftServer, Session> SERVERS = new HashMap<>();
	public static void watch(ServerPlayer player, NetworkCoreMenu menu) {
		var server = player.server; check(server);
		SERVERS.computeIfAbsent(server, ignored -> new Session()).due.wake(new Key(player.getUUID(), menu.terminalSession(), false), server.overworld().getGameTime());
	}
	public static void remove(ServerPlayer player, NetworkCoreMenu menu) {
		check(player.server); var session = SERVERS.get(player.server);
		if (session != null) session.due.remove(new Key(player.getUUID(), menu.terminalSession(), false));
	}
	public static boolean beginWorldInput(ServerPlayer player, net.minecraft.world.InteractionHand hand, net.minecraft.world.level.block.entity.BlockEntity source) {
		check(player.server);
		var state = SERVERS.computeIfAbsent(player.server, ignored -> new Session());
		if (state.world.containsKey(player.getUUID()) || !TerminalPayloads.allow(player)) return false;
		var request = new WorldBeeInputRequest(player, hand, source); state.world.put(player.getUUID(), request);
		state.due.wake(new Key(player.getUUID(), request.id(), true), player.server.overworld().getGameTime()); return true;
	}
	public static void cancelWorldInput(ServerPlayer player) {
		check(player.server); var state = SERVERS.get(player.server); if (state == null) return;
		var request = state.world.remove(player.getUUID());
		if (request != null) { request.cancel(); state.due.remove(new Key(player.getUUID(), request.id(), true)); }
	}
	public static boolean step(MinecraftServer server) {
		check(server); var state = SERVERS.get(server); if (state == null || state.stepping) return false;
		long now = server.overworld().getGameTime(); var key = state.due.poll(now); if (key == null) return false;
		state.stepping = true;
		try {
			var player = server.getPlayerList().getPlayer(key.player());
			if (player == null) { if (key.world()) state.world.remove(key.player()); return true; }
			if (key.world()) {
				var request = state.world.get(key.player()); if (request == null || !request.id().equals(key.session())) return true;
				if (!state.bytes.acquire(now, 256)) { state.due.offer(key, now + 1); return true; }
				try {
					var result = request.step(player, now);
					if (state.world.get(key.player()) == request) {
						if (result == null) state.due.offer(key, now + 1);
						else { state.world.remove(key.player(), request); WorldBeeInputRequest.feedback(player, result); }
					}
				} catch (RuntimeException failure) {
					request.cancel();
					if (state.world.remove(key.player(), request)) state.due.remove(key);
					com.mojang.logging.LogUtils.getLogger().error("World bee input {} stopped without retry", key.session(), failure);
				}
				return true;
			}
			if (!(player.containerMenu instanceof NetworkCoreMenu menu) || !menu.terminalSession().equals(key.session())) return true;
			try {
				long next = menu.stepSubscription(player, state.bytes, now);
				if (next != Long.MAX_VALUE) state.due.offer(key, Math.max(now + 1, next));
			} catch (RuntimeException failure) {
				menu.cancelSubscription(player);
				com.mojang.logging.LogUtils.getLogger().error("Terminal subscription {} paused after a query failure", key.session(), failure);
			}
			return true;
		} finally { state.stepping = false; }
	}
	@SubscribeEvent public static void containerOpened(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open event) {
		if (event.getEntity() instanceof ServerPlayer player) cancelWorldInput(player);
	}
	@SubscribeEvent public static void loggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) cancelWorldInput(player);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { SERVERS.remove(event.getServer()); }
	private static void check(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Terminal subscriptions belong to the server thread"); }
	private TerminalSubscriptionService() { }
}
