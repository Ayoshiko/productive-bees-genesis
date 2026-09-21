package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingStockAuthorityTest {
	private final NetworkIdentity identity = CheckpointTestData.identity();
	@Test void authorityPublishesDeltasWhileEnergyOnlyChangesKeepTheIndexReady() {
		var key = CheckpointTestData.key("comb"); var initial = new LedgerCheckpoint(0, Map.of(key, ProductAmount.of(20)), List.of());
		var root = root(initial); var authority = NetworkSavedData.create(root, Runnable::run, (path, payload) -> { });
		var index = authority.processingStock(); while (!index.step()) { }
		var ledger = ProductLedger.restore(new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(), List.of())), 1, initial);
		ledger.extract(key, ProductAmount.of(3), ProductLedger.Action.EXECUTE);
		authority.publish(root(ledger.checkpoint())); assertFalse(index.ready()); assertTrue(index.step());
		assertEquals(ProductAmount.of(17), index.view().amount(key));
		authority.publish(authority.checkpoint().configureEnergy(100).receiveEnergy(13)); assertTrue(index.ready());
		assertEquals(authority.checkpoint().ledger(), index.view().ledger());
		var decoded = CheckpointTestData.CODEC.decode(NetworkCheckpointCodec.encode(authority.checkpoint()));
		assertEquals(authority.checkpoint(), decoded); assertEquals(ProductAmount.of(17), decoded.ledger().available(key));
		authority.closeAuthority(); assertThrows(IllegalStateException.class, authority::processingStock);
	}
	private NetworkCheckpoint root(LedgerCheckpoint ledger) {
		return new NetworkCheckpoint(identity, ledger.revision(), 0, ledger, List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
	}
}
