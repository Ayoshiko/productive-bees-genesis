package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CentrifugeCheckpointTest {
	@TempDir Path folder;
	private static final ProductKey COMB = key(ProductKey.Kind.ITEM, "comb"), ITEM = key(ProductKey.Kind.ITEM, "result"), FLUID = key(ProductKey.Kind.FLUID, "fluid");
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> {
		if (!Set.of(COMB, ITEM, FLUID).contains(key)) throw new IllegalArgumentException("Missing product");
	});
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static ProductKey key(ProductKey.Kind kind, String name) { return new ProductKey(kind, ResourceLocation.parse("test:" + name), new CompoundTag()); }
	private static ProductAmount amount(long value) { return ProductAmount.of(value); }
	private static CentrifugeRecipePlan plan() {
		return new CentrifugeRecipePlan("test:comb", 0, 3, COMB, 10, 4, 2, 3, 0,
				List.of(new CentrifugeRecipePlan.Output(ITEM, 2, 2, 1), new CentrifugeRecipePlan.Output(FLUID, 25, 25, 1)));
	}
	private static ProductPolicyRegistry policy() {
		return new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(new AllowedProductDescriptor(ITEM, "test", "test:comb"),
				new AllowedProductDescriptor(FLUID, "test", "test:comb")), List.of()));
	}
	private record Fixture(UUID member, NetworkCheckpoint checkpoint, AssetImage original) {
		CentrifugeWorkState state(NetworkCheckpoint value) { return value.ownedMachines().get(member).centrifuge(); }
		NetworkCheckpoint assign() {
			return checkpoint.applyCentrifuge(member, CentrifugeWorkTransaction.assign(state(checkpoint), checkpoint.ledger(), policy(), 0, plan(), 4, 17));
		}
	}
	private static Fixture fixture() {
		var identity = CheckpointTestData.identity(); var member = UUID.randomUUID();
		var claim = new MemberClaim(identity.networkId(), member, UUID.randomUUID(), new Origin("minecraft:overworld", 2, 64, 1), "productivebeesgenesis:mek_centrifuge");
		var extra = new CompoundTag(); extra.putInt("nativeProgress", 0);
		extra.putIntArray("productivebeesgenesis_pb_progress", new int[]{0});
		extra.putLongArray("productivebeesgenesis_myriad_pending_fluid", new long[]{0});
		extra.put("productivebeesgenesis_pb_committed_pending", new ListTag());
		var tag = new CompoundTag(); tag.put("extra", extra); tag.putLong("energy", 1000); tag.putLong("energyCapacity", 2000);
		var items = new ListTag(); var retained = new CompoundTag(); retained.putString("test", "有限槽位与升级仍在原映像"); items.add(retained); tag.put("items", items);
		var assets = new AssetImage(tag); var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, assets, assets.fingerprint(), "");
		var checkpoint = new NetworkCheckpoint(identity, 0, 0, new LedgerCheckpoint(0, Map.of(COMB, amount(9)), List.of()),
				List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY).withOwnership(sealed).withOwnership(sealed.phase(OwnedMachineRecord.Phase.OWNED));
		var active = checkpoint.withOwnership(checkpoint.ownedMachines().get(member).withCentrifuge(new CentrifugeWorkState(member, 0, 1, 1000, 2000, Map.of())));
		return new Fixture(member, active, assets);
	}
	private static NetworkCheckpoint advance(Fixture f, NetworkCheckpoint current, int ticks) {
		return current.applyCentrifuge(f.member, CentrifugeWorkTransaction.advance(f.state(current), current.ledger(), 0, ticks, true, true));
	}
	private static NetworkCheckpoint freeze(Fixture f, NetworkCheckpoint current) {
		return current.applyCentrifuge(f.member, CentrifugeWorkTransaction.freeze(f.state(current), current.ledger(), 0));
	}

	@Test void restartAtEveryBoundaryKeepsInputsFeesAndResultsInOneAuthority() throws Exception {
		var f = fixture(); var current = roundTrip(f.checkpoint, "activated");
		assertEquals(f.original, current.ownedMachines().get(f.member).returnImage());
		current = roundTrip(f.assign(), "assigned");
		assertEquals(amount(5), current.ledger().balances().get(COMB));
		assertEquals(amount(4), f.state(current).jobs().get(0).heldInputs());
		current = roundTrip(advance(f, current, 3), "partial");
		assertEquals(976, f.state(current).energy());
		var partial = current;
		assertThrows(IllegalStateException.class, () -> partial.ownedMachines().get(f.member).returnImage());
		assertNull(CentrifugeWorkTransaction.cancel(f.state(current), current.ledger(), 0, 0));
		current = roundTrip(advance(f, current, 256), "paid");
		assertEquals(920, f.state(current).energy()); assertTrue(f.state(current).jobs().get(0).paid());
		assertFalse(f.state(current).jobs().get(0).sampled());
		current = roundTrip(freeze(f, current), "frozen");
		var frozen = f.state(current).jobs().get(0).frozen();
		assertEquals(Map.of(ITEM, amount(24), FLUID, amount(300)), frozen);
		var settlement = CentrifugeWorkTransaction.settle(f.state(current), current.ledger(), 0, 0);
		current = current.applyCentrifuge(f.member, settlement);
		assertSame(current, current.applyCentrifuge(f.member, settlement));
		current = roundTrip(current, "settled");
		assertTrue(f.state(current).drained()); assertEquals(amount(5), current.ledger().balances().get(COMB));
		assertEquals(amount(24), current.ledger().balances().get(ITEM)); assertEquals(amount(300), current.ledger().balances().get(FLUID));
		var returning = current.ownedMachines().get(f.member).phase(OwnedMachineRecord.Phase.RETURNING);
		assertNull(returning.centrifuge()); assertEquals(920, returning.assets().copy().getLong("energy"));
		assertEquals(f.original.copy().get("items"), returning.assets().copy().get("items"));
		current = roundTrip(current.withOwnership(returning), "returning");
		current = roundTrip(current.withOwnership(returning.phase(OwnedMachineRecord.Phase.RETURNED)), "returned");
		assertTrue(current.ownedMachines().get(f.member).assets().isEmpty());
	}

	@Test void checkpointRejectsBypassStaleRequestsAndQuarantinedWork() {
		var f = fixture(); var initial = f.checkpoint;
		var assignment = CentrifugeWorkTransaction.assign(f.state(initial), initial.ledger(), policy(), 0, plan(), 4, 17);
		assertThrows(IllegalArgumentException.class, () -> initial.withOwnership(initial.ownedMachines().get(f.member).withCentrifuge(assignment.state())));
		var assigned = initial.applyCentrifuge(f.member, assignment);
		assertSame(assigned, assigned.applyCentrifuge(f.member, assignment));
		var progress = CentrifugeWorkTransaction.advance(f.state(assigned), assigned.ledger(), 0, 3, true, true);
		var quarantined = assigned.withOwnership(assigned.ownedMachines().get(f.member).quarantine("offline recovery"));
		assertSame(quarantined, quarantined.applyCentrifuge(f.member, progress));
		assertSame(initial, initial.applyCentrifuge(UUID.randomUUID(), assignment));
		var higherPolicy = new NetworkCheckpoint(initial.identity(), initial.revision() + 1, 1, initial.ledger(), initial.transfers(),
				initial.discoveries(), initial.members(), initial.lanes(), initial.scheduler()).restoredOwnership(initial.ownedMachines());
		assertSame(higherPolicy, higherPolicy.applyCentrifuge(f.member, assignment));
	}

	@Test void unstartedWorkCanReturnInputsButNeverTwice() throws Exception {
		var f = fixture(); var assigned = roundTrip(f.assign(), "before-cancel");
		var cancel = CentrifugeWorkTransaction.cancel(f.state(assigned), assigned.ledger(), 0, 0);
		var restored = assigned.applyCentrifuge(f.member, cancel);
		assertSame(restored, restored.applyCentrifuge(f.member, cancel));
		assertEquals(amount(9), restored.ledger().balances().get(COMB));
		assertEquals(f.original, restored.ownedMachines().get(f.member).returnImage());
		assertEquals(restored, roundTrip(restored, "cancelled"));
	}

	@Test void missingOrCorruptWorkFailsBothDecodersWithoutPublishingPartialAssets() throws Exception {
		var f = fixture(); var frozen = freeze(f, advance(f, f.assign(), 10));
		List<Consumer<CompoundTag>> mutations = List.of(
				tag -> job(tag).putInt("progress", 11),
				tag -> job(tag).putInt("operations", 5),
				tag -> job(tag).putInt("progress", 9),
				tag -> job(tag).putBoolean("sampled", false),
				tag -> job(tag).put("frozen", new ListTag()),
				tag -> job(tag).getList("frozen", 10).getCompound(0).putLong("amount", 999),
				tag -> job(tag).getCompound("plan").putLong("recipeRevision", 1),
				tag -> job(tag).getCompound("plan").putDouble("stability", Double.NaN),
				tag -> work(tag).getList("jobs", 10).add(job(tag).copy()),
				tag -> work(tag).putLong("energy", 2001),
				tag -> work(tag).putUUID("member", UUID.randomUUID()),
				tag -> work(tag).putInt("unknown", 1),
				tag -> owned(tag).getCompound("assets").putLong("energy", 10),
				tag -> owned(tag).remove("centrifuge"),
				tag -> owned(tag).put("centrifuge", new CompoundTag()));
		for (int i = 0; i < mutations.size(); i++) {
			var tag = NetworkCheckpointCodec.encode(frozen); mutations.get(i).accept(tag);
			assertThrows(RuntimeException.class, () -> CODEC.decode(tag), "mutation " + i);
			var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); root.put("data", tag);
			var file = folder.resolve("bad-" + i + ".dat"); NbtIo.writeCompressed(root, file);
			try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
				drain(decoder); assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state(), "mutation " + i);
				assertThrows(IllegalStateException.class, decoder::checkpoint);
			}
		}
		assertEquals(920, f.state(frozen).energy()); assertEquals(amount(5), frozen.ledger().balances().get(COMB));
	}

	@Test void migrationRefusesUnresolvedPhysicalProgressAndAuthorityDuplication() {
		var f = fixture();
		for (String field : List.of("nativeProgress", "productivebeesgenesis_pb_progress", "productivebeesgenesis_myriad_pending_fluid", "productivebeesgenesis_pb_committed_pending")) {
			var tag = f.original.copy(); var extra = tag.getCompound("extra");
			switch (field) {
				case "nativeProgress" -> extra.putInt(field, 1);
				case "productivebeesgenesis_pb_progress" -> extra.putIntArray(field, new int[]{1});
				case "productivebeesgenesis_myriad_pending_fluid" -> extra.putLongArray(field, new long[]{1});
				default -> { var values = new ListTag(); values.add(new CompoundTag()); extra.put(field, values); }
			}
			assertThrows(IllegalArgumentException.class, () -> CentrifugeAssetProjection.validateMigration(new AssetImage(tag), f.state(f.checkpoint)));
		}
		var tag = f.original.copy(); tag.putLong("energy", 999);
		assertThrows(IllegalArgumentException.class, () -> CentrifugeAssetProjection.validateMigration(new AssetImage(tag), f.state(f.checkpoint)));
	}

	@Test void frozenProbabilityAndEmptyResultsSurviveCompressedRecovery() throws Exception {
		var f = fixture();
		for (int maximum : new int[]{0, 8}) {
			var plan = new CentrifugeRecipePlan("test:random", 0, 1, COMB, 1, 4, 2, 3, 0.1F,
					List.of(new CentrifugeRecipePlan.Output(ITEM, 0, maximum, 0.33F)));
			var current = f.checkpoint.applyCentrifuge(f.member, CentrifugeWorkTransaction.assign(f.state(f.checkpoint), f.checkpoint.ledger(), policy(), 0, plan, 4, 55));
			current = freeze(f, advance(f, current, 1));
			var expected = f.state(current).jobs().get(0).frozen();
			current = roundTrip(current, "random-" + maximum);
			assertEquals(expected, f.state(current).jobs().get(0).frozen());
			assertNull(CentrifugeWorkTransaction.freeze(f.state(current), current.ledger(), 0));
			var settled = current.applyCentrifuge(f.member, CentrifugeWorkTransaction.settle(f.state(current), current.ledger(), 0, 0));
			assertTrue(f.state(settled).drained());
			assertEquals(expected.getOrDefault(ITEM, ProductAmount.ZERO), settled.ledger().balances().getOrDefault(ITEM, ProductAmount.ZERO));
			assertEquals(amount(5), settled.ledger().balances().get(COMB));
		}
	}

	private static CompoundTag owned(CompoundTag tag) { return tag.getList("ownership", 10).getCompound(0); }
	private static CompoundTag work(CompoundTag tag) { return owned(tag).getCompound("centrifuge"); }
	private static CompoundTag job(CompoundTag tag) { return work(tag).getList("jobs", 10).getCompound(0); }
	private NetworkCheckpoint roundTrip(NetworkCheckpoint expected, String name) throws Exception {
		assertEquals(expected, CODEC.decode(NetworkCheckpointCodec.encode(expected)));
		var file = folder.resolve(name + ".dat");
		CheckpointFiles.write(file, new CheckpointPayload.Network(expected, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			drain(decoder); assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
			assertEquals(expected, decoder.checkpoint()); return decoder.checkpoint();
		}
	}
	private static void drain(CheckpointDecoder decoder) {
		long end = System.nanoTime() + 10_000_000_000L;
		while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
			assertTrue(System.nanoTime() < end); decoder.step(1, 1_000_000); Thread.yield();
		}
	}
}
