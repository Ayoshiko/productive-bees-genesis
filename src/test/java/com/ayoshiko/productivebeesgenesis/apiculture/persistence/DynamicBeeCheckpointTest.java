package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicBeeCheckpointTest {
	private static final UUID MEMBER = new UUID(17, 3);
	private static final ProductKey FIRST = key("first");
	private static final ProductKey SECOND = key("second");
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> { });
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static ProductKey key(String id) {
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:" + id), new CompoundTag());
	}
	private static NetworkCheckpoint fixture(float multiplier, BeeGenerator generator) {
		return fixture(multiplier, generator, false);
	}
	private static NetworkCheckpoint fixture(float multiplier, BeeGenerator generator, boolean combBlock) {
		return fixture(multiplier, generator, combBlock, BeeProductionEffects.NONE);
	}
	private static NetworkCheckpoint fixture(float multiplier, BeeGenerator generator, boolean combBlock, BeeProductionEffects effects) {
		var identity = CheckpointTestData.identity();
		var claim = new MemberClaim(identity.networkId(), MEMBER, new UUID(29, 1), new Origin("minecraft:overworld", 1, 64, 2), "productivebeesgenesis:mek_apiary");
		var entity = new CompoundTag();
		String type = generator == BeeGenerator.WANNA_BEE ? "productivebees:wanna" : "productivebees:lumber_bee";
		if (generator == BeeGenerator.MYRIAD) type = "productivebees:myriadcreations";
		boolean configurable = generator == BeeGenerator.WANNA_BEE || generator == BeeGenerator.MYRIAD;
		entity.putString("id", configurable ? "productivebees:configurable_bee" : type);
		if (configurable) entity.putString("type", type);
		var slot = new CompoundTag();
		slot.putInt("slot_index", 0);
		slot.putInt("ticks_in_hive", 0);
		slot.put("entity_data", entity);
		var slots = new ListTag();
		slots.add(slot);
		var extra = new CompoundTag();
		extra.put(BeeAssetProjection.SLOTS, slots);
		var feeders = new ListTag();
		for (int index = 0; index < 9; index++) feeders.add(new CompoundTag());
		extra.put(com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingAssetProjection.SLOTS, feeders);
		extra.put(com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS, new CompoundTag());
		var image = new CompoundTag();
		image.put("extra", extra);
		image.putLong("energy", 10000);
		image.putLong("energyCapacity", 10000);
		var assets = new AssetImage(image);
		var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, assets, assets.fingerprint(), "");
		var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED);
		var plan = new StaticBeePlan(type, "test:generated", 0, 0, 1, 3, 0, false,
				BeeWorkConditions.Traits.DEFAULT, multiplier,
				generator == BeeGenerator.STATIC ? List.of(new BeeOutputPlan(FIRST, FIRST, 1, 1, 1, BeeOutputPlan.Basis.ROLL)) : List.of(),
				generator, combBlock, effects);
		var bee = new BeeRecord(new UUID(123, 456), MEMBER, 0, new AssetImage(slot), plan, 0, 0, 0, ProductAmount.ZERO);
		var state = new BeeMemberState(MEMBER, 0, 10000, 10000, List.of(bee));
		return NetworkCheckpoint.empty(identity).withOwnership(sealed).withOwnership(owned).withOwnership(owned.withBees(state));
	}
	private static BeeMemberState state(NetworkCheckpoint root) { return root.ownedMachines().get(MEMBER).bees(); }
	private static BeeWorkExecutor.Context context() {
		var source = new CompoundTag();
		source.putString("source", "fixed test input");
		return new BeeWorkExecutor.Context(true, true, true, 0, 0,
				new BeeWorkConditions.Environment(false, false, false, false), new AssetImage(source));
	}
	private static NetworkCheckpoint pay(NetworkCheckpoint root) {
		var state = state(root);
		var result = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), 1, 1);
		assertEquals(BeeWorkExecutor.Status.READY, result.status());
		assertNull(state.bee(0).generation());
		assertEquals(10000, state.energy());
		return root.applyBeeWork(MEMBER, result);
	}
	private static NetworkCheckpoint pool(NetworkCheckpoint root, List<BeeGeneration.Drop> drops) {
		var state = state(root);
		return root.applyBeeWork(MEMBER, BeeWorkExecutor.resolveGeneration(state, 0,
				state.bee(0).generation().ready(drops, state.bee(0).plan().effects())));
	}
	private static NetworkCheckpoint drain(NetworkCheckpoint root, int budget, boolean restart) {
		for (int step = 0; !state(root).bee(0).drained(); step++) {
			assertTrue(step < 100, "Dynamic cycle failed to drain");
			if (restart) root = CODEC.decode(NetworkCheckpointCodec.encode(root));
			var state = state(root);
			if (state.bee(0).deliverable()) root = root.settleBee(MEMBER, 0, state.bee(0).revision());
			else {
				var result = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), 0, budget);
				assertEquals(BeeWorkExecutor.Status.READY, result.status());
				root = root.applyBeeWork(MEMBER, result);
			}
		}
		return root;
	}
	@Test void myriadAllocationPreservesWeightsLargeAmountsAndFourfoldBlocksAcrossRestart() throws Exception {
		var drops = List.of(new BeeGeneration.Drop(FIRST, 1, 2), new BeeGeneration.Drop(SECOND, 1, 1));
		var ready = pool(pay(fixture(10, BeeGenerator.MYRIAD)), drops);
		var folder = java.nio.file.Path.of("build", "network-gates", "r3-myriad-20261016", "stream");
		java.nio.file.Files.createDirectories(folder);
		var file = java.nio.file.Files.createTempFile(folder, "weighted-pool-", ".dat");
		try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file)))) {
			NetworkCheckpointStream.write(ready, out);
		}
		try (var service = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService();
				var decoder = CODEC.decoder(service.tryOpen(file).orElseThrow())) {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < deadline);
				decoder.step(1, 1_000_000);
				Thread.yield();
			}
			assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
			assertEquals(ready, decoder.checkpoint());
			ready = decoder.checkpoint();
		}
		var normal = drain(ready, 1, true);
		assertEquals(Map.of(FIRST, ProductAmount.of(7), SECOND, ProductAmount.of(3)), normal.ledger().balances());
		var blocks = drain(pool(pay(fixture(10, BeeGenerator.MYRIAD, true)), drops), 1, true);
		assertEquals(Map.of(FIRST, ProductAmount.of(27), SECOND, ProductAmount.of(13)), blocks.ledger().balances());
		var huge = drain(pool(pay(fixture(0x1.0p80f, BeeGenerator.MYRIAD, true)), drops), 1, true);
		assertEquals(BigInteger.ONE.shiftLeft(82), huge.ledger().balances().values().stream()
				.map(ProductAmount::exact).reduce(BigInteger.ZERO, BigInteger::add));
		assertEquals(9997, state(huge).energy());
		assertEquals(1, state(huge).bee(0).random().cursor());
	}
	@Test void upgradeEventHoldsRawOutputsAndRestoresExactConversionAndOneGene() {
		var geneComponents = new CompoundTag();
		var geneValue = new CompoundTag();
		geneValue.putString("attribute", "type");
		geneValue.putString("value", "productivebees:lumber_bee");
		geneValue.putInt("purity", 100);
		geneComponents.put("productivebees:gene_group", geneValue);
		var gene = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("productivebees:gene"), geneComponents);
		var effects = new BeeProductionEffects(true, false, 1, List.of(gene),
				List.of(new BeeProductionEffects.Conversion(FIRST, SECOND, 9, 1, false)), java.util.Set.of());
		var ready = pool(pay(fixture(20, BeeGenerator.STATIC, false, effects)), List.of());
		var state = state(ready);
		var step = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), 0, 1);
		ready = ready.applyBeeWork(MEMBER, step);
		assertTrue(state(ready).bee(0).hasFrozen());
		assertFalse(state(ready).bee(0).deliverable());
		assertSame(ready, ready.settleBee(MEMBER, 0, state(ready).bee(0).revision()));
		var finished = drain(ready, 1, true);
		assertEquals(Map.of(FIRST, ProductAmount.of(2), SECOND, ProductAmount.of(2), gene, ProductAmount.of(1)), finished.ledger().balances());
		assertEquals(9997, state(finished).energy());
		assertEquals(1, state(finished).bee(0).random().cursor());
		var old = NetworkCheckpointCodec.encode(ready);
		old.putInt("schema", 17);
		old.getList("ownership", 10).getCompound(0).getCompound("bees").putInt("samplingVersion", 8);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(old));
		var huge = effects.finish(Map.of(FIRST, ProductAmount.of(BigInteger.ONE.shiftLeft(100))));
		assertEquals(BigInteger.ONE.shiftLeft(100),
				huge.get(SECOND).exact().multiply(BigInteger.valueOf(9)).add(huge.get(FIRST).exact()));
	}
	@Test void upgradeConversionPreservesBothChainRemaindersAndDoesNotRecurseOtherOutputs() {
		var finalKey = key("final");
		var honey = new ProductKey(ProductKey.Kind.FLUID, ResourceLocation.parse("test:honey"), new CompoundTag());
		var effects = new BeeProductionEffects(true, true, 0, List.of(),
				List.of(new BeeProductionEffects.Conversion(FIRST, SECOND, 9, 1, true),
						new BeeProductionEffects.Conversion(SECOND, finalKey, 3, 1, false)), java.util.Set.of(honey));
		assertEquals(Map.of(FIRST, ProductAmount.of(1), SECOND, ProductAmount.of(2), finalKey, ProductAmount.of(1)),
				effects.finish(Map.of(FIRST, ProductAmount.of(46), honey, ProductAmount.of(250))));
		var oneHop = new BeeProductionEffects(true, false, 0, List.of(),
				List.of(new BeeProductionEffects.Conversion(FIRST, SECOND, 9, 1, false),
						new BeeProductionEffects.Conversion(SECOND, finalKey, 3, 1, false)), java.util.Set.of());
		assertEquals(Map.of(SECOND, ProductAmount.of(3)), oneHop.finish(Map.of(FIRST, ProductAmount.of(27))));
	}
	@Test void oldOwnershipSchemaCannotCarryNewSealedUpgradeWithoutActiveBees() {
		var encoded = NetworkCheckpointCodec.encode(fixture(1, BeeGenerator.WANNA_BEE));
		encoded.putInt("schema", 17);
		var owned = encoded.getList("ownership", 10).getCompound(0);
		owned.put("bees", new CompoundTag());
		var counts = owned.getCompound("assets").getCompound("extra")
				.getCompound(com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS);
		counts.putInt(com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.GENE_SAMPLER.getId(), 1);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(encoded));
	}
	@Test void schemaSixteenKeepsUnweightedRequestsAndCannotHideMyriadOrWeightedPool() {
		var legacy = NetworkCheckpointCodec.encode(pool(pay(fixture(3, BeeGenerator.WANNA_BEE)),
				List.of(new BeeGeneration.Drop(FIRST, 1))));
		legacy.putInt("schema", 16);
		legacy.getList("ownership", 10).getCompound(0).getCompound("bees").putInt("samplingVersion", 7);
		assertEquals(ProductAmount.of(3), drain(CODEC.decode(legacy), 1, true).ledger().balances().get(FIRST));
		var weighted = NetworkCheckpointCodec.encode(pool(pay(fixture(3, BeeGenerator.MYRIAD)),
				List.of(new BeeGeneration.Drop(FIRST, 1, 2))));
		weighted.putInt("schema", 16);
		weighted.getList("ownership", 10).getCompound(0).getCompound("bees").putInt("samplingVersion", 7);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(weighted));
		assertThrows(IllegalArgumentException.class, () -> new BeeGeneration.Drop(FIRST, 1, Double.NaN));
	}
	@Test void unresolvedPaidRequestSurvivesRestartWithoutRegenerationOrExtraCharge() {
		var paid = CODEC.decode(NetworkCheckpointCodec.encode(pay(fixture(10, BeeGenerator.WANNA_BEE))));
		var state = state(paid);
		assertEquals(9997, state.energy());
		assertEquals(BeeGeneration.Phase.STARTED, state.bee(0).generation().phase());
		var result = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), 0, 64);
		assertEquals(BeeWorkExecutor.Status.GENERATION_UNKNOWN, result.status());
		assertSame(state, result.candidate());
		var failure = state.bee(0).generation().unknown("generator failed after entry");
		var held = paid.applyBeeWork(MEMBER, BeeWorkExecutor.resolveGeneration(state, 0, failure));
		assertEquals(held, CODEC.decode(NetworkCheckpointCodec.encode(held)));
		assertFalse(state(held).bee(0).drained());
		assertEquals(9997, state(held).energy());
		var resolved = state.bee(0).resolveGeneration(state.bee(0).generation().ready(List.of(new BeeGeneration.Drop(FIRST, 1))));
		var unproved = new BeeMemberState(MEMBER, Math.incrementExact(state.revision()), state.energy(), state.energyCapacity(),
				List.of(resolved), state.feeding(), state.networkPowered());
		assertFalse(state.sameProduction(unproved));
		assertThrows(IllegalArgumentException.class, () -> state.validateSuccessor(unproved));
	}
	@Test void correlatedChoicePoolResumesExactlyAcrossBudgetAndCheckpointBoundaries() {
		var ready = pool(pay(fixture(10000, BeeGenerator.WANNA_BEE)), List.of(new BeeGeneration.Drop(FIRST, 1), new BeeGeneration.Drop(SECOND, 1)));
		var expected = drain(ready, 64, false);
		var restored = drain(ready, 1, true);
		assertEquals(expected.ledger().balances(), restored.ledger().balances());
		assertEquals(ProductAmount.of(10000), restored.ledger().balances().get(FIRST).add(restored.ledger().balances().get(SECOND)));
		assertEquals(1, state(restored).bee(0).random().cursor());
		assertEquals(9997, state(restored).energy());
	}
	@Test void singleCandidateKeepsAmountsBeyondLongWithoutAWorldDropFallback() {
		var ready = pool(pay(fixture(0x1.0p80f, BeeGenerator.WANNA_BEE)), List.of(new BeeGeneration.Drop(FIRST, 2)));
		var done = drain(ready, 1, true);
		assertEquals(BigInteger.ONE.shiftLeft(81), done.ledger().balances().get(FIRST).exact());
		assertTrue(state(done).bee(0).drained());
	}
	@Test void allFlowerPoolProducesEachMappedOutputAndEmptyLootStillFinishes() {
		var ready = pool(pay(fixture(3, BeeGenerator.MULTI_FLOWER)), List.of(new BeeGeneration.Drop(FIRST, 2), new BeeGeneration.Drop(SECOND, 4)));
		var done = drain(ready, 1, true);
		assertEquals(Map.of(FIRST, ProductAmount.of(6), SECOND, ProductAmount.of(12)), done.ledger().balances());
		assertTrue(drain(pool(pay(fixture(3, BeeGenerator.WANNA_BEE)), List.of()), 1, true).ledger().balances().isEmpty());
	}
	@Test void oldHeaderAndForeignFrozenKeyCannotDisguiseDynamicState() {
		var paid = pay(fixture(1, BeeGenerator.WANNA_BEE));
		var encoded = NetworkCheckpointCodec.encode(paid);
		encoded.putInt("schema", 15);
		encoded.getList("ownership", 10).getCompound(0).getCompound("bees").putInt("samplingVersion", 6);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(encoded));
		var bee = state(pool(paid, List.of(new BeeGeneration.Drop(FIRST, 1)))).bee(0);
		assertThrows(IllegalArgumentException.class, () -> new BeeRecord(bee.id(), bee.member(), bee.slot(), bee.originalSlot(), bee.plan(),
				bee.revision(), 0, 1, Map.of(SECOND, ProductAmount.of(1)), bee.random(), true, null, bee.generation()));
	}
	@Test void generatedProductAdmissionIsExactAndOldSnapshotStaysUnchanged() {
		var base = new ProductPolicySnapshot(1, List.of(), List.of());
		var generated = base.withVerifiedGenerated(List.of(FIRST));
		assertFalse(base.accepts(FIRST));
		assertTrue(generated.accepts(FIRST));
		var tag = new CompoundTag();
		tag.putString("test:marker", "different component");
		assertFalse(generated.accepts(new ProductKey(FIRST.kind(), FIRST.id(), tag)));
		assertTrue(new ProductPolicyRegistry(generated).evaluate(FIRST).allowed());
		assertFalse(new ProductPolicyRegistry(base.withRevision(2)).evaluate(FIRST).allowed());
	}
	@Test void paidPoolSurvivesDisabledProductionAndChangedRecipeAndCapabilityEpochs() {
		var root = pool(pay(fixture(3, BeeGenerator.MULTI_FLOWER)), List.of(new BeeGeneration.Drop(FIRST, 2)));
		var invalidated = new BeeWorkExecutor.Context(true, false, false, 23, 41,
				new BeeWorkConditions.Environment(false, false, false, false));
		for (int step = 0; !state(root).bee(0).drained(); step++) {
			assertTrue(step < 8);
			root = CODEC.decode(NetworkCheckpointCodec.encode(root));
			var state = state(root);
			if (state.bee(0).hasFrozen()) root = root.settleBee(MEMBER, 0, state.bee(0).revision());
			else {
				var result = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), invalidated, 0, 1);
				assertEquals(BeeWorkExecutor.Status.READY, result.status());
				assertEquals(0, result.energyUsed());
				root = root.applyBeeWork(MEMBER, result);
			}
		}
		assertEquals(Map.of(FIRST, ProductAmount.of(6)), root.ledger().balances());
		assertEquals(9997, state(root).energy());
		var settled = state(root);
		var next = BeeWorkExecutor.advance(settled, 0, settled.bee(0).revision(), invalidated, 1, 1);
		assertEquals(BeeWorkExecutor.Status.STALE_PLAN, next.status());
		assertSame(settled, next.candidate());
	}
	@Test void paidCycleRetainsCombUpgradeUntilFrozenDeliveryAndNextCycleUsesNewCapability() {
		var ready = pool(pay(fixture(10, BeeGenerator.WANNA_BEE, true)), List.of(new BeeGeneration.Drop(FIRST, 2)));
		var restored = CODEC.decode(NetworkCheckpointCodec.encode(ready));
		var state = state(restored);
		assertTrue(state.bee(0).plan().generatedCombBlock());
		var nextCycle = new BeeWorkExecutor.Cycle(1, 7, 3, List.of(), false);
		var delivery = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), 0, 1, state.energy(), nextCycle);
		assertEquals(BeeWorkExecutor.Status.READY, delivery.status());
		assertEquals(0, delivery.energyUsed());
		assertTrue(delivery.candidate().bee(0).plan().generatedCombBlock());
		var done = drain(restored.applyBeeWork(MEMBER, delivery), 1, true);
		assertEquals(ProductAmount.of(20), done.ledger().balances().get(FIRST));
		assertEquals(9997, state(done).energy());
		state = state(done);
		var next = BeeWorkExecutor.advance(state, 0, state.bee(0).revision(), context(), 1, 1, state.energy(), nextCycle);
		assertEquals(BeeWorkExecutor.Status.READY, next.status());
		var nextRestored = CODEC.decode(NetworkCheckpointCodec.encode(done.applyBeeWork(MEMBER, next)));
		assertFalse(state(nextRestored).bee(0).plan().generatedCombBlock());
		assertEquals(3, state(nextRestored).bee(0).plan().productionMultiplier());
		assertEquals(9990, state(nextRestored).energy());
		assertEquals(BeeGeneration.Phase.STARTED, state(nextRestored).bee(0).generation().phase());
	}
}
