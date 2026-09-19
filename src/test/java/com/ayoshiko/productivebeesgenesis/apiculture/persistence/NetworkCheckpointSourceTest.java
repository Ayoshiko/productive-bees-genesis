package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleIndex;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkCheckpointSourceTest {
	private record Fixture(NetworkCheckpoint original, ProductPolicyRegistry policy, ProductLedger ledger,
			TransferStaging staging, ProcessingRuleScheduler scheduler, NetworkCheckpointSource source) { }
	private static Fixture fixture() {
		var original = rich(identity(), 10);
		var dynamic = new DynamicProductRule("test:dynamic", PRODUCT.kind(), PRODUCT.id(), true, PRODUCT::equals);
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(3, List.of(
				new AllowedProductDescriptor(RAW, "test:adapter", "test:recipe"),
				new AllowedProductDescriptor(PRODUCT, "test:adapter", "test:recipe")), List.of(dynamic)));
		policy.restoreDiscoveries(3, original.discoveries());
		var ledger = ProductLedger.restore(policy, 16, original.ledger());
		var staging = TransferStaging.restore(ledger, 16, 100, original.transfers());
		var index = new ProcessingRuleIndex(original.scheduler().rules(), List.of(), Map.of());
		var scheduler = ProcessingRuleScheduler.restore(ledger, index, original.scheduler());
		var source = new NetworkCheckpointSource(original.identity(), ledger, policy, staging, scheduler);
		original.members().forEach(source::putMember); original.lanes().forEach(source::putLane);
		return new Fixture(original, policy, ledger, staging, scheduler, source);
	}
	@Test void allDomainsFreezeTogetherAndSurviveSettlementReloadAndMetadataChanges() {
		var state = fixture(); var frozen = state.source.capture(10); assertEquals(state.original, frozen);
		var paid = state.ledger.pending(frozen.ledger().transactions().getFirst().id()); assertTrue(state.ledger.commit(paid));
		state.staging.resolve(state.staging.pending(frozen.transfers().getFirst().id()), 13);
		state.policy.replace(policy(4).snapshot());
		state.scheduler.replace(new ProcessingRuleIndex(List.of(), List.of(), Map.of()));
		var member = frozen.members().getFirst();
		state.source.putMember(new MemberCapabilitySnapshot(member.memberId(), member.revision(), member.machineId(), member.origin(),
				MemberCapabilitySnapshot.Availability.ONLINE, member.beeSlots(), member.laneCount(), member.alternatives()));
		var lane = frozen.lanes().getFirst();
		state.source.putLane(new VirtualLaneState(lane.memberId(), lane.capabilityRevision(), lane.laneIndex(), lane.capability(), lane.progress() + 1));
		var next = state.source.capture(11);
		assertEquals(state.original, frozen); assertEquals(frozen, CODEC.decode(NetworkCheckpointCodec.encode(frozen)));
		assertEquals(4, next.policyRevision()); assertTrue(next.ledger().transactions().isEmpty());
		assertTrue(next.transfers().isEmpty()); assertTrue(next.discoveries().isEmpty());
		assertEquals(SchedulerCheckpoint.EMPTY, next.scheduler()); assertTrue(next.members().getFirst().online());
		assertEquals(14, next.lanes().getFirst().progress()); assertEquals(ProductAmount.of(13), next.ledger().balances().get(PRODUCT));
		var recovered = ProductLedger.restore(policy(4), 16, frozen.ledger());
		var recoveredPaid = recovered.pending(paid.id()); assertTrue(recovered.commit(recoveredPaid));
		var once = recovered.checkpoint(); assertTrue(recovered.commit(recoveredPaid)); assertEquals(once, recovered.checkpoint());
		assertEquals(ProductAmount.of(7), recovered.available(PRODUCT));
	}
	@Test void rejectsMixedAuthoritiesFuturePoliciesWrongThreadAndReentrantCapture() throws Exception {
		var state = fixture(); var unrelated = new ProductLedger(state.policy, 16);
		assertThrows(IllegalArgumentException.class, () -> new NetworkCheckpointSource(state.original.identity(), unrelated, state.policy, state.staging, state.scheduler));
		var otherStaging = new TransferStaging(state.ledger, 16, 100);
		assertThrows(IllegalArgumentException.class, () -> new NetworkCheckpointSource(state.original.identity(), state.ledger, state.policy,
				otherStaging, new ProcessingRuleScheduler(unrelated, new ProcessingRuleIndex(List.of(), List.of(), Map.of()), ProcessingRuleScheduler.Mode.FAIR)));
		var olderPolicy = policy(2); var futureLedger = ProductLedger.restore(olderPolicy, 16, state.original.ledger());
		assertThrows(IllegalArgumentException.class, () -> new NetworkCheckpointSource(state.original.identity(), futureLedger, olderPolicy,
				new TransferStaging(futureLedger, 16, 100), new ProcessingRuleScheduler(futureLedger,
						new ProcessingRuleIndex(List.of(), List.of(), Map.of()), ProcessingRuleScheduler.Mode.FAIR)));
		try (var executor = Executors.newSingleThreadExecutor()) {
			var failed = executor.submit(() -> state.source.capture(11));
			assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, failed::get).getCause());
			var policyChange = executor.submit(() -> state.policy.replace(policy(4).snapshot()));
			assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, policyChange::get).getCause());
		}
		state.staging.transfer("endpoint", RAW, 1, TransferStaging.Direction.EXPORT, offered -> {
			assertThrows(IllegalStateException.class, () -> state.source.capture(11)); return offered;
		});
		assertEquals(state.original.transfers(), state.source.capture(11).transfers());
	}
	@Test void memberEditsPreserveCrossReferencesAndRejectedChangesAreAtomic() {
		var state = fixture(); var before = state.source.capture(10); var member = before.members().getFirst(); var lane = before.lanes().getFirst();
		assertThrows(IllegalStateException.class, () -> state.source.removeMember(member.memberId()));
		var duplicate = new MemberCapabilitySnapshot(UUID.randomUUID(), member.revision(), member.machineId(), member.origin(),
				member.availability(), member.beeSlots(), member.laneCount(), member.alternatives());
		assertThrows(IllegalArgumentException.class, () -> state.source.putMember(duplicate));
		assertThrows(IllegalArgumentException.class, () -> state.source.putLane(new VirtualLaneState(lane.memberId(), member.revision() + 1,
				lane.laneIndex(), lane.capability(), 0)));
		assertThrows(IllegalArgumentException.class, () -> state.source.putLane(new VirtualLaneState(UUID.randomUUID(), member.revision(), 0, lane.capability(), 0)));
		var smaller = new MemberCapabilitySnapshot(member.memberId(), member.revision() + 1, member.machineId(), member.origin(),
				member.availability(), 0, 1, member.alternatives());
		assertThrows(IllegalArgumentException.class, () -> state.source.putMember(smaller));
		assertThrows(IllegalArgumentException.class, () -> state.source.putMember(new MemberCapabilitySnapshot(member.memberId(), member.revision() - 1,
				member.machineId(), member.origin(), member.availability(), 0, member.laneCount(), member.alternatives())));
		assertThrows(IllegalArgumentException.class, () -> state.source.putMember(new MemberCapabilitySnapshot(member.memberId(), member.revision(),
				member.machineId(), member.origin(), member.availability(), 0, member.laneCount() + 1, member.alternatives())));
		assertEquals(before, state.source.capture(10));
		state.source.removeLane(member.memberId(), lane.laneIndex()); state.source.removeMember(member.memberId());
		assertTrue(state.source.capture(11).members().isEmpty()); assertEquals(before, CODEC.decode(NetworkCheckpointCodec.encode(before)));
	}
	@Test void exportedFrozenCollectionsCannotBypassValidationOrBeMutated() {
		var state = fixture(); var frozen = state.source.capture(10);
		assertThrows(UnsupportedOperationException.class, () -> frozen.ledger().transactions().clear());
		assertThrows(UnsupportedOperationException.class, () -> frozen.transfers().clear());
		assertThrows(UnsupportedOperationException.class, () -> frozen.discoveries().clear());
		assertThrows(UnsupportedOperationException.class, () -> frozen.members().clear());
		assertThrows(UnsupportedOperationException.class, () -> frozen.scheduler().watermarks().clear());
		assertThrows(IllegalArgumentException.class, () -> new LedgerCheckpoint(10, Map.of(), frozen.ledger().transactions()));
		assertThrows(IllegalArgumentException.class, () -> new NetworkCheckpoint(frozen.identity(), 10, 2, frozen.ledger(), frozen.transfers(),
				frozen.discoveries(), frozen.members(), frozen.lanes(), frozen.scheduler()));
		assertThrows(IllegalArgumentException.class, () -> new NetworkCheckpoint(frozen.identity(), 10, 3, frozen.ledger(), frozen.transfers(),
				frozen.discoveries(), List.of(), frozen.lanes(), frozen.scheduler()));
		assertThrows(IllegalArgumentException.class, () -> new SchedulerCheckpoint(frozen.scheduler().rules(), frozen.scheduler().mode(),
				Map.of("disabled", true), "", 0));
	}
	@Test void directorySnapshotKeepsItsRevisionAndIdentitiesAfterLaterAdditions() {
		var directory = NetworkDirectoryData.create(Runnable::run, (path, bytes) -> { }); var first = identity(); directory.add(first);
		var frozen = directory.checkpoint();
		var next = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), first.ownerId(), 1,
				new MemberCapabilitySnapshot.Origin("minecraft:overworld", 3, 64, 1)); directory.add(next);
		assertEquals(1, frozen.revision()); assertEquals(Map.of(first.networkId(), first), frozen.identities());
		assertEquals(2, directory.checkpoint().revision()); assertEquals(2, directory.checkpoint().identities().size());
		assertThrows(UnsupportedOperationException.class, () -> frozen.identities().clear());
	}
}
