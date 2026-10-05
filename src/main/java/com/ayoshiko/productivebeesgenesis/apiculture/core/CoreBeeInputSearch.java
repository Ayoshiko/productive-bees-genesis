package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.ApiaryRank;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** 菜单与世界入口共用的有界候选扫描；候选不持资产，实际交付仍须重新验证。 */
final class CoreBeeInputSearch {
	record Target(UUID member, int slot, long revision) { }
	private ApiaryRank cursor;
	private Object orderToken;
	private boolean exhausted;
	boolean exhausted() { return exhausted; }
	Target next(NetworkCoreBlockEntity core, ServerPlayer player) {
		var order = core.apiaryIndex().step(core);
		if (order == null) return null;
		if (orderToken != order.token()) { orderToken = order.token(); cursor = null; exhausted = false; }
		for (int i = 0; i < 4; i++) {
			var entry = cursor == null ? order.sorted().firstEntry() : order.sorted().higherEntry(cursor);
			if (entry == null) { exhausted = true; return null; }
			cursor = entry.getKey();
			var authority = core.ownership().readyAuthority();
			var record = authority.checkpoint().ownedMachines().get(entry.getValue());
			if (record == null || record.bees() == null) continue;
			var hive = ManagedProductionAccess.member(player.serverLevel(), authority, NetworkPersistence.directory(player.server), record, TileEntityMekApiary.class);
			if (hive == null || !hive.canFunction()) continue;
			int slot = emptySlot(record.bees(), -1);
			if (slot >= 0) return new Target(entry.getValue(), slot, record.bees().revision());
		}
		return null;
	}
	static int emptySlot(BeeMemberState state, int preferred) {
		if (preferred >= 0 && preferred < 3 && state.bees().stream().noneMatch(bee -> bee.slot() == preferred)) return preferred;
		for (int i = 0; i < 3; i++) { int slot = i; if (state.bees().stream().noneMatch(bee -> bee.slot() == slot)) return slot; }
		return -1;
	}
}
