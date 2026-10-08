package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 服务器线程的短期显示历史；不保存数量，不拥有作业或产物，不保活网格。 */
public final class AeCraftingCompletions {
	private static final int HISTORY = 32, PINS = 9;
	private static final long LIFETIME = 20 * 60 * 10;
	private record Completion(WeakReference<IGrid> grid, AEKey key, long until) { }
	private static final Map<MinecraftServer, Map<UUID, LinkedHashMap<UUID, Completion>>> SERVERS = new HashMap<>();
	public static void completed(ServerPlayer player, IGrid grid, CraftingJobStatusPacket message) {
		var server = player.server;
		if (!server.isSameThread()) throw new IllegalStateException("Crafting completions belong to server thread");
		if (grid == null || !ModConfig.SERVER.beeNetwork.enabled.get() || server.getPlayerList().getPlayer(player.getUUID()) != player
				|| message.status() != CraftingJobStatusPacket.Status.FINISHED || message.requestedAmount() <= 0
				|| message.remainingAmount() != 0 || message.jobId() == null || message.what() == null) return;
		long now = server.overworld().getGameTime();
		var players = SERVERS.computeIfAbsent(server, ignored -> new HashMap<>());
		var jobs = players.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>());
		prune(jobs, now);
		if (jobs.containsKey(message.jobId())) return;
		jobs.put(message.jobId(), new Completion(new WeakReference<>(grid), message.what(), now + LIFETIME));
		if (jobs.size() > HISTORY) jobs.pollFirstEntry();
		AeMeCatalogue.invalidate(server, grid);
	}
	static Map<AEKey, Integer> ranks(ServerPlayer player, IGrid grid) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Crafting completions belong to server thread");
		var players = SERVERS.get(player.server); var jobs = players == null ? null : players.get(player.getUUID());
		if (jobs == null) return Map.of();
		prune(jobs, player.server.overworld().getGameTime());
		var result = new HashMap<AEKey, Integer>();
		for (var entry : jobs.reversed().values()) {
			if (entry.grid().get() == grid && !result.containsKey(entry.key())) result.put(entry.key(), result.size());
			if (result.size() == PINS) break;
		}
		return Map.copyOf(result);
	}
	private static void prune(LinkedHashMap<UUID, Completion> jobs, long now) {
		jobs.values().removeIf(entry -> entry.grid().get() == null || now >= entry.until());
	}
	public static void forget(ServerPlayer player) {
		var players = SERVERS.get(player.server);
		if (players != null) { players.remove(player.getUUID()); if (players.isEmpty()) SERVERS.remove(player.server); }
	}
	public static void clear(MinecraftServer server) { SERVERS.remove(server); }
	private AeCraftingCompletions() { }
}
