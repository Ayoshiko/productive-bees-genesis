package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
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
	private static CombinedMachineWork restore(CompoundTag tag) { return CombinedWorkCodec.decode(tag, key -> {}, key -> 64); }
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
				tag -> tag.putInt("schema", 2), tag -> tag.putInt("capacityVersion", 2),
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
		var restored = CombinedWorkCodec.decode(encoded, key -> {}, key -> key.equals(variant) ? 1 : 16);
		assertEquals(16, restored.buffer().count(ITEM)); assertEquals(1, restored.buffer().count(variant));
		assertThrows(IllegalArgumentException.class, () -> CombinedWorkCodec.decode(encoded, key -> {}, key -> 1));
		assertThrows(IllegalStateException.class, () -> CombinedWorkCodec.decode(encoded, key -> { throw new IllegalStateException("missing registry entry"); }, key -> 64));
		assertEquals(encoded, CombinedWorkCodec.encode(state));
	}

}
