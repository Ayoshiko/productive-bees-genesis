package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalSyncBudget;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MeTerminalBudget {
	private static final class State { long tick = -1, window = -1, revision; int expensive, pending; final TerminalSyncBudget bytes = new TerminalSyncBudget(); }
	private static final Map<MinecraftServer, State> STATES = new HashMap<>();
	private static State state(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("ME terminal belongs to server thread");
		return STATES.computeIfAbsent(server, ignored -> new State());
	}
	public static boolean expensive(MinecraftServer server) {
		var state = state(server); long now = server.overworld().getGameTime();
		if (now - state.window >= 20 || state.window < 0) { state.window = now; state.expensive = 0; }
		if (state.tick == now || state.expensive >= 8) return false;
		state.tick = now; state.expensive++; return true;
	}
	public static long revision(MinecraftServer server) { var state = state(server); return state.revision = Math.incrementExact(state.revision); }
	public static boolean plan(MinecraftServer server) { var state = state(server); if (state.pending >= 4) return false; state.pending++; return true; }
	public static void release(MinecraftServer server) { var state = state(server); if (state.pending > 0) state.pending--; }
	public static boolean bytes(MinecraftServer server, int amount) { return state(server).bytes.acquire(server.overworld().getGameTime(), amount); }
	@SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity().containerMenu instanceof MeTerminalHost host) host.meTerminal().close();
		if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
			com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration.forgetCompletions(player);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		STATES.remove(event.getServer()); com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration.clearMeCache(event.getServer());
	}
	private MeTerminalBudget() { }
}
