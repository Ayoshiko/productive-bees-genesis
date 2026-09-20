package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BeeProductionCheckpointTest {
	@TempDir Path folder;
	private static final ProductKey COMB = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("productivebees:configurable_honeycomb"), new CompoundTag());
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> { if (!COMB.equals(key)) throw new IllegalArgumentException("Missing bee output"); });
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private record Fixture(NetworkCheckpoint before, OwnedMachineRecord original, BeeMemberState bees) {
		NetworkCheckpoint active() { return before.withOwnership(original.withBees(bees)); }
	}
	private static Fixture fixture(long pending, int productivity) {
		var identity = CheckpointTestData.identity(); var member = UUID.randomUUID();
		var claim = new MemberClaim(identity.networkId(), member, UUID.randomUUID(), new Origin("minecraft:overworld", 1, 64, 2), "productivebeesgenesis:mek_apiary");
		var entity = new CompoundTag(); entity.putString("id", "productivebees:configurable_bee"); entity.putString("type", "productivebees:iron"); entity.putString("custom", "原始完整数据");
		var slot = new CompoundTag(); slot.putInt("slot_index", 2); slot.put("entity_data", entity); slot.putInt("ticks_in_hive", 0);
		var slots = new ListTag(); slots.add(slot); var extra = new CompoundTag(); extra.put(BeeAssetProjection.SLOTS, slots);
		if (pending > 0) { var paid = new CompoundTag(); paid.putIntArray("counts", new int[]{0, 0, Math.toIntExact(pending)}); extra.put(BeeAssetProjection.PENDING, paid); }
		var image = new CompoundTag(); image.put("extra", extra); image.putLong("energy", 1000); image.putLong("energyCapacity", 2000);
		var assets = new AssetImage(image); var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, assets, assets.fingerprint(), "");
		var original = sealed.phase(OwnedMachineRecord.Phase.OWNED);
		var before = NetworkCheckpoint.empty(identity).withOwnership(sealed).withOwnership(original);
		var plan = new StaticBeePlan("productivebees:iron", "productivebees:bee_produce/iron", 0, 0, 5, 10, productivity, true,
				BeeWorkConditions.Traits.DEFAULT, COMB, 1);
		var bee = new BeeRecord(BeeRecord.identity(member, 2), member, 2, new AssetImage(slot), plan, 0, 0, pending, ProductAmount.ZERO);
		return new Fixture(before, original, new BeeMemberState(member, 0, 1000, 2000, List.of(bee)));
	}
	private static BeeWorkExecutor.Context context() { return new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false)); }
	private static OwnedMachineRecord record(NetworkCheckpoint checkpoint, Fixture fixture) { return checkpoint.ownedMachines().get(fixture.bees.member()); }
	private static NetworkCheckpoint publishCandidate(NetworkCheckpoint checkpoint, Fixture fixture, BeeWorkExecutor.Result result) {
		assertEquals(BeeWorkExecutor.Status.READY, result.status());
		return checkpoint.withOwnership(record(checkpoint, fixture).withBees(result.candidate()));
	}
	@Test void migrationRemovesOldOwnersAndReturnRequiresDraining() {
		var f = fixture(0, 3); var active = f.active(); var owned = record(active, f);
		assertEquals(0, owned.assets().copy().getLong("energy"));
		assertTrue(owned.assets().copy().getCompound("extra").getList(BeeAssetProjection.SLOTS, 10).isEmpty());
		assertEquals(f.original.assets(), owned.returnImage());
		var candidate = BeeWorkExecutor.advance(owned.bees(), 2, 0, context(), 12, 1);
		assertEquals(1000, owned.bees().energy()); assertEquals(0, owned.bees().bee(2).progress());
		var paid = publishCandidate(active, f, candidate); var bee = record(paid, f).bees().bee(2);
		assertEquals(880, record(paid, f).bees().energy()); assertEquals(2, bee.progress());
		assertEquals(1, bee.pendingCycles()); assertEquals(ProductAmount.of(4), bee.frozen());
		assertThrows(IllegalStateException.class, () -> record(paid, f).returnImage());
		var drained = publishCandidate(paid, f, BeeWorkExecutor.advance(record(paid, f).bees(), 2, bee.revision(), context(), 0, 10));
		var settled = drained.settleBee(f.bees.member(), 2, record(drained, f).bees().bee(2).revision());
		assertEquals(ProductAmount.of(8), settled.ledger().balances().get(COMB));
		var returning = record(settled, f).phase(OwnedMachineRecord.Phase.RETURNING);
		assertNull(returning.bees()); assertEquals(880, returning.assets().copy().getLong("energy"));
		var returnedSlot = returning.assets().copy().getCompound("extra").getList(BeeAssetProjection.SLOTS, 10).getCompound(0);
		assertEquals(2, returnedSlot.getInt("slot_index")); assertEquals(2, returnedSlot.getInt("ticks_in_hive"));
		assertEquals("原始完整数据", returnedSlot.getCompound("entity_data").getString("custom"));
		assertTrue(settled.withOwnership(returning).withOwnership(returning.phase(OwnedMachineRecord.Phase.RETURNED)).ownedMachines().get(f.bees.member()).assets().isEmpty());
	}
	@Test void restartAtEveryPaidBoundaryAndDuplicateSettlementPreserveConservation() throws Exception {
		var f = fixture(0, 0); var current = roundTrip(f.active(), "migrated");
		current = roundTrip(publishCandidate(current, f, BeeWorkExecutor.advance(record(current, f).bees(), 2, 0, context(), 3, 0)), "partial");
		current = roundTrip(publishCandidate(current, f, BeeWorkExecutor.advance(record(current, f).bees(), 2, 1, context(), 9, 0)), "paid-unsampled");
		assertEquals(2, record(current, f).bees().bee(2).pendingCycles());
		current = roundTrip(publishCandidate(current, f, BeeWorkExecutor.advance(record(current, f).bees(), 2, 2, context(), 0, 1)), "frozen");
		long frozenRevision = record(current, f).bees().bee(2).revision();
		current = roundTrip(current.settleBee(f.bees.member(), 2, frozenRevision), "credited");
		assertSame(current, current.settleBee(f.bees.member(), 2, frozenRevision));
		assertEquals(ProductAmount.of(1), current.ledger().balances().get(COMB));
		var bee = record(current, f).bees().bee(2);
		current = publishCandidate(current, f, BeeWorkExecutor.advance(record(current, f).bees(), 2, bee.revision(), context(), 0, 1));
		current = current.settleBee(f.bees.member(), 2, record(current, f).bees().bee(2).revision());
		assertEquals(ProductAmount.of(2), current.ledger().balances().get(COMB)); assertEquals(880, record(current, f).bees().energy());
		assertEquals(2, record(current, f).bees().bee(2).progress());
	}
	@Test void missingConditionsStaleRevisionsAndBudgetDoNotCharge() {
		var state = fixture(0, 0).bees;
		var day = context().environment();
		var contexts = List.of(new BeeWorkExecutor.Context(false, true, true, 0, 0, day), new BeeWorkExecutor.Context(true, false, true, 0, 0, day),
				new BeeWorkExecutor.Context(true, true, false, 0, 0, day), new BeeWorkExecutor.Context(true, true, true, 1, 0, day),
				new BeeWorkExecutor.Context(true, true, true, 0, 1, day), new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, true, false, false)));
		for (var context : contexts) assertSame(state, BeeWorkExecutor.advance(state, 2, 0, context, 10, 1).candidate());
		assertEquals(BeeWorkExecutor.Status.ENERGY, BeeWorkExecutor.advance(state, 2, 0, context(), 101, 1).status());
		assertSame(state, BeeWorkExecutor.advance(state, 2, 99, context(), 10, 1).candidate());
		assertSame(state, BeeWorkExecutor.advance(state, 2, 0, context(), 0, 0).candidate());
		var paid = BeeWorkExecutor.advance(state, 2, 0, context(), 10, 0).candidate();
		assertEquals(BeeWorkExecutor.Status.DRAIN_FIRST, BeeWorkExecutor.advance(paid, 2, 1, context(), 10, 1).status());
		var changed = new BeeWorkExecutor.Context(true, false, false, 99, 99, day);
		var recovered = BeeWorkExecutor.advance(paid, 2, 1, changed, 0, 1);
		assertEquals(BeeWorkExecutor.Status.READY, recovered.status()); assertEquals(900, recovered.candidate().energy());
	}
	@Test void frozenExactQuantityAndLongBacklogSurviveRestore() throws Exception {
		var f = fixture(0, 0); var bee = f.bees.bee(2);
		var huge = ProductAmount.of(BigInteger.ONE.shiftLeft(90));
		var state = f.bees.update(bee.work(0, Long.MAX_VALUE, huge), 1000);
		var checkpoint = f.active(); checkpoint = checkpoint.withOwnership(record(checkpoint, f).withBees(state));
		var read = roundTrip(checkpoint, "large");
		assertEquals(Long.MAX_VALUE, record(read, f).bees().bee(2).pendingCycles());
		var result = BeeWorkExecutor.advance(record(read, f).bees(), 2, 1, context(), 0, Integer.MAX_VALUE);
		assertEquals(huge.add(ProductAmount.of(Integer.MAX_VALUE)), result.candidate().bee(2).frozen());
		assertEquals(Long.MAX_VALUE - Integer.MAX_VALUE, result.candidate().bee(2).pendingCycles());
		assertEquals(huge, read.settleBee(f.bees.member(), 2, 1).ledger().balances().get(COMB));
	}
	@Test void malformedBeeStateCannotDisappearOrBecomeAnEmptyAuthority() {
		var f = fixture(0, 0); var original = NetworkCheckpointCodec.encode(f.active());
		for (String field : List.of("bees", "energy", "revision", "capacity")) {
			var tag = original.copy(); tag.getList("ownership", 10).getCompound(0).getCompound("bees").remove(field);
			assertThrows(IllegalArgumentException.class, () -> CODEC.decode(tag));
		}
		var missing = original.copy(); missing.getList("ownership", 10).getCompound(0).put("bees", new CompoundTag());
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(missing));
		var duplicate = original.copy(); var list = duplicate.getList("ownership", 10).getCompound(0).getCompound("bees").getList("bees", 10); list.add(list.getFirst().copy());
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(duplicate));
		var unknown = original.copy(); unknown.getList("ownership", 10).getCompound(0).getCompound("bees").getList("bees", 10).getCompound(0).getCompound("plan").getCompound("output").putString("id", "missing:comb");
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(unknown));
		var future = original.copy(); future.getList("ownership", 10).getCompound(0).getCompound("bees").getList("bees", 10).getCompound(0).getCompound("plan").putLong("recipeRevision", 1);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(future));
		assertEquals(f.active(), CODEC.decode(original));
	}
	@Test void migrationCannotInventEnergyOrLoseOriginalBeeProgress() {
		var f = fixture(0, 0);
		var inflated = new BeeMemberState(f.bees.member(), 0, 1001, 2000, f.bees.bees());
		assertThrows(IllegalArgumentException.class, () -> f.original.withBees(inflated));
		var changed = f.bees.bee(2).work(1, 0, ProductAmount.ZERO);
		assertThrows(IllegalArgumentException.class, () -> f.original.withBees(new BeeMemberState(f.bees.member(), 0, 1000, 2000, List.of(changed))));
		var active = f.active();
		assertThrows(IllegalArgumentException.class, () -> record(active, f).withBees(f.bees));
	}
	@Test void publishedAuthorityRejectsAStaleLedgerSource() {
		var f = fixture(0, 0); var active = f.active();
		var paid = publishCandidate(active, f, BeeWorkExecutor.advance(f.bees, 2, 0, context(), 5, 1));
		var settled = paid.settleBee(f.bees.member(), 2, 1);
		var data = NetworkSavedData.create(settled, Runnable::run, (path, payload) -> { });
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(), List.of()));
		var ledger = new ProductLedger(policy, 4);
		var source = new NetworkCheckpointSource(settled.identity(), ledger, policy, new TransferStaging(ledger, 4, 64),
				new com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler(ledger,
				new com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleIndex(List.of(), List.of(), Map.of()),
				com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler.Mode.FAIR));
		assertThrows(IllegalArgumentException.class, () -> data.publish(source.capture(data, settled.revision() + 1)));
		assertSame(settled, data.checkpoint());
	}
	private NetworkCheckpoint roundTrip(NetworkCheckpoint expected, String name) throws Exception {
		assertEquals(expected, CODEC.decode(NetworkCheckpointCodec.encode(expected)));
		var file = folder.resolve(name + ".dat");
		CheckpointFiles.write(file, new CheckpointPayload.Network(expected, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			long end = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < end); decoder.step(1, 1_000_000); Thread.yield();
			}
			assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
			assertEquals(expected, decoder.checkpoint()); return decoder.checkpoint();
		}
	}
}
