package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BeeMultiOutputTest {
	private static final UUID MEMBER = new UUID(17, 3);
	private static ProductKey key(String variant, ProductKey.Kind kind) {
		var components = new CompoundTag(); components.putString("variant", variant);
		return new ProductKey(kind, ResourceLocation.parse("test:output"), components);
	}
	private static final ProductKey A = key("a", ProductKey.Kind.ITEM), B = key("b", ProductKey.Kind.ITEM), HONEY = key("honey", ProductKey.Kind.FLUID);
	private static BeeOutputPlan item(ProductKey key, int min, int max, float chance) { return new BeeOutputPlan(key, key, min, max, chance, BeeOutputPlan.Basis.ROLL); }
	private static final BeeOutputPlan FLUID = new BeeOutputPlan(HONEY, HONEY, 250, 250, 1, BeeOutputPlan.Basis.CYCLE);
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> {});
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static BeeRecord bee(NetworkCheckpoint root) { return root.ownedMachines().get(MEMBER).bees().bee(0); }
	private static BeeWorkExecutor.Context context(BeeRecord bee) { return new BeeWorkExecutor.Context(true, true, true, 0, bee.plan().capabilityRevision(), new BeeWorkConditions.Environment(false, false, false, false)); }
	private static NetworkCheckpoint step(NetworkCheckpoint root, int ticks, int budget) {
		var bee = bee(root); var result = BeeWorkExecutor.advance(root.ownedMachines().get(MEMBER).bees(), 0, bee.revision(), context(bee), ticks, budget);
		assertEquals(BeeWorkExecutor.Status.READY, result.status()); var next = root.applyBeeWork(MEMBER, result);
		assertSame(next, next.applyBeeWork(MEMBER, result)); return next;
	}
	private static NetworkCheckpoint finish(NetworkCheckpoint root, int budget, boolean reload) {
		for (int i = 0; bee(root).pendingCycles() > 0; i++) {
			assertTrue(i < 10000); root = step(root, 0, budget);
			if (reload) root = CODEC.decode(NetworkCheckpointCodec.encode(root));
			root = root.settleBee(MEMBER, 0, bee(root).revision());
		}
		return root;
	}
	@Test void completeKeysShareFractionalRollsAndHoneyCountsCycles() {
		var initial = BeeRandomCheckpointTest.fixture(2.5f, false, List.of(item(A, 1, 1, 1), item(B, 2, 2, 1), FLUID));
		var paid = step(initial, 35, 0); var done = finish(paid, 1, true);
		var random = new SplittableRandom(bee(initial).random().seed()); long rolls = 0;
		for (int i = 0; i < 7; i++) rolls += 2 + (random.nextDouble() < .5 ? 1 : 0);
		assertEquals(Map.of(A, ProductAmount.of(rolls * 3), B, ProductAmount.of(rolls * 6), HONEY, ProductAmount.of(1750)), done.ledger().balances());
		assertEquals(9650, done.ownedMachines().get(MEMBER).bees().energy()); assertTrue(bee(done).drained());
	}
	@Test void stochasticRollsBeyondIntResumeWithIdenticalResultsAcrossBudgetsAndReload() {
		var initial = BeeRandomCheckpointTest.fixture(2_148_000_000f, false, List.of(item(A, 1, 3, .3f), item(B, 2, 2, 1), FLUID));
		var paid = step(initial, 5, 0); var partial = step(paid, 0, 1);
		assertEquals(1_000_000, bee(partial).sampling().consumed().longSaturated()); assertEquals(0, bee(partial).random().cursor());
		assertThrows(IllegalStateException.class, () -> partial.ownedMachines().get(MEMBER).returnImage());
		assertEquals(BeeWorkExecutor.Status.DRAIN_FIRST, BeeWorkExecutor.advance(partial.ownedMachines().get(MEMBER).bees(), 0, bee(partial).revision(), context(bee(partial)), 1, 1).status());
		var one = finish(partial, 1, true); var bulk = finish(paid, 64, false);
		assertEquals(one.ledger().balances(), bulk.ledger().balances()); assertEquals(1, bee(one).random().cursor());
		long rolls = BeeProductionRollPlan.fromMultiplier(bee(initial).plan().productionMultiplier()).fixedRolls().longSaturated();
		assertTrue(rolls > Integer.MAX_VALUE); assertEquals(ProductAmount.of(rolls * 6), one.ledger().balances().get(B));
		assertEquals(ProductAmount.of(250), one.ledger().balances().get(HONEY)); assertEquals(9950, one.ownedMachines().get(MEMBER).bees().energy());
	}
	@Test void fixedOutputsKeepAmountsBeyondLongAndZeroChanceDoesNotCreateAnEntry() {
		var initial = BeeRandomCheckpointTest.fixture(Float.MAX_VALUE, false, List.of(item(A, 1, 1, 1), item(B, 0, 4, 0), FLUID));
		var done = finish(step(initial, 5, 0), 1, true);
		assertEquals(BigInteger.valueOf(0xffffff).shiftLeft(104).multiply(BigInteger.valueOf(3)), done.ledger().balances().get(A).exact());
		assertFalse(done.ledger().balances().containsKey(B)); assertEquals(ProductAmount.of(250), done.ledger().balances().get(HONEY));
	}
	@Test void corruptDistributionCursorAndMixedSchemaCannotBecomeFreshWork() {
		var root = step(BeeRandomCheckpointTest.fixture(2_000_000f, false, List.of(item(A, 0, 3, .5f), FLUID)), 5, 1);
		var encoded = NetworkCheckpointCodec.encode(root);
		for (var field : List.of("outputs", "sampling", "frozenOutputs", "cursor")) {
			var bad = encoded.copy(); var bee = bad.getList("ownership", 10).getCompound(0).getCompound("bees").getList("bees", 10).getCompound(0);
			(field.equals("outputs") ? bee.getCompound("plan") : bee).remove(field);
			assertThrows(IllegalArgumentException.class, () -> CODEC.decode(bad));
		}
		var bad = encoded.copy(); bad.putInt("schema", 13); assertThrows(IllegalArgumentException.class, () -> CODEC.decode(bad));
		var source = bee(root);
		assertThrows(IllegalArgumentException.class, () -> new BeeRecord(source.id(), MEMBER, 0, source.originalSlot(), source.plan(), source.revision(), 0, 1, source.frozenOutputs(), source.random(), true, new BeeSamplingCursor(source.sampling().rolls(), 0, ProductAmount.of(1))));
	}
	@Test void streamWriterAndBudgetedReaderPreservePartiallySampledOutputs() throws Exception {
		var root = step(BeeRandomCheckpointTest.fixture(2_000_000f, false, List.of(item(A, 0, 3, .5f), FLUID)), 5, 1);
		var file = java.nio.file.Files.createTempFile("bee-multi-output", ".dat");
		try {
			try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file)))) { NetworkCheckpointStream.write(root, out); }
			try (var reader = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
				long deadline = System.nanoTime() + 10_000_000_000L;
				while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
					assertTrue(System.nanoTime() < deadline); decoder.step(1, 1_000_000); Thread.yield();
				}
				assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
				assertEquals(root, decoder.checkpoint()); assertEquals(finish(root, 64, false).ledger(), finish(decoder.checkpoint(), 64, false).ledger());
			}
		} finally { java.nio.file.Files.deleteIfExists(file); }
	}
	@Test void finiteDeliveryKeepsVariantsAndPaidCursorWhilePausedAcrossMachineReload() {
		var source = bee(BeeRandomCheckpointTest.fixture(2_000_000f, false, List.of(item(A, 1, 3, .5f), item(B, 1, 1, 1), FLUID)));
		var work = CombinedMachineCapacity.empty(MEMBER, 1); work = work.insertBee(0, source.originalSlot(), source.plan()).apply(work); work = work.receiveEnergy(100).apply(work);
		work = work.advanceBee(0, 0, context(work.bee(0)), 5, 1, null).apply(work); work = work.beeEnabled(0, work.bee(0).id(), false).apply(work);
		var cursor = work.bee(0).sampling(); work = work.settleBee(0, key -> key.equals(A) ? 1 : 16).apply(work);
		assertEquals(CombinedMachineCapacity.ITEM_SLOTS, work.buffer().count(A)); assertNotNull(work.bee(0).sampling());
		var held = CombinedWorkCodec.decode(CombinedWorkCodec.encode(work), key -> {}, key -> key.equals(A) ? 1 : 16, food -> {});
		assertFalse(held.bee(0).enabled()); assertEquals(cursor, held.bee(0).sampling()); assertEquals(work.bee(0), held.bee(0));
		assertFalse(held.settleBee(0, key -> 1).changed()); assertThrows(IllegalStateException.class, () -> held.extractBee(0, held.bee(0).id()));
		var resumed = held.advanceBee(0, held.bee(0).revision(), context(held.bee(0)), 0, 64, null).apply(held);
		assertEquals(50, resumed.energy()); assertEquals(1, resumed.bee(0).random().cursor());
		assertEquals(ProductAmount.of(6_000_000), resumed.bee(0).frozenOutputs().get(B)); assertEquals(ProductAmount.of(250), resumed.bee(0).frozenOutputs().get(HONEY));
	}
}
