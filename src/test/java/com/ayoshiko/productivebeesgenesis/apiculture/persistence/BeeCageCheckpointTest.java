package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingAssetProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeAssetProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRosterChange;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor;
import com.ayoshiko.productivebeesgenesis.apiculture.production.StaticBeePlan;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class BeeCageCheckpointTest {
	@TempDir Path folder;
	private static final ProductKey COMB = new ProductKey(ProductKey.Kind.ITEM,
			ResourceLocation.parse("productivebees:configurable_honeycomb"), new CompoundTag());
	private static final StaticBeePlan PLAN = new StaticBeePlan("productivebees:iron", "test:iron", 0, 0,
			5, 10, 0, false, BeeWorkConditions.Traits.DEFAULT, COMB, 1);
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> { });
	private record Fixture(NetworkCheckpoint checkpoint, UUID member) { }
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }

	private static Fixture fixture() {
		var identity = CheckpointTestData.identity(); var member = UUID.randomUUID();
		var claim = new MemberClaim(identity.networkId(), member, UUID.randomUUID(),
				new Origin("minecraft:overworld", 1, 64, 2), "productivebeesgenesis:mek_apiary");
		var item = new CompoundTag(); item.putString("id", "minecraft:iron_block"); item.putInt("count", 1);
		var feeders = new ListTag();
		for (int i = 0; i < 9; i++) {
			var slot = new CompoundTag(); if (i == 0) slot.put("item", item.copy()); feeders.add(slot);
		}
		var extra = new CompoundTag(); extra.put(BeeAssetProjection.SLOTS, new ListTag());
		extra.put(FeedingAssetProjection.SLOTS, feeders);
		var raw = new CompoundTag(); raw.put("extra", extra); raw.putLong("energy", 1000); raw.putLong("energyCapacity", 2000);
		var image = new AssetImage(raw);
		var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
		var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED);
		var feeding = new FeedingSlotStore(0, 9, FeedingAssetProjection.fingerprint(image), List.of(
				new FeedingSlotStore.Slot(new FeedingItem(new AssetImage(item), 64), 1, false, 0),
				new FeedingSlotStore.Slot(null, 0, false, 0), new FeedingSlotStore.Slot(null, 0, false, 0)));
		var state = new BeeMemberState(member, 0, 1000, 2000, List.of(), feeding);
		var checkpoint = NetworkCheckpoint.empty(identity).withOwnership(sealed).withOwnership(owned)
				.withOwnership(owned.withBees(state)).configureEnergy(5000).migrateEnergy(member);
		return new Fixture(checkpoint, member);
	}
	private static BeeMemberState state(NetworkCheckpoint checkpoint, UUID member) {
		return checkpoint.ownedMachines().get(member).bees();
	}
	private static AssetImage slot(int index) {
		var data = new CompoundTag(); data.putString("entity", "productivebees:configurable_bee");
		data.putString("type", "productivebees:iron"); data.putString("custom", "完整蜜蜂数据");
		var slot = new CompoundTag(); slot.putInt("slot_index", index); slot.put("entity_data", data);
		slot.putInt("ticks_in_hive", 0); return new AssetImage(slot);
	}
	private static BeeWorkExecutor.Context context() {
		return new BeeWorkExecutor.Context(true, true, true, 0, 0,
				new BeeWorkConditions.Environment(false, false, false, false));
	}

	@Test void insertAndExtractKeepEveryOtherAuthorityAndFeedingGroup() {
		var f = fixture(); var before = f.checkpoint(); var source = state(before, f.member());
		var insert = BeeRosterChange.insert(source, 1, slot(1), PLAN);
		assertTrue(source.bees().isEmpty()); assertTrue(insert.matches(source));
		assertNotEquals(BeeRecord.identity(f.member(), 1), insert.bee().id());
		var added = before.exchangeBee(insert);
		assertSame(before.ledger(), added.ledger()); assertSame(before.energy(), added.energy());
		assertSame(before.scheduler(), added.scheduler()); assertSame(before.transfers(), added.transfers());
		assertSame(source.feeding(), state(added, f.member()).feeding());
		assertSame(before.ownedMachines().get(f.member()).assets(), added.ownedMachines().get(f.member()).assets());
		var removed = added.exchangeBee(BeeRosterChange.extract(state(added, f.member()), 1, insert.bee().id()));
		assertTrue(state(removed, f.member()).bees().isEmpty());
		assertSame(source.feeding(), state(removed, f.member()).feeding());
		assertTrue(removed.ownedMachines().follows(added.ownedMachines()));
		assertEquals(1, removed.ownedMachines().activeCount());
	}

	@Test void aRepeatedCandidateOrAnotherRootCannotReplayTheTransfer() throws Exception {
		var f = fixture(); var before = f.checkpoint();
		var insert = BeeRosterChange.insert(state(before, f.member()), 0, slot(0), PLAN);
		var after = before.exchangeBee(insert);
		assertThrows(IllegalArgumentException.class, () -> after.exchangeBee(insert));
		var restored = roundTrip(before, "empty");
		assertThrows(IllegalArgumentException.class, () -> restored.exchangeBee(insert));
		var recompiled = BeeRosterChange.insert(state(restored, f.member()), 0, slot(0), PLAN);
		assertEquals(insert.bee().id(), recompiled.bee().id());
	}

	@Test void genericOwnershipUpdatesCannotBypassRosterProofs() {
		var f = fixture(); var before = f.checkpoint(); var old = before.ownedMachines().get(f.member());
		var change = BeeRosterChange.insert(old.bees(), 0, slot(0), PLAN);
		assertThrows(IllegalArgumentException.class, () -> old.withBees(change.candidate()));
		var forged = new OwnedMachineRecord(old.claim(), old.phase(), old.assets(), old.fingerprint(), "", change.candidate());
		assertThrows(IllegalArgumentException.class, () -> before.withOwnership(forged));
		assertThrows(IllegalArgumentException.class, () -> before.ownedMachines().put(forged));
	}

	@Test void paidResultsMustSettleAndOldWorkCannotApplyToReplacementBee() {
		var f = fixture(); var start = f.checkpoint();
		var inserted = start.exchangeBee(BeeRosterChange.insert(state(start, f.member()), 0, slot(0), PLAN));
		var source = state(inserted, f.member()); var bee = source.bee(0);
		var oldWork = BeeWorkExecutor.advance(source, 0, bee.revision(), context(), 5, 0, inserted.energy().stored());
		var paid = inserted.applyBeeWork(f.member(), oldWork); var paidState = state(paid, f.member());
		assertThrows(IllegalStateException.class, () -> BeeRosterChange.extract(paidState, 0, bee.id()));
		var sample = BeeWorkExecutor.advance(paidState, 0, paidState.bee(0).revision(), context(), 0, 1, paid.energy().stored());
		var frozen = paid.applyBeeWork(f.member(), sample); var frozenState = state(frozen, f.member());
		assertThrows(IllegalStateException.class, () -> BeeRosterChange.extract(frozenState, 0, bee.id()));
		var settled = frozen.settleBee(f.member(), 0, frozenState.bee(0).revision());
		assertEquals(ProductAmount.of(1), settled.ledger().balances().get(COMB));
		assertEquals(950, settled.energy().stored());
		var empty = settled.exchangeBee(BeeRosterChange.extract(state(settled, f.member()), 0, bee.id()));
		var replacement = BeeRosterChange.insert(state(empty, f.member()), 0, slot(0), PLAN);
		var current = empty.exchangeBee(replacement);
		assertNotEquals(bee.id(), replacement.bee().id());
		assertSame(current, current.applyBeeWork(f.member(), oldWork));
		assertThrows(IllegalArgumentException.class, () -> BeeRosterChange.extract(state(current, f.member()), 0, bee.id()));
	}

	@Test void partialCycleCancellationDoesNotRefundEnergyOrGenerateProducts() {
		var f = fixture(); var start = f.checkpoint();
		var inserted = start.exchangeBee(BeeRosterChange.insert(state(start, f.member()), 0, slot(0), PLAN));
		var source = state(inserted, f.member());
		var advanced = inserted.applyBeeWork(f.member(), BeeWorkExecutor.advance(source, 0, 0, context(), 3, 1, 1000));
		var bee = state(advanced, f.member()).bee(0);
		var extract = BeeRosterChange.extract(state(advanced, f.member()), 0, bee.id());
		assertEquals(3, extract.bee().progress());
		var after = advanced.exchangeBee(extract);
		assertEquals(970, after.energy().stored()); assertTrue(after.ledger().balances().isEmpty());
	}

	@Test void normalReturnAndBothDecodersUseOnlyCurrentRoster() throws Exception {
		var f = fixture(); var before = f.checkpoint();
		var first = BeeRosterChange.insert(state(before, f.member()), 0, slot(0), PLAN);
		var added = roundTrip(before.exchangeBee(first), "inserted");
		var second = BeeRosterChange.insert(state(added, f.member()), 2, slot(2), PLAN);
		var both = added.exchangeBee(second);
		var remaining = roundTrip(both.exchangeBee(BeeRosterChange.extract(state(both, f.member()), 0, first.bee().id())), "extracted");
		var image = remaining.ownedMachines().get(f.member()).returnImage().copy().getCompound("extra");
		assertEquals(1, image.getList(BeeAssetProjection.SLOTS, 10).size());
		assertEquals(2, image.getList(BeeAssetProjection.SLOTS, 10).getCompound(0).getInt("slot_index"));
		assertEquals("完整蜜蜂数据", image.getList(BeeAssetProjection.SLOTS, 10).getCompound(0).getCompound("entity_data").getString("custom"));
		assertEquals(1, image.getList(FeedingAssetProjection.SLOTS, 10).getCompound(0).getCompound("item").getInt("count"));
	}

	@Test void repeatedReentryNeverReusesBeeIdentityOrAccumulatesRemovedRecords() {
		var f = fixture(); var current = f.checkpoint(); var identities = ConcurrentHashMap.<UUID>newKeySet();
		for (int i = 0; i < 1000; i++) {
			var insert = BeeRosterChange.insert(state(current, f.member()), i % 3, slot(i % 3), PLAN);
			assertTrue(identities.add(insert.bee().id())); current = current.exchangeBee(insert);
			current = current.exchangeBee(BeeRosterChange.extract(state(current, f.member()), i % 3, insert.bee().id()));
			assertTrue(state(current, f.member()).bees().isEmpty()); assertEquals(1, current.ownedMachines().size());
		}
		assertEquals(1000, current.energy().stored()); assertTrue(current.ledger().balances().isEmpty());
	}

	@Test void wrongSlotOccupiedSlotAndRevisionOverflowCannotChangeSource() {
		var f = fixture(); var source = state(f.checkpoint(), f.member());
		assertThrows(IllegalArgumentException.class, () -> BeeRosterChange.insert(source, 3, slot(3), PLAN));
		assertThrows(IllegalArgumentException.class, () -> BeeRosterChange.insert(source, 0, slot(1), PLAN));
		var added = BeeRosterChange.insert(source, 0, slot(0), PLAN).candidate();
		assertThrows(IllegalArgumentException.class, () -> BeeRosterChange.insert(added, 0, slot(0), PLAN));
		var last = new BeeMemberState(source.member(), Long.MAX_VALUE, 0, source.energyCapacity(),
				List.of(), source.feeding(), true);
		assertThrows(ArithmeticException.class, () -> BeeRosterChange.insert(last, 0, slot(0), PLAN));
		assertTrue(source.bees().isEmpty());
	}

	private NetworkCheckpoint roundTrip(NetworkCheckpoint expected, String name) throws Exception {
		assertEquals(expected, CODEC.decode(NetworkCheckpointCodec.encode(expected)));
		var file = folder.resolve(name + ".dat");
		CheckpointFiles.write(file, new CheckpointPayload.Network(expected, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING
					|| decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < deadline); decoder.step(1, 1_000_000); Thread.yield();
			}
			assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
			assertEquals(expected, decoder.checkpoint()); return decoder.checkpoint();
		}
	}
}
