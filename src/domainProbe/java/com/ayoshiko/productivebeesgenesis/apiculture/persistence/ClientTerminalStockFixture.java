package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;

/** 隔离开发夹具：保留同一个所有权根，设置终端有限取回的历史库存。 */
public final class ClientTerminalStockFixture {
	public static void seed(NetworkSavedData data, Map<ProductKey, ProductAmount> stock) {
		var current = data.checkpoint();
		data.publish(new NetworkCheckpoint(current.identity(), current.revision() + 1, current.policyRevision(),
				new LedgerCheckpoint(current.ledger().revision() + 1, stock, List.of()), current.transfers(), current.discoveries(),
				current.members(), current.lanes(), current.scheduler(), current.energy()).restoredOwnership(current.ownedMachines()));
	}
	private ClientTerminalStockFixture() { }
}
