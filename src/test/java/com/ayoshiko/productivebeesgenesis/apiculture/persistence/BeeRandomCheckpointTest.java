package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BeeRandomCheckpointTest {
	@TempDir Path folder;
	private static final UUID MEMBER = new UUID(17, 3);
	private static final ProductKey COMB = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("productivebees:configurable_honeycomb"), new CompoundTag());
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> assertEquals(COMB, key));
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }

	private NetworkCheckpoint fixture(float multiplier) {
		var identity = CheckpointTestData.identity();
		var claim = new MemberClaim(identity.networkId(), MEMBER, new UUID(29, 1), new Origin("minecraft:overworld", 1, 64, 2), "productivebeesgenesis:mek_apiary");
		var entity = new CompoundTag(); entity.putString("type", "productivebees:iron");
		var slot = new CompoundTag(); slot.putInt("slot_index", 0); slot.putInt("ticks_in_hive", 0); slot.put("entity_data", entity);
		var slots = new ListTag(); slots.add(slot); var extra = new CompoundTag(); extra.put(BeeAssetProjection.SLOTS, slots);
		var feeders = new ListTag(); for (int i = 0; i < 9; i++) feeders.add(new CompoundTag());
		extra.put(com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingAssetProjection.SLOTS, feeders);
		var image = new CompoundTag(); image.put("extra", extra); image.putLong("energy", 10000); image.putLong("energyCapacity", 10000);
		var assets = new AssetImage(image);
		var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, assets, assets.fingerprint(), "");
		var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED);
		var plan = new StaticBeePlan("productivebees:iron", "productivebees:bee_produce/iron", 0, 0, 5, 10, 2, true,
				BeeWorkConditions.Traits.DEFAULT, COMB, 1, multiplier);
		var bee = new BeeRecord(new UUID(123, 456), MEMBER, 0, new AssetImage(slot), plan, 0, 0, 0, ProductAmount.ZERO);
		var state = new BeeMemberState(MEMBER, 0, 10000, 10000, List.of(bee));
		return NetworkCheckpoint.empty(identity).withOwnership(sealed).withOwnership(owned).withOwnership(owned.withBees(state));
	}
	private static BeeMemberState state(NetworkCheckpoint value) { return value.ownedMachines().get(MEMBER).bees(); }
	private static BeeWorkExecutor.Context context() { return new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false)); }
	private static BeeWorkExecutor.Result candidate(NetworkCheckpoint current, int ticks, int budget) {
		var state = state(current);
		return BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), ticks, budget);
	}
	private static NetworkCheckpoint work(NetworkCheckpoint current, int ticks, int budget) {
		var result = candidate(current, ticks, budget); assertEquals(BeeWorkExecutor.Status.READY, result.status());
		return current.applyBeeWork(MEMBER, result);
	}
	private static NetworkCheckpoint settle(NetworkCheckpoint current) { return current.settleBee(MEMBER, 0, state(current).bee(0).revision()); }
	private static BigInteger oracle(long seed, int cycles, float multiplier) {
		var random = new SplittableRandom(seed); long rolls = 0;
		for (int i = 0; i < cycles; i++) rolls += (long) Math.floor(multiplier) + (random.nextDouble() < multiplier - Math.floor(multiplier) ? 1 : 0);
		return BigInteger.valueOf(rolls * 3);
	}

	@Test void candidateDiscardReplayAndGeneralMutationCannotChangeRandomAuthority() {
		var before = fixture(2.5f); var first = candidate(before, 1000, 7); var repeated = candidate(before, 1000, 7);
		assertEquals(first.candidate(), repeated.candidate());
		assertEquals(0, state(before).bee(0).random().cursor()); assertEquals(10000, state(before).energy());
		assertThrows(IllegalArgumentException.class, () -> before.ownedMachines().get(MEMBER).withBees(first.candidate()));
		var after = before.applyBeeWork(MEMBER, first);
		assertEquals(7, state(after).bee(0).random().cursor());
		assertSame(after, after.applyBeeWork(MEMBER, first));
		assertEquals(0, state(after).energy());
		assertEquals(oracle(state(before).bee(0).random().seed(), 7, 2.5f), state(after).bee(0).frozen().exact());
		var bee = state(after).bee(0);
		assertEquals(bee.random(), bee.work(bee.progress(), bee.pendingCycles(), ProductAmount.ZERO).random());
		assertEquals(2.5f, bee.plan().retime(2, 3).productionMultiplier());
	}

	@Test void partitionedSamplingAndSettlementMatchOneCycleOracleAcrossFiles() throws Exception {
		var initial = fixture(2.25f); var current = work(initial, 1000, 0);
		assertEquals(200, state(current).bee(0).pendingCycles()); assertEquals(0, state(current).bee(0).random().cursor());
		var large = work(current, 0, Integer.MAX_VALUE);
		assertEquals(64, state(large).bee(0).random().cursor());
		assertEquals(136, state(large).bee(0).pendingCycles());
		int index = 0;
		for (int budget : new int[]{1, 7, 32, 64, 3, 64, 64}) {
			if (state(current).bee(0).pendingCycles() == 0) break;
			current = roundTrip(work(current, 0, budget), "partition-" + index++);
			current = settle(current);
		}
		assertEquals(200, state(current).bee(0).random().cursor()); assertTrue(state(current).drained());
		assertEquals(oracle(state(initial).bee(0).random().seed(), 200, 2.25f), current.ledger().balances().get(COMB).exact());
		assertEquals(0, state(current).energy());
		while (state(large).bee(0).pendingCycles() > 0) large = work(large, 0, Integer.MAX_VALUE);
		large = settle(large);
		assertEquals(current.ledger().balances(), large.ledger().balances());
		assertEquals(state(current).bee(0).random(), state(large).bee(0).random());
	}

	@Test void absentConditionsAndOldPartialCyclesPreserveStream() {
		var before = fixture(2.5f); var state = state(before); var bee = state.bee(0);
		var blocked = new BeeWorkExecutor.Context(true, false, false, 0, 0, context().environment());
		assertSame(state, BeeWorkExecutor.advance(state, 0, bee.revision(), blocked, 1, 10).candidate());
		assertSame(state, BeeWorkExecutor.advance(state, 0, bee.revision(), context(), 1001, 10).candidate());
		assertSame(state, BeeWorkExecutor.advance(state, 0, bee.revision() + 1, context(), 1, 10).candidate());
		var partial = work(before, 3, 0);
		assertEquals(bee.random(), state(partial).bee(0).random());
		assertThrows(IllegalArgumentException.class, () -> BeeWorkExecutor.advance(state(partial), 0, state(partial).bee(0).revision(), context(), 1, 1, state(partial).energy(), new BeeWorkExecutor.Timing(2, 3)));
		var paid = work(partial, 2, 0);
		var result = BeeWorkExecutor.advance(state(paid), 0, state(paid).bee(0).revision(), blocked, 0, 1);
		assertEquals(BeeWorkExecutor.Status.READY, result.status()); assertEquals(0, result.energyUsed());
	}

	@Test void integerMultiplierAggregatesBeyondLongWithoutEnumeratingRolls() throws Exception {
		var current = fixture(Float.MAX_VALUE);
		current = roundTrip(work(current, 5, 1), "huge-output");
		var exact = BigInteger.valueOf(0xffffff).shiftLeft(104).multiply(BigInteger.valueOf(3));
		assertEquals(exact, state(current).bee(0).frozen().exact());
		assertEquals(1, state(current).bee(0).random().cursor());
		assertEquals(exact, settle(current).ledger().balances().get(COMB).exact());
	}

	@Test void legacySixRestoresExactPaidStateAndNewSevenRejectsMissingOrMixedFields() throws Exception {
		var initial = fixture(1); var paid = work(initial, 15, 0);
		var old = legacy(NetworkCheckpointCodec.encode(paid));
		assertEquals(paid, CODEC.decode(old));
		assertEquals(paid, decodeFile(old, "legacy", true));
		var encoded = NetworkCheckpointCodec.encode(work(paid, 0, 1));
		List<Consumer<CompoundTag>> mutations = List.of(
				root -> beeState(root).remove("samplingVersion"),
				root -> beeState(root).putInt("samplingVersion", 2),
				root -> bee(root).remove("cursor"),
				root -> bee(root).remove("seed"),
				root -> bee(root).putInt("cursor", 1),
				root -> bee(root).putLong("cursor", -1),
				root -> bee(root).putLong("cursor", Long.MAX_VALUE),
				root -> bee(root).getCompound("plan").remove("multiplier"),
				root -> bee(root).getCompound("plan").putDouble("multiplier", 2.5),
				root -> bee(root).getCompound("plan").putFloat("multiplier", Float.NaN),
				root -> root.putInt("schema", 6),
				root -> root.putInt("schema", 8));
		int index = 0;
		for (var mutation : mutations) {
			var invalid = encoded.copy(); mutation.accept(invalid);
			assertThrows(RuntimeException.class, () -> CODEC.decode(invalid));
			decodeFile(invalid, "invalid-" + index++, false);
		}
		var disguised = legacy(encoded); disguised.putInt("schema", 7);
		assertThrows(RuntimeException.class, () -> CODEC.decode(disguised));
		decodeFile(disguised, "disguised", false);
	}

	@Test void settledMovedAndReinsertedBeesKeepTheRightStreamIdentity() throws Exception {
		var current = settle(work(fixture(2.5f), 5, 1));
		var original = state(current).bee(0);
		var moved = state(current).moveBee(0, 1, false);
		assertEquals(original.random(), moved.bee(1).random());
		current = current.withOwnership(current.ownedMachines().get(MEMBER).withBees(moved));
		current = roundTrip(current, "moved");
		var remove = BeeRosterChange.extract(state(current), 1, original.id());
		current = current.exchangeBee(remove);
		var raw = original.originalSlot().copy();
		var insertion = BeeRosterChange.insert(state(current), 0, new AssetImage(raw), original.plan());
		assertNotEquals(original.id(), insertion.bee().id());
		assertNotEquals(original.random().seed(), insertion.bee().random().seed());
		assertEquals(0, insertion.bee().random().cursor());
	}
	private static CompoundTag beeState(CompoundTag root) { return root.getList("ownership", 10).getCompound(0).getCompound("bees"); }
	private static CompoundTag bee(CompoundTag root) { return beeState(root).getList("bees", 10).getCompound(0); }
	private static CompoundTag legacy(CompoundTag encoded) {
		encoded.putInt("schema", 6); beeState(encoded).remove("samplingVersion");
		for (var raw : beeState(encoded).getList("bees", 10)) {
			var bee = (CompoundTag) raw; bee.remove("seed"); bee.remove("cursor"); bee.getCompound("plan").remove("multiplier");
		}
		return encoded;
	}
	private NetworkCheckpoint roundTrip(NetworkCheckpoint expected, String name) throws Exception {
		var tag = NetworkCheckpointCodec.encode(expected); assertEquals(expected, CODEC.decode(tag));
		var restored = decodeFile(tag, name, true); assertEquals(expected, restored); return restored;
	}
	private NetworkCheckpoint decodeFile(CompoundTag data, String name, boolean valid) throws Exception {
		var path = folder.resolve(name + ".dat");
		// 故意把 schema 写在 ownership 之后，证明新旧格式校验不依赖 NBT 字段顺序。
		try (var output = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(path)))) {
			var stream = new NbtStream(output); stream.root(SharedConstants.getCurrentVersion().getDataVersion().getVersion());
			for (var nameKey : data.getAllKeys()) if (!nameKey.equals("schema")) stream.tag(nameKey, data.get(nameKey));
			stream.integer("schema", data.getInt("schema")); stream.end(); stream.end();
		}
		var bytes = Files.readAllBytes(path);
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(path).orElseThrow())) {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < deadline); decoder.step(1, 1_000_000); Thread.yield();
			}
			assertArrayEquals(bytes, Files.readAllBytes(path));
			assertEquals(valid ? CheckpointDecoder.State.COMPLETE : CheckpointDecoder.State.FAILED, decoder.progress().state(), decoder.progress().failure());
			return valid ? decoder.checkpoint() : null;
		}
	}
}
