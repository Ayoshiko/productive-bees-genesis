package com.ayoshiko.productivebeesgenesis.apiculture.feeding;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedingExchangePlanTest {
	private static FeedingItem item(String id, int limit) {
		var tag = new CompoundTag(); tag.putString("id", id); tag.putInt("count", 1);
		return new FeedingItem(new AssetImage(tag), limit);
	}
	private static final FeedingItem IRON = item("minecraft:iron_block", 64), EGG = item("minecraft:egg", 16);
	private static FeedingSlotStore store(FeedingItem item, int count, boolean disabled) {
		return new FeedingSlotStore(0, 9, "0".repeat(64), List.of(new FeedingSlotStore.Slot(item, count, disabled, 0),
				new FeedingSlotStore.Slot(null, 0, false, 0), new FeedingSlotStore.Slot(null, 0, false, 2)));
	}
	@Test void depositUsesOnlyFiniteSpaceAndDoesNotMutateSimulationSource() {
		var before = store(IRON, 63, true); var plan = before.deposit(0, IRON, Integer.MAX_VALUE);
		assertEquals(1, plan.moved()); assertEquals(63, before.slots().get(0).count());
		var after = plan.apply(before); assertEquals(64, after.slots().get(0).count()); assertTrue(after.slots().get(0).disabled());
		assertEquals(1, after.revision()); assertSame(after, after.deposit(0, IRON, 1).apply(after));
	}
	@Test void withdrawalUsesSelectedSlotRatherThanOtherSharedSamples() {
		var before = store(IRON, 7, true); var empty = before.withdraw(1, 64);
		assertEquals(0, empty.moved()); assertSame(before, empty.apply(before));
		var plan = before.withdraw(0, 64); assertEquals(7, plan.moved()); var after = plan.apply(before);
		assertNull(after.slots().get(0).item()); assertFalse(after.slots().get(0).disabled()); assertEquals(0, after.slots().get(1).group());
	}
	@Test void limitsFollowItemAndComponentsMustMatch() {
		var before = store(EGG, 15, false); assertEquals(1, before.deposit(0, EGG, 64).moved());
		assertEquals(0, before.deposit(0, IRON, 1).moved());
		var tag = IRON.unit().copy(); var components = new CompoundTag(); components.putString("minecraft:custom_name", "\"named\""); tag.put("components", components);
		var named = new FeedingItem(new AssetImage(tag), 64);
		assertEquals(0, store(IRON, 1, false).deposit(0, named, 1).moved());
	}
	@Test void stalePlansCannotReplayAfterCompetingTransferOrRoundTrip() {
		var before = store(IRON, 2, false); var first = before.withdraw(0, 1); var competitor = before.deposit(0, IRON, 1);
		var changed = first.apply(before);
		assertThrows(IllegalArgumentException.class, () -> first.apply(changed));
		assertThrows(IllegalArgumentException.class, () -> competitor.apply(changed));
		var restored = new FeedingSlotStore(before.revision(), before.legacySlots(), before.sourceFingerprint(), before.slots());
		assertThrows(IllegalArgumentException.class, () -> first.apply(restored));
	}
	@Test void boundedRandomExchangesConserveIndependentExternalCount() {
		var current = store(IRON, 1, false); int external = 99; var random = new java.util.Random(20260922);
		for (int i = 0; i < 2000; i++) {
			int request = 1 + random.nextInt(64); boolean deposit = random.nextBoolean() && external > 0;
			int expected = deposit ? Math.min(Math.min(request, external), 64 - current.slots().get(0).count()) : Math.min(request, current.slots().get(0).count());
			var plan = deposit ? current.deposit(0, IRON, Math.min(request, external)) : current.withdraw(0, request);
			assertEquals(expected, plan.moved()); current = plan.apply(current); external += deposit ? -expected : expected;
			assertEquals(100, external + current.slots().get(0).count());
		}
	}
	@Test void rejectsInvalidRequestsBeforeCreatingCandidate() {
		var before = store(IRON, 1, false);
		assertThrows(IllegalArgumentException.class, () -> before.deposit(0, IRON, 0));
		assertThrows(IllegalArgumentException.class, () -> before.withdraw(0, -1));
		assertThrows(IndexOutOfBoundsException.class, () -> before.deposit(3, IRON, 1));
		assertThrows(IndexOutOfBoundsException.class, () -> before.withdraw(-1, 1));
	}
}
