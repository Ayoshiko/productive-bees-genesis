package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CombinedCheckpointTest {
	@TempDir Path folder;
	static final ProductKey INPUT = key("comb"), OUTPUT = key("result");
	static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> {});
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	static ProductKey key(String id) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:" + id), new CompoundTag()); }
	static CentrifugeRecipePlan plan() {
		return new CentrifugeRecipePlan("test:comb", 0, 0, INPUT, 4, 1, 2, 1, 0,
				List.of(new CentrifugeRecipePlan.Output(OUTPUT, 10, 10, 1)));
	}
	static CombinedMachineWork source() {
		var empty = CombinedMachineCapacity.empty(UUID.randomUUID(), 1);
		var bees = new ArrayList<BeeRecord>();
		for (int slot = 0; slot < 6; slot++) {
			var original = new CompoundTag(); original.putInt("slot_index", slot); original.putInt("ticks_in_hive", 0);
			var entity = new CompoundTag(); entity.putString("type", "productivebees:iron"); original.put("entity_data", entity);
			var plan = new StaticBeePlan("productivebees:iron", "test:bee", 0, 0, 4, 3, 0, false, BeeWorkConditions.Traits.DEFAULT, INPUT, 1, 1);
			bees.add(new BeeRecord(UUID.randomUUID(), empty.machine(), slot, new AssetImage(original), plan, 0, 1, 0, ProductAmount.ZERO));
		}
		var jobs = new HashMap<Integer, CentrifugeDelivery>();
		var buffer = empty.buffer();
		for (int lane = 0; lane < 3; lane++) {
			var job = new CentrifugeJob(UUID.randomUUID(), plan(), 1, 4, 37 + lane, null).freeze();
			jobs.put(lane, new CentrifugeDelivery(job, Map.of(OUTPUT, ProductAmount.of(3))));
			buffer = buffer.insert(OUTPUT, 3, 64).buffer();
		}
		return new CombinedMachineWork(empty.machine(), 1, 0, 6, 3, 1000, empty.energyCapacity(), bees, jobs, buffer);
	}
	static NetworkCheckpoint owned(CombinedMachineWork source, long capacity) {
		var initial = new NetworkCheckpoint(CheckpointTestData.identity(), 0, 0, new LedgerCheckpoint(0, Map.of(), List.of()), List.of(), Set.of(), List.of(), List.of(), com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint.EMPTY);
		initial = initial.configureEnergy(capacity);
		var claim = new MemberClaim(initial.identity().networkId(), source.machine(), UUID.randomUUID(), new Origin("minecraft:overworld", 5, 64, 0), CombinedAssetProjection.MACHINE);
		var image = CombinedAssetProjection.capture(source);
		var record = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
		return initial.withOwnership(record).withOwnership(record.phase(OwnedMachineRecord.Phase.OWNED));
	}
	@Test void activationCapacityRejectionAndReplayKeepOneEnergyOwner() throws Exception {
		var source = source(); var full = owned(source, 100).receiveEnergy(100);
		assertSame(full, full.activateCombined(source.machine(), source));
		var before = roundTrip(owned(source, 2000), "sealed");
		var current = roundTrip(before.activateCombined(source.machine(), source), "active");
		assertEquals(1000, current.energy().stored());
		assertEquals(0, current.ownedMachines().get(source.machine()).combined().energy());
		assertSame(current, current.activateCombined(source.machine(), source));
		assertEquals(9, current.ownedMachines().get(source.machine()).combined().buffer().count(OUTPUT));
	}
	@Test void threePartiallyDeliveredLanesSettleOnlyRemaindersAndPreserveReturnAssets() throws Exception {
		var source = source(); var member = source.machine();
		var current = owned(source, 2000).activateCombined(member, source);
		for (int lane = 0; lane < 3; lane++) {
			current = roundTrip(current, "lane-" + lane);
			var transaction = CentrifugeWorkTransaction.settle(current.ownedMachines().get(member).centrifugeState(), current.ledger(), lane, 0);
			current = current.applyCentrifuge(member, transaction);
			assertSame(current, current.applyCentrifuge(member, transaction));
		}
		assertEquals(ProductAmount.of(21), current.ledger().balances().get(OUTPUT));
		assertEquals(9, current.ownedMachines().get(member).combined().buffer().count(OUTPUT));
		var returning = current.ownedMachines().get(member).phase(OwnedMachineRecord.Phase.RETURNING);
		current = roundTrip(current.withOwnership(returning), "returning");
		var returned = CombinedWorkCodec.decode(returning.assets().copy().getCompound("combinedWork"), key -> {}, key -> 64, item -> {});
		assertEquals(6, returned.bees().size()); assertEquals(1, returned.bee(5).progress()); assertEquals(0, returned.energy());
		assertEquals(9, returned.buffer().count(OUTPUT));
		current = roundTrip(current.withOwnership(returning.phase(OwnedMachineRecord.Phase.RETURNED)), "returned");
		assertTrue(current.ownedMachines().get(member).assets().isEmpty()); assertEquals(1000, current.energy().stored());
	}
	@Test void beeAndCentrifugeUseTheSameFeAndStaleCandidatesFail() throws Exception {
		var source = source(); var member = source.machine(); var current = owned(source, 2000).activateCombined(member, source);
		var work = current.ownedMachines().get(member).combined(); var bee = work.bee(5);
		var context = new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false));
		var result = BeeWorkExecutor.advanceBee(bee, bee.revision(), context, 3, 1, 1000, null);
		current = current.applyCombinedBee(member, result);
		assertEquals(991, current.energy().stored());
		var frozen = current; assertThrows(IllegalArgumentException.class, () -> frozen.applyCombinedBee(member, result));
		current = roundTrip(current.settleCombinedBee(member, 5, current.ownedMachines().get(member).combined().bee(5).revision()), "bee");
		assertEquals(ProductAmount.of(1), current.ledger().balances().get(INPUT));
		assertEquals(1, current.ownedMachines().get(member).combined().bee(0).progress());
	}
	@Test void missingOrDisguisedCombinedAuthorityFailsBothReaders() throws Exception {
		var source = source(); var active = owned(source, 2000).activateCombined(source.machine(), source);
		for (int variation = 0; variation < 3; variation++) {
			var tag = NetworkCheckpointCodec.encode(active); var record = tag.getList("ownership", 10).getCompound(0);
			if (variation == 0) record.remove("combined");
			if (variation == 1) tag.putInt("schema", 12);
			if (variation == 2) record.getCompound("combined").putLong("energy", 1);
			assertThrows(IllegalArgumentException.class, () -> CODEC.decode(tag));
			read(tag, "invalid-" + variation, false);
		}
		var legacy = NetworkCheckpointCodec.encode(owned(source, 2000)); legacy.putInt("schema", 12);
		legacy.getList("ownership", 10).getCompound(0).remove("combined");
		read(legacy, "legacy-twelve", true);
	}
	@Test void decreasedComponentStackLimitRejectsBothReadersWithoutTruncation() throws Exception {
		var source = source(); var active = owned(source, 2000).activateCombined(source.machine(), source);
		var strict = new NetworkCheckpointCodec(key -> {}, item -> {}, key -> 16);
		var tag = NetworkCheckpointCodec.encode(active);
		assertThrows(IllegalArgumentException.class, () -> strict.decode(tag));
		var path = folder.resolve("lowered-limit.dat");
		CheckpointFiles.write(path, new CheckpointPayload.Network(active, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = strict.decoder(reader.tryOpen(path).orElseThrow())) {
			long end = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < end); decoder.step(1, 1_000_000); Thread.yield();
			}
			assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state());
		}
		assertEquals(9, active.ownedMachines().get(source.machine()).combined().buffer().count(OUTPUT));
	}
	@Test void terminalSelectionSurvivesProgressButRejectsRosterAndUpgradeAbaAndRestore() throws Exception {
		var source = source(); var id = source.machine();
		var root = owned(source, 2000).activateCombined(id, source);
		var session = new com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession();
		var kind = com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Kind.MEMBERS;
		for (var scope : com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.values()) {
			var page = session.begin(this, root, kind, 0, scope);
			assertEquals(1, page.rows().size());
			assertEquals(6, ((com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.MemberRow) page.rows().getFirst()).bees().size());
		}
		var selected = (com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.MemberRow) session.page().rows().getFirst();
		var work = root.ownedMachines().get(id).combined();
		var queryToken = root.ownedMachines().queryToken(); var capability = root.ownedMachines().capabilityToken();
		var bee = work.bee(5);
		var context = new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false));
		root = root.applyCombinedBee(id, BeeWorkExecutor.advanceBee(bee, bee.revision(), context, 1, 1, 1000, null));
		assertSame(queryToken, root.ownedMachines().queryToken()); assertSame(capability, root.ownedMachines().capabilityToken());
		assertTrue(com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.sameRoster(selected, root.ownedMachines().get(id)));
		work = root.ownedMachines().get(id).combined();
		root = root.exchangeCombined(id, work.beeEnabled(5, bee.id(), false));
		work = root.ownedMachines().get(id).combined(); root = root.exchangeCombined(id, work.beeEnabled(5, bee.id(), true));
		assertFalse(com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.sameRoster(selected, root.ownedMachines().get(id)));
		var upgraded = (com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.MemberRow) session.begin(this, root,
				com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Kind.UPGRADES, 1).rows().getFirst();
		work = root.ownedMachines().get(id).combined(); root = root.exchangeCombined(id, work.exchangeUpgrade(2, 1));
		work = root.ownedMachines().get(id).combined(); root = root.exchangeCombined(id, work.exchangeUpgrade(2, -1));
		assertFalse(com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.sameUpgrades(upgraded, root.ownedMachines().get(id)));
		assertNotSame(capability, root.ownedMachines().capabilityToken());
		var beforeRestore = root.ownedMachines().get(id).rosterVersion();
		root = roundTrip(root, "terminal-control");
		assertNotSame(beforeRestore, root.ownedMachines().get(id).rosterVersion()); assertTrue(root.ownedMachines().get(id).combined().bee(5).enabled());
	}
	NetworkCheckpoint roundTrip(NetworkCheckpoint expected, String name) throws Exception {
		assertEquals(NetworkCheckpointCodec.encode(expected), NetworkCheckpointCodec.encode(CODEC.decode(NetworkCheckpointCodec.encode(expected))));
		var path = folder.resolve(name + ".dat");
		CheckpointFiles.write(path, new CheckpointPayload.Network(expected, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		var result = decode(path, true);
		assertEquals(NetworkCheckpointCodec.encode(expected), NetworkCheckpointCodec.encode(result)); return result;
	}
	void read(CompoundTag data, String name, boolean valid) throws Exception {
		var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); root.put("data", data);
		var path = folder.resolve(name + ".dat"); NbtIo.writeCompressed(root, path); decode(path, valid);
	}
	NetworkCheckpoint decode(Path path, boolean valid) throws Exception {
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(path).orElseThrow())) {
			long end = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < end); decoder.step(1, 1_000_000); Thread.yield();
			}
			assertEquals(valid ? CheckpointDecoder.State.COMPLETE : CheckpointDecoder.State.FAILED, decoder.progress().state(), decoder.progress().failure());
			return valid ? decoder.checkpoint() : null;
		}
	}
}
