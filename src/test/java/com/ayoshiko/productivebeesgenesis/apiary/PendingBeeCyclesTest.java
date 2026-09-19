package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingBeeCyclesTest {
	@Test void snapshotPreservesPaidCyclesAndDoesNotShareArrays() {
		var parent = new CompoundTag(); int[] counts = {2, 0, Integer.MAX_VALUE}; PendingBeeCycles.save(parent, counts, 7, 2);
		counts[0] = 999; var restored = PendingBeeCycles.read(parent, 3);
		assertArrayEquals(new int[]{2, 0, Integer.MAX_VALUE}, restored.counts()); assertEquals(Integer.MAX_VALUE, restored.total());
		assertEquals(7, restored.flushTicks()); assertEquals(2, restored.rotation());
		var alias = restored.counts(); alias[0] = 333; assertEquals(2, restored.counts()[0]);
		parent.getCompound(PendingBeeCycles.KEY).getIntArray("counts")[0] = 444; assertEquals(2, restored.counts()[0]);
	}
	@Test void zeroAndLegacyHaveNoPendingStateAndUpgradeAddsOnlyEmptySlots() {
		var tag = new CompoundTag(); assertEquals(0, PendingBeeCycles.read(tag, 4).total());
		PendingBeeCycles.save(tag, new int[]{4}, 1, 0); assertArrayEquals(new int[]{4, 0, 0}, PendingBeeCycles.read(tag, 3).counts());
		PendingBeeCycles.save(tag, new int[]{0}, 3, 0); assertFalse(tag.contains(PendingBeeCycles.KEY));
	}
	@Test void corruptOrUndersizedTargetsAreRejectedWithoutEditingSource() {
		var tag = new CompoundTag(); PendingBeeCycles.save(tag, new int[]{0, 8}, 0, 0); var before = tag.copy();
		assertThrows(IllegalArgumentException.class, () -> PendingBeeCycles.read(tag, 1)); assertEquals(before, tag);
		tag.getCompound(PendingBeeCycles.KEY).putIntArray("counts", new int[]{0, -1}); assertThrows(IllegalArgumentException.class, () -> PendingBeeCycles.read(tag, 2));
		tag.getCompound(PendingBeeCycles.KEY).remove("flushTicks"); assertThrows(IllegalArgumentException.class, () -> PendingBeeCycles.read(tag, 2));
		assertThrows(IllegalArgumentException.class, () -> PendingBeeCycles.save(new CompoundTag(), new int[]{-1}, 0, 0));
	}
}
