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
	record Entry(AEKey key, long amount, boolean craftable, String name, String order) { }
	private record Query(String text, com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter filter) { }
	private record StorageSnapshot(long until, List<Entry> entries, Map<Query, List<Entry>> queries) { }
	private static final Map<MinecraftServer, Map<IGrid, StorageSnapshot>> STORAGE = new HashMap<>();
	static List<Entry> stored(ServerPlayer player, IGrid grid, String query, com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter filter) {
		var cache = STORAGE.computeIfAbsent(player.server, ignored -> new WeakHashMap<>()); long now = player.server.overworld().getGameTime();
		var snapshot = cache.get(grid);
		if (snapshot == null || now >= snapshot.until) {
			var amounts = grid.getStorageService().getCachedInventory();
			var craftables = grid.getCraftingService().getCraftables(key -> true);
			var keys = new LinkedHashSet<>(craftables);
			for (var entry : amounts) {
				if (entry.getLongValue() < 0) throw new IllegalStateException("Invalid ME stock amount");
				if (entry.getLongValue() > 0) keys.add(entry.getKey());
			}
			var entries = new ArrayList<Entry>(keys.size());
			for (var key : keys) entries.add(new Entry(key, amounts.get(key), craftables.contains(key),
					key.getDisplayName().getString(), key.getId() + "|" + key.toTagGeneric(player.registryAccess())));
			snapshot = new StorageSnapshot(now + 40, List.copyOf(entries), new LinkedHashMap<>()); cache.put(grid, snapshot);
		}
		var selection = new Query(query.toLowerCase(Locale.ROOT), filter); var old = snapshot.queries.get(selection);
		if (old != null) return old;
		var matches = new ArrayList<Entry>();
		for (var entry : snapshot.entries) {
			var type = entry.key instanceof appeng.api.stacks.AEItemKey ? com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Type.ITEM
					: entry.key instanceof appeng.api.stacks.AEFluidKey ? com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Type.FLUID
					: AeMeEnergy.isFe(entry.key) ? com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Type.ENERGY
					: AeMeChemical.isChemical(entry.key) ? com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Type.CHEMICAL
					: com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Type.OTHER;
			if (filter.type() != com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Type.ALL && filter.type() != type
					|| filter.content() == com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Content.STORED && entry.amount <= 0
					|| filter.content() == com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.Content.CRAFTABLE && !entry.craftable) continue;
			if (!entry.key.getId().toString().toLowerCase(Locale.ROOT).contains(selection.text) && !entry.name.toLowerCase(Locale.ROOT).contains(selection.text)) continue;
			matches.add(entry);
		}
		Comparator<Entry> ordering = switch (filter.sort()) {
			case NAME -> Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT));
			case AMOUNT -> Comparator.comparingLong(Entry::amount);
			case MOD -> Comparator.comparing(entry -> entry.key.getId().getNamespace());
		};
		if (filter.descending()) ordering = ordering.reversed();
		matches.sort(ordering.thenComparing(Entry::order)); var result = List.copyOf(matches);
		if (snapshot.queries.size() >= 8) snapshot.queries.remove(snapshot.queries.keySet().iterator().next());
		snapshot.queries.put(selection, result); return result;
	}
	static void invalidate(MinecraftServer server, IGrid grid) { var cache = STORAGE.get(server); if (cache != null) cache.remove(grid); }
	public static void clear(MinecraftServer server) { SERVERS.remove(server); STORAGE.remove(server); }
	private AeMeCatalogue() { }
}
