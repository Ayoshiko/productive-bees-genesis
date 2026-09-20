package com.ayoshiko.productivebeesgenesis.apiculture.feeding;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedingSlotStoreTest {
	private static FeedingItem item(String id, int limit) { var tag = new CompoundTag(); tag.putString("id", id); tag.putInt("count", 1); return new FeedingItem(new AssetImage(tag), limit); }
	private static final FeedingItem IRON = item("minecraft:iron_block", 64), FLOWER = item("minecraft:poppy", 64);
	private static FeedingSlotStore source(int count) {
		return new FeedingSlotStore(0, 9, "0".repeat(64), List.of(new FeedingSlotStore.Slot(IRON, count, false, 0),
				new FeedingSlotStore.Slot(null, 0, false, 1), new FeedingSlotStore.Slot(null, 0, false, 2)));
	}
	@Test void onePhysicalSampleDoesNotFeedUnlinkedBeeSlots() {
		var store = source(1);
		assertEquals(3, store.slots().size()); assertTrue(store.matches(0, IRON::equals)); assertFalse(store.matches(1, IRON::equals));
		var plan = store.groups(List.of(0, 0, 2));
		assertFalse(store.matches(1, IRON::equals)); var grouped = plan.apply(store);
		assertTrue(grouped.matches(1, IRON::equals)); assertFalse(grouped.matches(2, IRON::equals)); assertEquals(1, total(grouped));
		assertFalse(grouped.matches(0, FLOWER::equals));
	}
	@Test void twoRequestsCannotReserveTheSameLastItem() {
		var store = source(1); store = store.groups(List.of(0, 0, 2)).apply(store);
		assertTrue(store.consume(List.of(new FeedingSlotStore.Demand(0, IRON, 1), new FeedingSlotStore.Demand(1, IRON, 1))).isEmpty());
		assertEquals(1, total(store));
		var first = store.consume(List.of(new FeedingSlotStore.Demand(0, IRON, 1))).orElseThrow();
		var competing = store.consume(List.of(new FeedingSlotStore.Demand(1, IRON, 1))).orElseThrow();
		var consumed = first.apply(store); assertEquals(0, total(consumed));
		assertThrows(IllegalArgumentException.class, () -> competing.apply(consumed));
		assertThrows(IllegalArgumentException.class, () -> first.apply(consumed));
	}
	@Test void batchConsumptionUsesRemainingAmountsAndHonorsDisabledSlots() {
		var store = new FeedingSlotStore(0, 9, "0".repeat(64), List.of(new FeedingSlotStore.Slot(IRON, 2, false, 0),
				new FeedingSlotStore.Slot(IRON, 3, false, 0), new FeedingSlotStore.Slot(IRON, 20, true, 0)));
		assertTrue(store.consume(List.of(new FeedingSlotStore.Demand(0, IRON, 6))).isEmpty());
		var after = store.consume(List.of(new FeedingSlotStore.Demand(0, IRON, 4), new FeedingSlotStore.Demand(1, IRON, 1))).orElseThrow().apply(store);
		assertEquals(20, total(after)); assertFalse(after.matches(0, IRON::equals)); assertEquals(25, total(store));
	}
	@Test void refillTransfersOnlyActualSpaceAndPreservesRemainderAndFlags() {
		var store = new FeedingSlotStore(0, 9, "0".repeat(64), List.of(new FeedingSlotStore.Slot(IRON, 8, true, 0),
				new FeedingSlotStore.Slot(IRON, 63, true, 1), new FeedingSlotStore.Slot(null, 0, false, 2)));
		var plan = store.refill(0, 1, 8); assertEquals(1, plan.moved()); var after = plan.apply(store);
		assertEquals(7, after.slots().get(0).count()); assertEquals(64, after.slots().get(1).count()); assertEquals(total(store), total(after));
		var moved = after.refill(0, 2, 7).apply(after); assertFalse(moved.slots().get(0).disabled()); assertTrue(moved.slots().get(2).disabled());
		assertEquals(0, moved.refill(2, 1, 7).moved());
	}
	@Test void emptySlotCannotRetainAnInvisibleDisabledFlag() {
		var store = source(1); assertSame(store, store.disabled(2, true).apply(store));
		var disabled = store.disabled(0, true).apply(store); assertFalse(disabled.matches(0, IRON::equals));
		assertEquals(0, disabled.refill(0, 1, 1).apply(disabled).slots().get(0).count());
	}
	@Test void changedGroupsInvalidateUncommittedConsumptionAndTransferPlans() {
		var store = source(2); var consume = store.consume(List.of(new FeedingSlotStore.Demand(0, IRON, 1))).orElseThrow(); var transfer = store.refill(0, 1, 1);
		var changed = store.groups(List.of(0, 0, 0)).apply(store);
		assertThrows(IllegalArgumentException.class, () -> consume.apply(changed)); assertThrows(IllegalArgumentException.class, () -> transfer.apply(changed));
	}
	@Test void rejectsInvalidGroupsAndInventoryBounds() {
		assertThrows(IllegalArgumentException.class, () -> source(65));
		assertThrows(IllegalArgumentException.class, () -> source(1).groups(List.of(1, 0, 2)));
		assertThrows(IllegalArgumentException.class, () -> source(1).groups(List.of(0, 0)));
		assertThrows(IllegalArgumentException.class, () -> source(1).refill(0, 0, 1));
		var store = source(1); var grouped = store.groups(List.of(0, 0, 2)).apply(store);
		assertThrows(IllegalArgumentException.class, () -> grouped.moveWithBee(0, 2));
	}
	private static int total(FeedingSlotStore store) { return store.slots().stream().mapToInt(FeedingSlotStore.Slot::count).sum(); }
}
