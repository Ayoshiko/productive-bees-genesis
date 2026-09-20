package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CentrifugeLaneAllocatorTest {
	private static final ProductKey INPUT = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:comb"), new CompoundTag());
	private static final ProductKey OUTPUT = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:ingot"), new CompoundTag());
	private static ProductPolicyRegistry policy() { return new ProductPolicyRegistry(new ProductPolicySnapshot(0,
			List.of(new AllowedProductDescriptor(INPUT, "test", "comb"), new AllowedProductDescriptor(OUTPUT, "test", "comb")), List.of())); }
	private static CentrifugeRecipePlan plan(int parallel, int ticks, long energy) { return new CentrifugeRecipePlan("test:comb", 0, 0,
			INPUT, ticks, parallel, energy, 1, 0, List.of(new CentrifugeRecipePlan.Output(OUTPUT, 1, 1, 1))); }
	private static NetworkCheckpoint fixture() {
		var origin = new Origin("minecraft:overworld", 0, 64, 0);
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, origin);
		var checkpoint = new NetworkCheckpoint(identity, 0, 0, new LedgerCheckpoint(0, Map.of(INPUT, ProductAmount.of(34)), List.of()),
				List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		for (int i = 1; i <= 2; i++) {
			var tag = new CompoundTag(); tag.putLong("energy", 10000); tag.putLong("energyCapacity", 10000); tag.put("extra", new CompoundTag());
			var image = new AssetImage(tag);
			var claim = new MemberClaim(identity.networkId(), new UUID(0, i), UUID.randomUUID(), new Origin("minecraft:overworld", i, 64, 0), "productivebeesgenesis:mek_centrifuge");
			var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
			checkpoint = checkpoint.withOwnership(sealed);
			var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED); checkpoint = checkpoint.withOwnership(owned);
			checkpoint = checkpoint.withOwnership(owned.withCentrifuge(new CentrifugeWorkState(claim.member(), 0, 1, 10000, 10000, Map.of())));
		}
		return checkpoint;
	}
	private static CentrifugeLaneAllocator.Candidate candidate(int member, long revision, CentrifugeRecipePlan plan) {
		return new CentrifugeLaneAllocator.Candidate(new UUID(0, member), 0, revision, plan);
	}
	@Test void previewIsPureAndStalePreviewCannotClaimAnotherLane() {
		var original = fixture(); var policy = policy();
		var candidates = List.of(candidate(1, 0, plan(17, 10, 2)), candidate(2, 0, plan(17, 5, 3)));
		var first = CentrifugeLaneAllocator.select(original, policy, candidates, 0, 1, 100);
		var competing = CentrifugeLaneAllocator.select(original, policy, candidates, 1, 1, 100);
		assertEquals(1, first.inspected()); assertEquals(1, first.nextCursor()); assertEquals(17, first.selection().operations());
		assertTrue(original.ownedMachines().get(new UUID(0, 1)).centrifuge().drained());
		var current = CentrifugeLaneAllocator.commit(original, policy, first.selection(), 10);
		assertEquals(ProductAmount.of(17), current.ledger().balances().get(INPUT));
		assertSame(current, CentrifugeLaneAllocator.commit(current, policy, competing.selection(), 11));
		assertSame(current, CentrifugeLaneAllocator.commit(current, policy, first.selection(), 10));
		var second = CentrifugeLaneAllocator.select(current, policy, candidates, first.nextCursor(), 2, 100);
		current = CentrifugeLaneAllocator.commit(current, policy, second.selection(), 11);
		assertFalse(current.ledger().balances().containsKey(INPUT));
		assertEquals(34, current.ownedMachines().get(new UUID(0, 1)).centrifuge().jobs().get(0).plan().energyPerTick(17));
		assertEquals(51, current.ownedMachines().get(new UUID(0, 2)).centrifuge().jobs().get(0).plan().energyPerTick(17));
		assertNull(CentrifugeLaneAllocator.select(current, policy, candidates, 0, 100, 100).selection());
	}
	@Test void budgetsAndCursorSkipStaleMissingAndUnavailableLanes() {
		var checkpoint = fixture(); var policy = policy();
		var candidates = List.of(candidate(8, 0, plan(17, 10, 2)), candidate(1, 99, plan(17, 10, 2)), candidate(2, 0, plan(17, 5, 3)));
		var stopped = CentrifugeLaneAllocator.select(checkpoint, policy, candidates, 0, 2, 100);
		assertNull(stopped.selection()); assertEquals(2, stopped.inspected()); assertEquals(2, stopped.nextCursor());
		var ready = CentrifugeLaneAllocator.select(checkpoint, policy, candidates, stopped.nextCursor(), 1, 3);
		assertEquals(3, ready.selection().operations()); assertEquals(0, ready.nextCursor());
		assertEquals(0, CentrifugeLaneAllocator.select(checkpoint, policy, candidates, 0, 0, 100).inspected());
		assertNull(CentrifugeLaneAllocator.select(checkpoint, policy, candidates, 0, 3, 0).selection());
		assertThrows(IllegalArgumentException.class, () -> CentrifugeLaneAllocator.select(checkpoint, policy, candidates, 0, -1, 100));
	}
	@Test void energyBoundsThePinnedBatch() {
		var checkpoint = fixture(); var policy = policy();
		// 当前 10,000 FE 只能支撑一份 6,000 FE/t 的新作业，不能为第二份饱和计价。
		var scan = CentrifugeLaneAllocator.select(checkpoint, policy, List.of(candidate(1, 0, plan(17, 10, 6000))), 0, 1, 100);
		assertEquals(1, scan.selection().operations());
		var current = CentrifugeLaneAllocator.commit(checkpoint, policy, scan.selection(), 1);
		var record = current.ownedMachines().get(new UUID(0, 1));
		var progress = CentrifugeWorkTransaction.advance(record.centrifuge(), current.ledger(), 0, 10, true, true);
		assertEquals(1, progress.executedTicks()); assertEquals(4000, progress.state().energy());
		assertNull(CentrifugeWorkTransaction.advance(progress.state(), progress.ledger(), 0, 10, true, true));
	}
	@Test void changedPolicyInvalidatesSelection() {
		var checkpoint = fixture(); var policy = policy();
		var scan = CentrifugeLaneAllocator.select(checkpoint, policy, List.of(candidate(1, 0, plan(4, 10, 2))), 0, 1, 10);
		policy.replace(new ProductPolicySnapshot(1, List.of(), List.of()));
		assertSame(checkpoint, CentrifugeLaneAllocator.commit(checkpoint, policy, scan.selection(), 1));
	}
	@Test void existingLedgerReservationsAreNotAssignedAgain() {
		var original = fixture(); var policy = policy();
		var reserved = new LedgerCheckpoint.Pending(UUID.randomUUID(), 0, LedgerTransaction.State.RESERVED,
				Map.of(INPUT, ProductAmount.of(33)), Map.of());
		var tag = NetworkCheckpointCodec.encode(original);
		var accounts = new LedgerCheckpoint(original.ledger().revision() + 1, original.ledger().balances(), List.of(reserved));
		// 通过真实 codec 组合受保护余额，避免绕过所有权根的验证。
		var empty = new NetworkCheckpoint(original.identity(), original.revision(), 0, accounts,
				List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		var accountTag = NetworkCheckpointCodec.encode(empty);
		tag.put("transactions", accountTag.get("transactions"));
		tag.putLong("ledgerRevision", accounts.revision());
		var checkpoint = new NetworkCheckpointCodec(key -> { }).decode(tag);
		var scan = CentrifugeLaneAllocator.select(checkpoint, policy, List.of(candidate(1, 0, plan(17, 10, 2))), 0, 1, 100);
		assertEquals(1, scan.selection().operations());
	}
	@Test void alternateRecipesDoNotCreateAdditionalPhysicalLanes() {
		var original = fixture(); var policy = policy();
		var first = candidate(1, 0, plan(4, 10, 2)); var alternate = candidate(1, 0, plan(8, 5, 3));
		var chosen = CentrifugeLaneAllocator.select(original, policy, List.of(first, alternate), 0, 2, 100);
		var current = CentrifugeLaneAllocator.commit(original, policy, chosen.selection(), 1);
		assertNull(CentrifugeLaneAllocator.select(current, policy, List.of(alternate), 0, 1, 100).selection());
		assertEquals(1, current.ownedMachines().get(first.member()).centrifuge().jobs().size());
	}
}
