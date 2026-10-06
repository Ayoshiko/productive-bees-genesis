package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 原生 API 会完整枚举；同网格共享 40 tick 快照，弱网格键且停服清理。 */
public final class AeMeCatalogue {
	private record Snapshot(long until, List<AEKey> keys) { }
	private record Ordered(AEKey key, String order) { }
	private static final Map<MinecraftServer, Map<IGrid, Snapshot>> SERVERS = new HashMap<>();
	static List<AEKey> get(ServerPlayer player, IGrid grid) {
		var cache = SERVERS.computeIfAbsent(player.server, ignored -> new WeakHashMap<>()); long now = player.server.overworld().getGameTime();
		var old = cache.get(grid); if (old != null && now < old.until) return old.keys;
		var keys = grid.getCraftingService().getCraftables(key -> key instanceof appeng.api.stacks.AEItemKey || key instanceof appeng.api.stacks.AEFluidKey);
		var ordered = new ArrayList<Ordered>(keys.size());
		for (var key : keys) ordered.add(new Ordered(key, key.getId() + "|" + key.toTagGeneric(player.registryAccess())));
		ordered.sort(Comparator.comparing(Ordered::order));
		var result = ordered.stream().map(Ordered::key).toList(); cache.put(grid, new Snapshot(now + 40, result)); return result;
	}
	public static void clear(MinecraftServer server) { SERVERS.remove(server); }
	private AeMeCatalogue() { }
}
