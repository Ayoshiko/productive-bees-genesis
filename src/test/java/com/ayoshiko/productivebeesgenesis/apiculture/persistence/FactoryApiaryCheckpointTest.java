package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FactoryApiaryCheckpointTest {
	private static final List<String> TYPES = List.of("basic", "advanced", "elite", "ultimate");
	private static final UUID MEMBER = new UUID(17, 3);
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> {});
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static NetworkCheckpoint fixture(String tier) {
		var base = BeeRandomCheckpointTest.fixture(2.5f, true, null);
		var record = base.ownedMachines().get(MEMBER); var old = record.bees().bee(0);
		String machine = "productivebeesgenesis:" + tier + "_mek_apiary_factory";
		var layout = ApiaryWorkLayout.forMachine(machine);
		var image = record.returnImage().copy(); image.putString("machine", machine); image.put("upgrades", new CompoundTag());
		var slot = old.originalSlot().copy(); slot.putInt("slot_index", layout.bees() - 1);
		var list = new ListTag(); list.add(slot); var extra = image.getCompound("extra"); extra.put(BeeAssetProjection.SLOTS, list);
		var rawFood = new ListTag(); for (int i = 0; i < layout.feeders(); i++) rawFood.add(new CompoundTag());
		extra.put(FeedingAssetProjection.SLOTS, rawFood); var assets = new AssetImage(image);
		var food = new ArrayList<FeedingSlotStore.Slot>();
		for (int i = 0; i < layout.bees(); i++) food.add(new FeedingSlotStore.Slot(null, 0, false, i));
		var feeding = new FeedingSlotStore(0, layout.feeders(), FeedingAssetProjection.fingerprint(assets), food);
		var bee = new BeeRecord(old.id(), MEMBER, layout.bees() - 1, new AssetImage(slot), old.plan(), 0, 0, 0, ProductAmount.ZERO);
		var state = new BeeMemberState(MEMBER, 0, 10000, 10000, List.of(bee), feeding);
		var claim = new MemberClaim(base.identity().networkId(), MEMBER, record.claim().transfer(), record.claim().origin(), machine);
		var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, assets, assets.fingerprint(), "");
		var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED);
		return NetworkCheckpoint.empty(base.identity()).withOwnership(sealed).withOwnership(owned).withOwnership(owned.withBees(state));
	}
	@Test void allFourTiersKeepLastSlotPaidOutputsUpgradeAndReturnLayout() {
		for (var tier : TYPES) {
			var root = fixture(tier); var record = root.ownedMachines().get(MEMBER); var state = record.bees(); int slot = state.slotCount() - 1;
			assertEquals(record.workLayout().beeSlots(), state.slotCount());
			var result = BeeWorkExecutor.advance(state, slot, 0, new BeeWorkExecutor.Context(true, true, true, 0, 0,
					new BeeWorkConditions.Environment(false, false, false, false)), 10, 1);
			assertEquals(BeeWorkExecutor.Status.READY, result.status()); root = root.applyBeeWork(MEMBER, result);
			root = CODEC.decode(NetworkCheckpointCodec.encode(root)); record = root.ownedMachines().get(MEMBER);
			var paid = record.bees().bee(slot);
			var upgraded = MemberUpgradeChange.speed(record, 1).candidate();
			assertEquals(paid, upgraded.bees().bee(slot)); assertEquals(state.slotCount(), upgraded.bees().slotCount());
			while (!record.bees().bee(slot).drained()) {
				var bee = record.bees().bee(slot);
				if (bee.pendingCycles() > 0) root = root.applyBeeWork(MEMBER, BeeWorkExecutor.advance(record.bees(), slot, bee.revision(),
						new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false)), 0, 64));
				root = root.settleBee(MEMBER, slot, root.ownedMachines().get(MEMBER).bees().bee(slot).revision());
				record = root.ownedMachines().get(MEMBER);
			}
			assertEquals(9900, record.bees().energy()); assertEquals(ProductAmount.of(500), root.ledger().balances().get(paid.plan().fluidOutput()));
			var returned = record.returnImage().copy().getCompound("extra");
			assertEquals(slot, returned.getList(BeeAssetProjection.SLOTS, 10).getCompound(0).getInt("slot_index"));
			assertEquals(state.feeding().legacySlots(), returned.getList(FeedingAssetProjection.SLOTS, 10).size());
			assertThrows(IllegalArgumentException.class, () -> state.moveBee(slot, state.slotCount(), false));
		}
	}
	@Test void memberIdentityAndFoodCapacityCannotDisguiseAnotherTier() {
		var root = fixture("ultimate"); var encoded = NetworkCheckpointCodec.encode(root);
		var bad = encoded.copy(); var feeding = bad.getList("ownership", 10).getCompound(0).getCompound("bees").getCompound("feeding");
		feeding.getList("slots", 10).removeLast();
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(bad));
		var record = root.ownedMachines().get(MEMBER);
		var foreign = new MemberClaim(record.claim().network(), MEMBER, record.claim().transfer(), record.claim().origin(), "productivebeesgenesis:basic_mek_apiary_factory");
		assertThrows(IllegalArgumentException.class, () -> new OwnedMachineRecord(foreign, record.phase(), record.assets(), record.fingerprint(), "", record.bees()));
		var old = encoded.copy(); old.putInt("schema", 14); old.getList("ownership", 10).getCompound(0).getCompound("bees").putInt("samplingVersion", 5);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(old));
	}
	@Test void oldSchemaFourteenStillRestoresAndScopeFindsEveryFactory() {
		var basic = BeeRandomCheckpointTest.fixture(1, true, null);
		var old = NetworkCheckpointCodec.encode(basic); old.putInt("schema", 14);
		old.getList("ownership", 10).getCompound(0).getCompound("bees").putInt("samplingVersion", 5);
		assertEquals(basic, CODEC.decode(old));
		for (var tier : TYPES) {
			var root = fixture(tier); var record = root.ownedMachines().get(MEMBER);
			assertTrue(TerminalScope.APIARY.accepts(record.claim().machine()));
			assertFalse(TerminalScope.CENTRIFUGE.accepts(record.claim().machine()));
			assertSame(record, root.ownedMachines().workEntry(WorkKey.Kind.BEE_CYCLE, null, false));
			assertSame(record, root.ownedMachines().workEntry(WorkKey.Kind.BEE_CYCLE, null, true));
			assertNull(root.ownedMachines().workEntry(WorkKey.Kind.CENTRIFUGE_RECIPE, null, false));
		}
	}
	@Test void streamingDecoderPreservesFactoryLayout() throws Exception {
		var root = fixture("ultimate"); var file = java.nio.file.Files.createTempFile("factory-apiary", ".dat");
		try {
			try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file)))) { NetworkCheckpointStream.write(root, out); }
			try (var service = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService();
					var decoder = CODEC.decoder(service.tryOpen(file).orElseThrow())) {
				long deadline = System.nanoTime() + 10_000_000_000L;
				while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
					assertTrue(System.nanoTime() < deadline); decoder.step(1, 1_000_000); Thread.yield();
				}
				assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
				assertEquals(root, decoder.checkpoint());
			}
		} finally { java.nio.file.Files.deleteIfExists(file); }
	}
}
