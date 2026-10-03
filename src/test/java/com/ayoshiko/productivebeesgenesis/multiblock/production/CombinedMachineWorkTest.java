package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombinedMachineWorkTest {
	private static final ProductKey COMB = key("comb", false), ITEM = key("item", false), OTHER = key("other", false), FLUID = key("fluid", true);
	private static ProductKey key(String id, boolean fluid) { return new ProductKey(fluid ? ProductKey.Kind.FLUID : ProductKey.Kind.ITEM, ResourceLocation.parse("test:" + id), new CompoundTag()); }
	private static BeeRecord bee(UUID machine, int slot, int ticks, long cost, float multiplier) {
		var entity = new CompoundTag(); entity.putString("type", "productivebees:iron");
		var original = new CompoundTag(); original.putInt("slot_index", slot); original.put("entity_data", entity); original.putInt("ticks_in_hive", 0);
		var plan = new StaticBeePlan("productivebees:iron", "test:bee", 0, 0, ticks, cost, 0, false, BeeWorkConditions.Traits.DEFAULT, COMB, 1, multiplier);
		return new BeeRecord(UUID.randomUUID(), machine, slot, new AssetImage(original), plan, 0, 0, 0, ProductAmount.ZERO);
	}
	private static CombinedMachineWork machine(long energy, List<BeeRecord> bees, FiniteProductBuffer buffer) {
		return new CombinedMachineWork(bees.isEmpty() ? UUID.randomUUID() : bees.getFirst().member(), 1, 0, 6, 2, energy, 1000, bees, Map.of(), buffer);
	}
	private static BeeWorkExecutor.Context context(BeeRecord bee) {
		return new BeeWorkExecutor.Context(true, true, true, bee.plan().recipeRevision(), bee.plan().capabilityRevision(), new BeeWorkConditions.Environment(false, false, false, false));
	}
	private static CentrifugeRecipePlan plan(int ticks, long cost, CentrifugeRecipePlan.Output... outputs) {
		return new CentrifugeRecipePlan("test:centrifuge", 0, 0, COMB, ticks, 4, cost, 1, 0, List.of(outputs));
	}
	private static CentrifugeRecipePlan.Output output(ProductKey key, int amount) { return new CentrifugeRecipePlan.Output(key, amount, amount, 1); }
	private static CombinedMachineWork change(CombinedMachineWork state, CombinedMachineWork.Change change) { return change.apply(state); }

	@Test void finiteSlotsRespectComponentsStackLimitsAndPreferExistingStacks() {
		var component = new CompoundTag(); component.putString("name", "different");
		var variant = new ProductKey(ProductKey.Kind.ITEM, COMB.id(), component);
		var buffer = FiniteProductBuffer.empty(1, 1, 1000);
		var inserted = buffer.insert(COMB, 64, 16); assertEquals(16, inserted.moved()); buffer = inserted.buffer();
		assertEquals(0, buffer.count(variant)); assertEquals(0, buffer.insert(variant, 1, 64).moved());
		assertEquals(1000, buffer.insert(FLUID, 1500, 1).moved());
		assertEquals(16, buffer.extract(COMB, Long.MAX_VALUE).moved());
		var merge = new FiniteProductBuffer(List.of(FiniteProductBuffer.Cell.empty(), new FiniteProductBuffer.Cell(ITEM, 10, 64)), List.of(), 1000).insert(ITEM, 10, 64);
		assertNull(merge.buffer().items().getFirst().key()); assertEquals(20, merge.buffer().items().get(1).count());
		assertThrows(UnsupportedOperationException.class, () -> inserted.buffer().items().clear());
	}

	@Test void twoSubsystemsSpendOneEnergyBalanceAndOldCandidatesCannotCommit() {
		var bee = bee(UUID.randomUUID(), 5, 2, 5, 1);
		var state = machine(20, List.of(bee), FiniteProductBuffer.empty(2, 1, 1000).insert(COMB, 1, 64).buffer());
		var old = state;
		var beeWork = state.advanceBee(5, 0, context(bee), 2, 1, null);
		var staleCentrifuge = state.assignCentrifuge(0, plan(4, 3, output(ITEM, 2)), 1, UUID.randomUUID(), 5);
		state = beeWork.apply(state);
		assertEquals(10, state.energy()); assertEquals(20, old.energy()); assertEquals(6, state.beeSlots());
		var advanced = state; assertThrows(IllegalArgumentException.class, () -> staleCentrifuge.apply(advanced));
		state = change(state, state.assignCentrifuge(0, plan(4, 3, output(ITEM, 2)), 1, UUID.randomUUID(), 5));
		state = change(state, state.advanceCentrifuge(0, 100, true));
		assertEquals(1, state.energy()); assertEquals(3, state.centrifuges().get(0).job().progress());
		assertFalse(state.advanceCentrifuge(0, 1, false).changed());
		state = change(state, state.receiveEnergy(2));
		state = change(state, state.advanceCentrifuge(0, 1, true));
		assertEquals(0, state.energy()); assertTrue(state.centrifuges().get(0).job().paid());
	}

	@Test void hugePaidBeeOutputDrainsPartiallyWithoutRerollOrPayment() {
		var bee = bee(UUID.randomUUID(), 4, 1, 5, Float.MAX_VALUE);
		var state = machine(10, List.of(bee), FiniteProductBuffer.empty(1, 0, 1000));
		state = change(state, state.advanceBee(4, 0, context(bee), 1, 1, null));
		var frozen = state.bee(4).frozen(); assertFalse(frozen.fitsLong()); var random = state.bee(4).random();
		var first = state.settleBee(4, 16); assertEquals(16, first.moved()); state = first.apply(state);
		assertEquals(frozen.subtract(ProductAmount.of(16)), state.bee(4).frozen()); assertEquals(random, state.bee(4).random());
		assertFalse(state.settleBee(4, 16).changed()); assertFalse(state.advanceBee(4, state.bee(4).revision(), context(state.bee(4)), 1, 1, null).changed());
		state = change(state, state.extract(COMB, 16)); state = change(state, state.settleBee(4, 16));
		assertEquals(frozen.subtract(ProductAmount.of(32)), state.bee(4).frozen()); assertEquals(5, state.energy());
	}

	@Test void multiOutputCentrifugeDrainsAcrossFullBuffersExactlyOnce() {
		var state = machine(20, List.of(), FiniteProductBuffer.empty(1, 1, 1000).insert(COMB, 1, 64).buffer());
		state = change(state, state.assignCentrifuge(0, plan(2, 3, output(ITEM, 70), output(FLUID, 1500)), 1, UUID.randomUUID(), 8));
		state = change(state, state.advanceCentrifuge(0, 2, true)); state = change(state, state.freezeCentrifuge(0));
		var frozen = state.centrifuges().get(0).job();
		var first = state.settleCentrifuge(0, key -> 64); assertEquals(1064, first.moved()); state = first.apply(state);
		assertEquals(ProductAmount.of(6), state.centrifuges().get(0).remaining(ITEM));
		assertEquals(ProductAmount.of(500), state.centrifuges().get(0).remaining(FLUID));
		assertSame(frozen, state.centrifuges().get(0).job());
		assertFalse(state.freezeCentrifuge(0).changed()); assertFalse(state.settleCentrifuge(0, key -> 64).changed());
		state = change(state, state.extract(ITEM, 64)); state = change(state, state.extract(FLUID, 1000));
		var last = state.settleCentrifuge(0, key -> 64); assertEquals(506, last.moved()); state = last.apply(state);
		assertTrue(state.centrifuges().isEmpty()); assertFalse(state.settleCentrifuge(0, key -> 64).changed());
		assertEquals(14, state.energy()); assertEquals(6, state.buffer().count(ITEM)); assertEquals(500, state.buffer().count(FLUID));
		assertThrows(IllegalArgumentException.class, () -> new CentrifugeDelivery(frozen, Map.of(ITEM, ProductAmount.of(71))));
	}

	@Test void newBeeCapabilityWaitsForTheOldPaidCycleAndNextSuccessfulPayment() {
		var bee = bee(UUID.randomUUID(), 5, 4, 2, 1);
		var state = machine(30, List.of(bee), FiniteProductBuffer.empty(2, 0, 1000));
		state = change(state, state.advanceBee(5, 0, context(bee), 1, 0, null));
		var next = new BeeWorkExecutor.Cycle(1, 7, 2, ITEM);
		state = change(state, state.advanceBee(5, state.bee(5).revision(), context(state.bee(5)), 100, 1, next));
		assertEquals(22, state.energy()); assertEquals(bee.plan(), state.bee(5).plan()); assertEquals(ProductAmount.of(1), state.bee(5).frozen());
		state = change(state, state.settleBee(5, 64));
		var denied = new BeeWorkExecutor.Context(true, true, false, 0, 0, context(bee).environment());
		assertFalse(state.advanceBee(5, state.bee(5).revision(), denied, 1, 1, next).changed());
		state = change(state, state.advanceBee(5, state.bee(5).revision(), context(state.bee(5)), 1, 1, next));
		assertEquals(15, state.energy()); assertEquals(ITEM, state.bee(5).plan().output()); assertEquals(ProductAmount.of(2), state.bee(5).frozen());
	}

	@Test void lanesCannotReuseOneInputAndZeroOutputsReleaseThePaidJob() {
		var state = machine(10, List.of(), FiniteProductBuffer.empty(1, 0, 1000).insert(COMB, 1, 64).buffer());
		var plan = plan(1, 1, output(ITEM, 0));
		state = change(state, state.assignCentrifuge(0, plan, 1, UUID.randomUUID(), 0));
		assertFalse(state.assignCentrifuge(1, plan, 1, UUID.randomUUID(), 0).changed()); assertEquals(0, state.buffer().count(COMB));
		state = change(state, state.advanceCentrifuge(0, 1, true)); state = change(state, state.freezeCentrifuge(0));
		state = change(state, state.settleCentrifuge(0, key -> 64));
		assertTrue(state.centrifuges().isEmpty()); assertEquals(9, state.energy());
	}

	@Test void failedLimitLookupCannotPartiallyPublishAResult() {
		var state = machine(10, List.of(), FiniteProductBuffer.empty(2, 0, 1000).insert(COMB, 1, 64).buffer());
		state = change(state, state.assignCentrifuge(0, plan(1, 1, output(ITEM, 2), output(OTHER, 3)), 1, UUID.randomUUID(), 0));
		state = change(state, state.advanceCentrifuge(0, 1, true)); state = change(state, state.freezeCentrifuge(0));
		var original = state;
		assertThrows(IllegalStateException.class, () -> original.settleCentrifuge(0, key -> { if (key.equals(OTHER)) throw new IllegalStateException("lookup unavailable"); return 64; }));
		assertEquals(0, original.buffer().count(ITEM)); assertTrue(original.centrifuges().get(0).delivered().isEmpty());
	}

	@Test void compositeIdentityCapacityAndDeliveryBoundsRejectBadState() {
		var id = UUID.randomUUID(); var bee = bee(id, 5, 1, 1, 1); var buffer = FiniteProductBuffer.empty(1, 0, 1000);
		assertThrows(IllegalArgumentException.class, () -> new CombinedMachineWork(UUID.randomUUID(), 1, 0, 6, 1, 0, 1, List.of(bee), Map.of(), buffer));
		assertThrows(IllegalArgumentException.class, () -> new CombinedMachineWork(id, 1, 0, 5, 1, 0, 1, List.of(bee), Map.of(), buffer));
		assertThrows(IllegalArgumentException.class, () -> new CombinedMachineWork(id, 1, 0, 6, 1, 2, 1, List.of(bee), Map.of(), buffer));
		var unpaid = new CentrifugeJob(UUID.randomUUID(), plan(2, 1, output(ITEM, 2)), 1, 0, 0, null);
		assertThrows(IllegalArgumentException.class, () -> new CentrifugeDelivery(unpaid, Map.of(ITEM, ProductAmount.of(1))));
	}
	private static CombinedMachineWork checkpointFixture() {
		var id = UUID.randomUUID(); var bees = new ArrayList<BeeRecord>();
		for (int slot = 0; slot < CombinedMachineCapacity.BEE_SLOTS; slot++) bees.add(bee(id, slot, 1, 5, Float.MAX_VALUE));
		var state = new CombinedMachineWork(id, 7, 0, CombinedMachineCapacity.BEE_SLOTS, CombinedMachineCapacity.LANES,
				1000, CombinedMachineCapacity.ENERGY_CAPACITY, bees, Map.of(),
				FiniteProductBuffer.empty(CombinedMachineCapacity.ITEM_SLOTS, CombinedMachineCapacity.FLUID_TANKS, CombinedMachineCapacity.TANK_CAPACITY));
		state = change(state, state.insert(COMB, 2, 64));
		state = change(state, state.assignCentrifuge(0, plan(4, 3, output(ITEM, 2)), 1, UUID.randomUUID(), 9));
		state = change(state, state.advanceCentrifuge(0, 1, true));
		state = change(state, state.assignCentrifuge(2, plan(2, 3, output(ITEM, 5000), output(FLUID, 100_000)), 1, UUID.randomUUID(), 8));
		state = change(state, state.advanceCentrifuge(2, 2, true));
		state = change(state, state.freezeCentrifuge(2));
		state = change(state, state.settleCentrifuge(2, key -> 64));
		state = change(state, state.advanceBee(5, 0, context(state.bee(5)), 1, 1, null));
		return state;
	}
	private static CombinedMachineWork restore(CompoundTag tag) { return CombinedWorkCodec.decode(tag, key -> {}, key -> 64, item -> {}); }
	private static net.minecraft.nbt.ListTag records(CompoundTag tag, String name) { return tag.getList(name, 10); }

	@Test void checkpointResumesPartialDeliveryAndUnpaidWorkWithoutRerollOrDoublePayment() {
		var before = checkpointFixture(); var encoded = CombinedWorkCodec.encode(before); var restored = restore(encoded);
		assertEquals(6, restored.bees().size()); assertEquals(before.machine(), restored.machine()); assertEquals(7, restored.generation());
		assertEquals(before.energy(), restored.energy()); assertEquals(before.revision(), restored.revision());
		assertEquals(before.bee(5), restored.bee(5)); assertFalse(restored.bee(5).frozen().fitsLong());
		assertEquals(before.centrifuges(), restored.centrifuges());
		assertEquals(3272, restored.centrifuges().get(2).remaining(ITEM).longSaturated());
		assertEquals(36_000, restored.centrifuges().get(2).remaining(FLUID).longSaturated());
		var frozen = restored.centrifuges().get(2).job(); long energy = restored.energy();
		long items = 0, fluids = 0;
		while (restored.centrifuges().containsKey(2)) {
			assertEquals(frozen, restored.centrifuges().get(2).job());
			items += restored.buffer().count(ITEM); fluids += restored.buffer().count(FLUID);
			restored = change(restored, restored.extract(ITEM, Long.MAX_VALUE));
			restored = change(restored, restored.extract(FLUID, Long.MAX_VALUE));
			restored = change(restored, restored.settleCentrifuge(2, key -> 64));
			restored = restore(CombinedWorkCodec.encode(restored));
		}
		assertEquals(5000, items + restored.buffer().count(ITEM)); assertEquals(100_000, fluids + restored.buffer().count(FLUID));
		assertEquals(energy, restored.energy()); assertEquals(before.bee(5).random(), restored.bee(5).random());
		restored = change(restored, restored.advanceCentrifuge(0, 3, true));
		assertEquals(energy - 9, restored.energy()); assertTrue(restored.centrifuges().get(0).job().paid());
		encoded.putLong("energy", 0); records(encoded, "bees").getCompound(5).getCompound("original").putInt("ticks_in_hive", 99);
		assertEquals(0, restored.bee(5).originalSlot().copy().getInt("ticks_in_hive")); assertEquals(986, before.energy());
	}

	@Test void checkpointRejectsIncompleteUnknownAndDuplicatedAssetsWithoutMutatingSource() {
		var encoded = CombinedWorkCodec.encode(checkpointFixture()); var stable = encoded.copy();
		for (var field : stable.getAllKeys()) {
			var bad = encoded.copy(); bad.remove(field);
			assertThrows(IllegalArgumentException.class, () -> restore(bad), field);
		}
		var mutations = List.<java.util.function.Consumer<CompoundTag>>of(
				tag -> tag.putInt("schema", 99), tag -> tag.putInt("capacityVersion", 2),
				tag -> tag.putInt("beeSlots", 3), tag -> tag.putLong("energyCapacity", 999),
				tag -> tag.putInt("energy", 0), tag -> tag.putLong("generation", 0),
				tag -> records(tag, "bees").getCompound(1).putUUID("id", records(tag, "bees").getCompound(0).getUUID("id")),
				tag -> records(tag, "bees").getCompound(0).putInt("slot", 6),
				tag -> records(tag, "bees").getCompound(0).remove("cursor"),
				tag -> records(tag, "jobs").getCompound(1).putInt("lane", 0),
				tag -> records(tag, "jobs").getCompound(1).getCompound("job").putUUID("id", records(tag, "bees").getCompound(0).getUUID("id")),
				tag -> records(tag, "jobs").getCompound(1).getCompound("job").putBoolean("sampled", false),
				tag -> records(records(tag, "jobs").getCompound(1), "delivered").getCompound(0).putLong("amount", 1_000_000),
				tag -> records(tag, "items").remove(0),
				tag -> records(tag, "items").getCompound(0).getCompound("key").putBoolean("unknown", true));
		for (var mutation : mutations) {
			var bad = encoded.copy(); mutation.accept(bad); var unchanged = bad.copy();
			assertThrows(IllegalArgumentException.class, () -> restore(bad)); assertEquals(unchanged, bad);
		}
		assertEquals(stable, encoded);
	}

	@Test void checkpointPreservesComponentVariantsAndRejectsUnavailableOrShrunkenItems() {
		var component = new CompoundTag(); component.putString("name", "variant");
		var variant = new ProductKey(ProductKey.Kind.ITEM, ITEM.id(), component);
		var state = CombinedMachineCapacity.empty(UUID.randomUUID(), 1);
		state = change(state, state.insert(ITEM, 16, 16)); state = change(state, state.insert(variant, 1, 1));
		var encoded = CombinedWorkCodec.encode(state);
		var restored = CombinedWorkCodec.decode(encoded, key -> {}, key -> key.equals(variant) ? 1 : 16, item -> {});
		assertEquals(16, restored.buffer().count(ITEM)); assertEquals(1, restored.buffer().count(variant));
		assertThrows(IllegalArgumentException.class, () -> CombinedWorkCodec.decode(encoded, key -> {}, key -> 1, item -> {}));
		assertThrows(IllegalStateException.class, () -> CombinedWorkCodec.decode(encoded, key -> { throw new IllegalStateException("missing registry entry"); }, key -> 64, item -> {}));
		assertEquals(encoded, CombinedWorkCodec.encode(state));
	}

	@Test void portTransfersStayInTheSpecifiedSlotAndRejectDifferentComponents() {
		var tag = new CompoundTag(); tag.putString("name", "variant");
		var variant = new ProductKey(ProductKey.Kind.ITEM, ITEM.id(), tag);
		var state = CombinedMachineCapacity.empty(UUID.randomUUID(), 1);
		var first = state.insertItem(5, ITEM, 64, 16);
		assertEquals(16, first.moved()); assertEquals(0, state.buffer().count(ITEM));
		state = first.apply(state); assertNull(state.buffer().items().get(0).key()); assertEquals(16, state.buffer().items().get(5).count());
		assertFalse(state.insertItem(5, variant, 10, 64).changed());
		state = change(state, state.insertItem(6, variant, 10, 64));
		state = change(state, state.insertItem(7, ITEM, 4, 16));
		var removed = state.extractItem(5, 100); assertEquals(16, removed.moved()); state = removed.apply(state);
		assertEquals(4, state.buffer().count(ITEM)); assertEquals(10, state.buffer().count(variant)); assertNull(state.buffer().items().get(5).key());
		assertFalse(state.extractItem(5, 1).changed());
		var frozen = state;
		assertThrows(IllegalArgumentException.class, () -> frozen.insertItem(0, FLUID, 1, 64));
		assertThrows(IllegalArgumentException.class, () -> frozen.extractItem(0, -1));
	}

	private static FeedingItem food(String variant, int limit) {
		var tag = new CompoundTag(); tag.putString("id", "test:flower"); tag.putInt("count", 1);
		var components = new CompoundTag(); components.putString("test:variant", variant); tag.put("components", components);
		return new FeedingItem(new AssetImage(tag), limit);
	}
	@Test void sixFeedingSlotsShareTheAssetRootAndPreserveComponentsAcrossWorkAndRestore() {
		var original = CombinedMachineCapacity.empty(UUID.randomUUID(), 1); var item = food("one", 16);
		var deposit = original.depositFeeding(5, item, 64); var staleEnergy = original.receiveEnergy(100);
		assertEquals(16, deposit.moved()); assertEquals(0, original.feeding().get(5).count());
		var state = deposit.apply(original); assertEquals(6, state.feeding().size());
		assertThrows(IllegalArgumentException.class, () -> staleEnergy.apply(state));
		assertFalse(state.depositFeeding(5, food("two", 16), 1).changed()); assertEquals(0, state.feeding().get(0).count());
		var charged = state.receiveEnergy(100).apply(state); assertSame(state.feeding(), charged.feeding());
		var saved = CombinedWorkCodec.encode(charged); var restored = restore(saved);
		assertEquals(charged.feeding(), restored.feeding()); assertEquals(100, restored.energy());
		var withdrawn = restored.withdrawFeeding(5, 7); assertEquals(7, withdrawn.moved()); restored = withdrawn.apply(restored);
		assertEquals(9, restored.feeding().get(5).count()); assertEquals(item, restored.feeding().get(5).item());
		restored = restored.withdrawFeeding(5, 64).apply(restored); assertNull(restored.feeding().get(5).item());
		assertThrows(IllegalStateException.class, () -> CombinedWorkCodec.decode(saved, key -> {}, key -> 64, value -> { throw new IllegalStateException("missing item"); }));
		assertThrows(IllegalArgumentException.class, () -> new FeedingSlotStore(0, 9, "a".repeat(64), state.feeding()));
	}
	@Test void onlyExplicitSchemaOneMigratesEmptyFeedingAndNewDamageIsRejected() {
		var old = CombinedWorkCodec.encode(checkpointFixture()); old.putInt("schema", 1); old.remove("feeding"); old.remove("upgrades"); var unchanged = old.copy();
		var restored = restore(old); assertTrue(restored.feeding().stream().allMatch(slot -> slot.item() == null));
		var newTag = CombinedWorkCodec.encode(restored); assertEquals(3, newTag.getInt("schema"));
		newTag.putInt("schema", 1); newTag.remove("feeding"); newTag.remove("upgrades"); assertEquals(old, newTag); assertEquals(unchanged, old);
		var complete = CombinedWorkCodec.encode(restored);
		for (var mutation : List.<java.util.function.Consumer<CompoundTag>>of(
				tag -> tag.remove("feeding"), tag -> records(tag, "feeding").remove(0),
				tag -> records(tag, "feeding").getCompound(5).remove("item"),
				tag -> records(tag, "feeding").getCompound(5).putInt("count", 1),
				tag -> records(tag, "feeding").getCompound(5).putInt("group", 6),
				tag -> tag.putInt("schema", 1))) {
			var broken = complete.copy(); mutation.accept(broken); var preserved = broken.copy();
			assertThrows(IllegalArgumentException.class, () -> restore(broken)); assertEquals(preserved, broken);
		}
	}
	@Test void pluginsPreserveAllExistingAssetsAndOnlyStrictOldSchemasGetEmptySlots() {
		var original = checkpointFixture(); var stale = original.receiveEnergy(1);
		var install = original.exchangeUpgrade(2, 8); assertEquals(MachineUpgrades.EMPTY, original.upgrades());
		var installed = install.apply(original);
		assertEquals(8, installed.upgrades().count(2)); assertEquals(8, install.moved());
		assertSame(original.bees(), installed.bees()); assertEquals(original.centrifuges(), installed.centrifuges());
		assertSame(original.buffer(), installed.buffer()); assertEquals(original.energy(), installed.energy());
		assertThrows(IllegalArgumentException.class, () -> stale.apply(installed));
		assertThrows(IllegalArgumentException.class, () -> installed.exchangeUpgrade(2, 1));
		assertThrows(IllegalArgumentException.class, () -> installed.exchangeUpgrade(0, -1));
		var fed = installed.depositFeeding(5, food("upgrade", 64), 3).apply(installed);
		var restored = restore(CombinedWorkCodec.encode(fed));
		assertEquals(installed.upgrades(), restored.upgrades()); assertEquals(fed.feeding(), restored.feeding());
		var returned = restored.exchangeUpgrade(2, -8).apply(restored);
		assertEquals(0, returned.upgrades().count(2)); assertEquals(2, returned.upgrades().revision());
		var legacy = CombinedWorkCodec.encode(fed); legacy.putInt("schema", 2); legacy.remove("upgrades");
		assertEquals(MachineUpgrades.EMPTY, restore(legacy).upgrades()); assertEquals(fed.feeding(), restore(legacy).feeding());
		for (var mutation : List.<java.util.function.Consumer<CompoundTag>>of(
				tag -> tag.remove("upgrades"), tag -> tag.getCompound("upgrades").remove("slot3"),
				tag -> tag.getCompound("upgrades").putInt("slot0", -1), tag -> tag.getCompound("upgrades").putInt("slot2", 9),
				tag -> tag.getCompound("upgrades").putLong("revision", -1), tag -> tag.getCompound("upgrades").putInt("extra", 1),
				tag -> tag.putInt("schema", 2))) {
			var broken = CombinedWorkCodec.encode(fed); mutation.accept(broken); var preserved = broken.copy();
			assertThrows(IllegalArgumentException.class, () -> restore(broken)); assertEquals(preserved, broken);
		}
	}
	@Test void beeExchangeChangesIdentityRetainsFoodAndCannotExtractPaidResults() {
		var state = CombinedMachineCapacity.empty(UUID.randomUUID(), 1); var seed = bee(state.machine(), 5, 2, 5, 1);
		state = state.depositFeeding(5, food("one", 64), 3).apply(state);
		var insert = state.insertBee(5, seed.originalSlot(), seed.plan()); assertTrue(state.bees().isEmpty());
		state = insert.apply(state); var firstId = state.bee(5).id();
		state = state.receiveEnergy(30).apply(state);
		state = state.advanceBee(5, 0, context(state.bee(5)), 2, 1, null).apply(state); var paid = state;
		assertThrows(IllegalStateException.class, () -> paid.extractBee(5, firstId));
		state = state.settleBee(5, 64).apply(state);
		state = state.extractBee(5, firstId).apply(state); assertTrue(state.bees().isEmpty()); assertEquals(3, state.feeding().get(5).count());
		state = state.insertBee(5, seed.originalSlot(), seed.plan()).apply(state); assertNotEquals(firstId, state.bee(5).id()); var fresh = state;
		assertThrows(IllegalArgumentException.class, () -> fresh.extractBee(5, firstId));
		state = state.advanceBee(5, 0, context(state.bee(5)), 1, 1, null).apply(state);
		state = state.extractBee(5, state.bee(5).id()).apply(state);
		assertEquals(15, state.energy()); assertEquals(1, state.buffer().count(COMB)); assertEquals(3, state.feeding().get(5).count());
	}
}
