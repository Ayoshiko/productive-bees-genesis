package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedBeeAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.ApiaryRank;
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
			if (record == null) continue;
			if (!record.beeReady()) {
				if (!ManagedBeeAccess.activate(player.serverLevel(), authority, record)) continue;
				record = authority.checkpoint().ownedMachines().get(entry.getValue());
			}
			var access = ManagedBeeAccess.capture(player.serverLevel(), authority, record);
			if (access == null || !access.canFunction()) continue;
			int slot = emptySlot(record, -1);
			if (slot >= 0) return new Target(entry.getValue(), slot, record.beeRevision());
		}
		return null;
	}
	static int emptySlot(OwnedMachineRecord record, int preferred) {
		return emptySlot(record.beeRecords(), record.workLayout().beeSlots(), preferred);
	}
	static int emptySlot(BeeMemberState state, int preferred) { return emptySlot(state.bees(), state.slotCount(), preferred); }
	private static int emptySlot(java.util.List<com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord> bees, int slots, int preferred) {
		if (preferred >= 0 && preferred < slots && bees.stream().noneMatch(bee -> bee.slot() == preferred)) return preferred;
		for (int i = 0; i < slots; i++) { int slot = i; if (bees.stream().noneMatch(bee -> bee.slot() == slot)) return slot; }
		return -1;
	}
}
