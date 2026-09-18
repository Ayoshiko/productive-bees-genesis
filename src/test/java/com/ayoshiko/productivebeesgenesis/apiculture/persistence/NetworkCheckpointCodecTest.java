package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkCheckpointCodecTest {
	@Test void roundTripPreservesEveryAuthoritativeDomainAndDoesNotAliasTags() {
		var original = rich(identity(), 11); var tag = NetworkCheckpointCodec.encode(original);
		assertEquals(original, CODEC.decode(tag));
		tag.getList("balances", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 1);
		assertEquals(257, original.ledger().balances().get(RAW).exact().bitLength());
		assertEquals(13, original.lanes().getFirst().progress());
		assertFalse(original.scheduler().rules().get(1).enabled());
	}
	@Test void rejectsMissingFieldsWrongTypesUnknownSchemaAndContent() {
		var base = NetworkCheckpointCodec.encode(rich(identity(), 1));
		for (String field : List.of("identity", "revision", "balances", "transactions", "transfers", "discoveries", "members", "lanes", "scheduler")) {
			var broken = base.copy(); broken.remove(field); assertThrows(IllegalArgumentException.class, () -> CODEC.decode(broken), field);
		}
		var version = base.copy(); version.putInt("schema", 99); assertThrows(IllegalArgumentException.class, () -> CODEC.decode(version));
		var numeric = base.copy(); numeric.putInt("revision", 1); assertThrows(IllegalArgumentException.class, () -> CODEC.decode(numeric));
		var missing = base.copy(); missing.getList("balances", Tag.TAG_COMPOUND).getCompound(0).getCompound("key").putString("id", "missing:product");
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(missing));
	}
	@Test void rejectsDuplicateKeysTransactionsAndMembersInsteadOfMerging() {
		var base = NetworkCheckpointCodec.encode(rich(identity(), 1));
		for (String field : List.of("balances", "transactions", "transfers", "discoveries", "members", "lanes")) {
			var broken = base.copy(); var entries = broken.getList(field, Tag.TAG_COMPOUND); entries.add(entries.getFirst().copy());
			assertThrows(IllegalArgumentException.class, () -> CODEC.decode(broken), field);
		}
	}
	@Test void rejectsNegativeNoncanonicalAndOverReservedAmounts() {
		var base = NetworkCheckpointCodec.encode(rich(identity(), 1));
		var negative = base.copy(); negative.getList("balances", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", -1);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(negative));
		var malformed = base.copy(); malformed.getList("balances", Tag.TAG_COMPOUND).getCompound(0).putByteArray("amount", new byte[]{0, 1});
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(malformed));
		var impossible = new LedgerCheckpoint.Pending(UUID.randomUUID(), 1, LedgerTransaction.State.RESERVED, Map.of(RAW, ProductAmount.of(3)), Map.of());
		assertThrows(IllegalArgumentException.class, () -> new LedgerCheckpoint(1, Map.of(RAW, ProductAmount.of(2)), List.of(impossible)));
	}
	@Test void restoredPaidWorkSettlesOnceAndOldHandlesAreRejected() {
		var ledger = new ProductLedger(policy(1), 8); ledger.insert(RAW, ProductAmount.of(100), ProductLedger.Action.EXECUTE);
		var work = ledger.prepare(Map.of(RAW, ProductAmount.of(30)), Map.of(PRODUCT, ProductAmount.of(3)), 1); ledger.markPaid(work);
		var restored = ProductLedger.restore(policy(2), 8, ledger.checkpoint());
		assertEquals(ProductAmount.of(70), restored.available(RAW));
		assertThrows(IllegalArgumentException.class, () -> restored.commit(work));
		var recovered = restored.pending(work.id()); assertTrue(restored.commit(recovered));
		var after = restored.checkpoint(); assertTrue(restored.commit(recovered)); assertEquals(after, restored.checkpoint());
		assertEquals(ProductAmount.of(3), restored.available(PRODUCT));
	}
	@Test void interruptedTransfersRemainQuarantinedUntilOriginalReceiptArrives() {
		var ledger = new ProductLedger(policy(1), 8);
		var interrupted = new TransferStaging.View(UUID.randomUUID(), "endpoint", RAW, TransferStaging.Direction.EXPORT, 40,
				ProductAmount.of(40), TransferStaging.Phase.CALLING, "");
		var restored = TransferStaging.restore(ledger, 8, 100, List.of(interrupted)); var transfer = restored.pending(interrupted.id());
		assertEquals(TransferStaging.Phase.UNKNOWN, transfer.view().phase()); assertFalse(restored.settle(transfer));
		assertEquals(ProductAmount.ZERO, ledger.available(RAW)); restored.resolve(transfer, 25);
		assertEquals(ProductAmount.of(15), ledger.available(RAW)); assertTrue(restored.settle(transfer));
		assertEquals(ProductAmount.of(15), ledger.available(RAW));
	}
	@Test void loadedUnpaidReservationCannotCommitAcrossPolicyRevision() {
		var ledger = new ProductLedger(policy(1), 8); ledger.insert(RAW, ProductAmount.of(100), ProductLedger.Action.EXECUTE);
		var pending = ledger.prepare(Map.of(RAW, ProductAmount.of(30)), Map.of(PRODUCT, ProductAmount.of(3)), 1);
		var restored = ProductLedger.restore(policy(2), 8, ledger.checkpoint());
		assertFalse(restored.commit(restored.pending(pending.id()))); assertEquals(ProductAmount.of(100), restored.available(RAW));
		assertEquals(ProductAmount.ZERO, restored.available(PRODUCT));
	}
	@Test void schedulerRestoreKeepsDisabledRulesFairCursorAndWatermarkState() {
		var saved = rich(identity(), 1).scheduler();
		var index = new com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleIndex(saved.rules(), List.of(), Map.of());
		var restored = com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler.restore(new ProductLedger(policy(3), 8), index, saved);
		assertEquals(saved, restored.checkpoint());
	}
	@Test void dynamicDiscoveryRestoreRevalidatesPolicyAndNeverPartiallyPublishes() {
		var dynamic = new DynamicProductRule("test:dynamic", PRODUCT.kind(), PRODUCT.id(), true, PRODUCT::equals);
		var snapshot = new ProductPolicySnapshot(3, List.of(), List.of(dynamic));
		var registry = new ProductPolicyRegistry(snapshot);
		var valid = new ProductPolicyRegistry.Discovery("test:dynamic", PRODUCT);
		var invalid = new ProductPolicyRegistry.Discovery("wrong:adapter", PRODUCT);
		assertThrows(IllegalArgumentException.class, () -> registry.restoreDiscoveries(3, java.util.Set.of(valid, invalid)));
		assertTrue(registry.discoveries().isEmpty()); assertFalse(registry.evaluate(PRODUCT).allowed());
		registry.restoreDiscoveries(2, java.util.Set.of(valid)); assertFalse(registry.evaluate(PRODUCT).allowed());
		registry.restoreDiscoveries(3, java.util.Set.of(valid)); assertTrue(registry.evaluate(PRODUCT).allowed());
	}
}
