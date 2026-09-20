package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyAccount;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NetworkEnergyCheckpointTest {
	private static final UUID BEE = new UUID(0, 1), CENTRIFUGE = new UUID(0, 2);
	private static final ProductKey COMB = CheckpointTestData.key("comb"), RESULT = CheckpointTestData.key("result");
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> { });
	@TempDir Path folder;
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static ProductPolicyRegistry policy() { return new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(
			new AllowedProductDescriptor(COMB, "test", "comb"), new AllowedProductDescriptor(RESULT, "test", "comb")), List.of())); }
	private static BeeWorkExecutor.Context context() { return new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false)); }
	private static CentrifugeRecipePlan plan() { return new CentrifugeRecipePlan("test:comb", 0, 0, COMB, 3, 2, 20, 1, 0, List.of(new CentrifugeRecipePlan.Output(RESULT, 3, 3, 1))); }
	private static NetworkCheckpoint fixture() {
		var identity = CheckpointTestData.identity();
		var current = new NetworkCheckpoint(identity, 0, 0, new LedgerCheckpoint(0, Map.of(COMB, ProductAmount.of(10)), List.of()), List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		for (var member : List.of(BEE, CENTRIFUGE)) {
			boolean bee = member.equals(BEE); long energy = bee ? 40 : 60;
			var claim = new MemberClaim(identity.networkId(), member, UUID.randomUUID(), new Origin("minecraft:overworld", bee ? 2 : 3, 64, 1),
					"productivebeesgenesis:" + (bee ? "mek_apiary" : "mek_centrifuge"));
			var extra = new CompoundTag(); var slots = new ListTag(); var slot = new CompoundTag();
			if (bee) {
				var entity = new CompoundTag(); entity.putString("type", "productivebees:iron");
				slot.putInt("slot_index", 0); slot.putInt("ticks_in_hive", 0); slot.put("entity_data", entity); slots.add(slot);
				extra.put(BeeAssetProjection.SLOTS, slots);
				var feeders = new ListTag(); for (int i = 0; i < 9; i++) feeders.add(new CompoundTag());
				extra.put(com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingAssetProjection.SLOTS, feeders);
			}
			var tag = new CompoundTag(); tag.putLong("energy", energy); tag.putLong("energyCapacity", 1000); tag.put("extra", extra);
			var image = new AssetImage(tag); var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
			current = current.withOwnership(sealed); var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED); current = current.withOwnership(owned);
			if (bee) {
				var plan = new StaticBeePlan("productivebees:iron", "test:iron", 0, 0, 5, 10, 0, false, BeeWorkConditions.Traits.DEFAULT, COMB, 1);
				var record = new BeeRecord(BeeRecord.identity(member, 0), member, 0, new AssetImage(slot), plan, 0, 0, 0, ProductAmount.ZERO);
				current = current.withOwnership(owned.withBees(new BeeMemberState(member, 0, energy, 1000, List.of(record))));
			} else current = current.withOwnership(owned.withCentrifuge(new CentrifugeWorkState(member, 0, 1, energy, 1000, Map.of())));
		}
		return current;
	}
	private static CentrifugeWorkState centrifuge(NetworkCheckpoint checkpoint) { return checkpoint.ownedMachines().get(CENTRIFUGE).centrifuge(); }
	private static BeeMemberState bees(NetworkCheckpoint checkpoint) { return checkpoint.ownedMachines().get(BEE).bees(); }
	private static NetworkCheckpoint shared() { return fixture().configureEnergy(200).migrateEnergy(BEE).migrateEnergy(CENTRIFUGE); }
	private static NetworkCheckpoint assign(NetworkCheckpoint checkpoint) {
		return checkpoint.applyCentrifuge(CENTRIFUGE, CentrifugeWorkTransaction.assign(centrifuge(checkpoint), checkpoint.ledger(), policy(), 0, plan(), 2, 1,
				centrifuge(checkpoint).networkPowered() ? checkpoint.energy().stored() : centrifuge(checkpoint).energy()));
	}
	@Test void migrationIsAtomicCapacityBoundAndExactlyOnce() {
		var original = fixture().configureEnergy(50); assertSame(original, original.migrateEnergy(CENTRIFUGE));
		assertEquals(60, centrifuge(original).energy());
		var first = original.migrateEnergy(BEE); assertEquals(40, first.energy().stored()); assertEquals(0, bees(first).energy());
		assertSame(first, first.migrateEnergy(BEE)); assertSame(first, first.migrateEnergy(CENTRIFUGE));
		var both = first.configureEnergy(200).migrateEnergy(CENTRIFUGE);
		assertEquals(100, both.energy().stored()); assertEquals(0, centrifuge(both).energy());
		assertEquals(0, both.ownedMachines().get(BEE).returnImage().copy().getLong("energy"));
		assertEquals(0, both.ownedMachines().get(CENTRIFUGE).returnImage().copy().getLong("energy"));
	}
	@Test void beeAndCentrifugeSpendOneAccountAndResumeTheSamePaidCycle() {
		var current = assign(shared()); var state = bees(current);
		var work = BeeWorkExecutor.advance(state, 0, 0, context(), 5, 1, current.energy().stored());
		current = current.applyBeeWork(BEE, work); assertSame(current, current.applyBeeWork(BEE, work));
		assertEquals(50, current.energy().stored());
		current = current.settleBee(BEE, 0, bees(current).bee(0).revision());
		var step = CentrifugeWorkTransaction.advance(centrifuge(current), current.ledger(), 0, 99, true, true, current.energy().stored());
		current = current.applyCentrifuge(CENTRIFUGE, step); assertEquals(10, current.energy().stored());
		assertEquals(1, centrifuge(current).jobs().get(0).progress());
		assertNull(CentrifugeWorkTransaction.advance(centrifuge(current), current.ledger(), 0, 99, true, true, current.energy().stored()));
		var pinned = centrifuge(current).jobs().get(0); current = current.receiveEnergy(70);
		assertEquals(pinned, centrifuge(current).jobs().get(0));
		current = current.applyCentrifuge(CENTRIFUGE, CentrifugeWorkTransaction.advance(centrifuge(current), current.ledger(), 0, 99, true, true, current.energy().stored()));
		current = current.applyCentrifuge(CENTRIFUGE, CentrifugeWorkTransaction.freeze(centrifuge(current), current.ledger(), 0));
		current = current.applyCentrifuge(CENTRIFUGE, CentrifugeWorkTransaction.settle(centrifuge(current), current.ledger(), 0, 0));
		assertEquals(0, current.energy().stored()); assertEquals(ProductAmount.of(6), current.ledger().balances().get(RESULT));
		assertEquals(ProductAmount.of(9), current.ledger().balances().get(COMB));
	}
	@Test void midCycleMigrationPreservesExistingProgressAndOnlyMovesRemainingEnergy() {
		var current = assign(fixture());
		current = current.applyCentrifuge(CENTRIFUGE, CentrifugeWorkTransaction.advance(centrifuge(current), current.ledger(), 0, 1, true, true));
		var job = centrifuge(current).jobs().get(0);
		current = current.configureEnergy(100).migrateEnergy(CENTRIFUGE);
		assertEquals(20, current.energy().stored()); assertEquals(job, centrifuge(current).jobs().get(0));
		assertSame(current, current.migrateEnergy(CENTRIFUGE));
	}
	@Test void ordinaryOwnershipUpdatesCannotBypassSharedPaymentOrMigration() {
		var original = fixture().configureEnergy(200); var owned = original.ownedMachines().get(BEE);
		assertThrows(IllegalArgumentException.class, () -> original.withOwnership(owned.withBees(owned.bees().transferEnergy())));
		var current = shared(); var state = bees(current);
		var work = BeeWorkExecutor.advance(state, 0, 0, context(), 5, 1, current.energy().stored());
		assertThrows(IllegalArgumentException.class, () -> current.withOwnership(current.ownedMachines().get(BEE).withBees(work.candidate())));
		var expensive = BeeWorkExecutor.advance(state, 0, 0, context(), 15, 1, 1000);
		assertSame(current, current.applyBeeWork(BEE, expensive));
		var moved = current.withOwnership(current.ownedMachines().get(BEE).withBees(state.moveBee(0, 1, false)));
		assertEquals(current.energy(), moved.energy()); assertTrue(bees(moved).networkPowered());
	}
	@Test void sharedEnergyAndPaidProgressSurviveBothDecoders() throws Exception {
		var current = assign(shared());
		current = current.applyCentrifuge(CENTRIFUGE, CentrifugeWorkTransaction.advance(centrifuge(current), current.ledger(), 0, 1, true, true, current.energy().stored()));
		assertEquals(current, CODEC.decode(NetworkCheckpointCodec.encode(current)));
		var file = folder.resolve("shared.dat"); CheckpointFiles.write(file, new CheckpointPayload.Network(current, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < deadline); decoder.step(5, 1_000_000); Thread.yield();
			}
			assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure()); assertEquals(current, decoder.checkpoint());
		}
	}
	@Test void malformedEnergyAndDuplicateMemberBalancesAreRejected() throws Exception {
		var source = NetworkCheckpointCodec.encode(shared());
		List<Consumer<CompoundTag>> damage = List.of(tag -> tag.remove("energy"), tag -> tag.getCompound("energy").putLong("stored", -1),
				tag -> tag.getCompound("energy").putLong("capacity", 1), tag -> tag.getCompound("energy").putString("stored", "100"),
				tag -> { tag.getCompound("energy").putLong("stored", 0); tag.getCompound("energy").putLong("capacity", 0); },
				tag -> tag.getList("ownership", 10).getCompound(0).getCompound("bees").putLong("energy", 1),
				tag -> tag.getList("ownership", 10).getCompound(0).getCompound("bees").putByte("networkPowered", (byte) 2));
		for (int i = 0; i < damage.size(); i++) {
			var bad = source.copy(); damage.get(i).accept(bad); assertThrows(IllegalArgumentException.class, () -> CODEC.decode(bad));
			var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); root.put("data", bad);
			var file = folder.resolve("bad-" + i + ".dat"); NbtIo.writeCompressed(root, file);
			try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
				long deadline = System.nanoTime() + 5_000_000_000L;
				while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
					assertTrue(System.nanoTime() < deadline); decoder.step(5, 1_000_000); Thread.yield();
				}
				assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state(), "mutation " + i);
				assertThrows(IllegalStateException.class, decoder::checkpoint);
			}
		}
	}
	@Test void capturingOtherDomainsPreservesCurrentSharedBalanceAndOwnership() {
		var current = shared(); var registry = policy(); var ledger = ProductLedger.restore(registry, 4, current.ledger());
		var source = new NetworkCheckpointSource(current.identity(), ledger, registry, new TransferStaging(ledger, 4, 64),
				new com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler(ledger,
						new com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleIndex(List.of(), List.of(), Map.of()),
						com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler.Mode.FAIR));
		var authority = NetworkSavedData.create(current, Runnable::run, (path, payload) -> { });
		authority.publish(current.receiveEnergy(17));
		var captured = source.capture(authority, authority.checkpoint().revision() + 1);
		assertEquals(117, captured.energy().stored()); assertSame(current.ownedMachines(), captured.ownedMachines());
		authority.publish(captured); assertEquals(captured, CODEC.decode(NetworkCheckpointCodec.encode(captured)));
	}
	@Test void longBoundaryAndCapacityReductionNeverOverflowOrDestroyEnergy() {
		var current = fixture().configureEnergy(Long.MAX_VALUE).receiveEnergy(Long.MAX_VALUE - 2);
		assertEquals(2, current.energy().accept(Long.MAX_VALUE)); assertEquals(0, current.energy().accept(-1));
		current = current.receiveEnergy(Long.MAX_VALUE); assertEquals(Long.MAX_VALUE, current.energy().stored());
		assertSame(current, current.receiveEnergy(1)); assertSame(current, current.configureEnergy(1));
		assertThrows(IllegalArgumentException.class, () -> new NetworkEnergyAccount(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> new NetworkEnergyAccount(1, 0));
	}
	@Test void overflowingBeePriceCannotBePaidAtTheSaturatedLongValue() {
		var original = bees(fixture()).bee(0);
		var plan = new StaticBeePlan("productivebees:iron", "test:iron", 0, 0, 5, Long.MAX_VALUE / 2 + 1,
				0, false, BeeWorkConditions.Traits.DEFAULT, COMB, 1);
		var bee = new BeeRecord(original.id(), BEE, 0, original.originalSlot(), plan, 0, 0, 0, ProductAmount.ZERO);
		var state = new BeeMemberState(BEE, 0, 0, 0, List.of(bee), null, true);
		var rejected = BeeWorkExecutor.advance(state, 0, 0, context(), 2, 1, Long.MAX_VALUE);
		assertEquals(BeeWorkExecutor.Status.ENERGY, rejected.status());
		assertSame(state, rejected.candidate()); assertEquals(0, rejected.energyUsed());
		var funded = BeeWorkExecutor.advance(state, 0, 0, context(), 1, 1, Long.MAX_VALUE);
		assertEquals(BeeWorkExecutor.Status.READY, funded.status());
		assertEquals(plan.energyPerTick(), funded.energyUsed()); assertEquals(1, funded.candidate().bee(0).progress());
	}
}
