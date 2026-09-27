package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class BeeProgressSyncMinecraftTest {

	@Test
	void steadyProgressSendsOneSamplePerFiveRealTicksWithoutChangingProduction() {
		BeeSlot slot = slot();
		AtomicLong time = new AtomicLong();
		BeeProgressSyncState sync = new BeeProgressSyncState(slot, time::get);
		for (int tick = 0; tick < 20; tick++) {
			time.set(tick);
			progress(slot, tick);
			assertEquals(tick / 5 * 5 / 100.0f, sync.progress());
			assertEquals(tick / 5 * 5, sync.ticks());
			assertEquals(tick, slot.getTicksInHive());
		}
	}

	@Test
	void stateDurationNectarAndBeeChangesRefreshImmediately() {
		BeeSlot slot = slot();
		AtomicLong time = new AtomicLong(50);
		BeeProgressSyncState sync = new BeeProgressSyncState(slot, time::get);
		assertEquals(0, sync.ticks());
		progress(slot, 1);
		slot.setState(BeeState.WAITING_ENERGY);
		assertEquals(1, sync.ticks());
		progress(slot, 2);
		slot.setMinOccupationTicks(200);
		assertEquals(0.02f, sync.progress());
		progress(slot, 3);
		slot.setHasNectar(true);
		assertEquals(3, sync.ticks());
		progress(slot, 4);
		CompoundTag replacement = new CompoundTag();
		replacement.putString("type", "productivebees:iron");
		slot.setBeeData(replacement);
		assertEquals(4, sync.ticks());
		slot.clear();
		assertEquals(0, sync.ticks());
		assertEquals(0, sync.progress());
	}

	@Test
	void cycleWrapClockRollbackAndNewViewerUseFreshState() {
		BeeSlot slot = slot();
		AtomicLong time = new AtomicLong(100);
		BeeProgressSyncState sync = new BeeProgressSyncState(slot, time::get);
		progress(slot, 98);
		assertEquals(98, sync.ticks());
		progress(slot, 99);
		time.set(101);
		assertEquals(98, sync.ticks());
		assertEquals(99, new BeeProgressSyncState(slot, time::get).ticks());
		progress(slot, 1);
		time.set(102);
		assertEquals(1, sync.ticks());
		assertEquals(0.01f, sync.progress());
		progress(slot, 2);
		time.set(0);
		assertEquals(2, sync.ticks());
	}

	private static BeeSlot slot() {
		BeeSlot slot = new BeeSlot();
		slot.setBeeData(new CompoundTag());
		slot.setState(BeeState.WORKING);
		slot.setMinOccupationTicks(100);
		return slot;
	}

	private static void progress(BeeSlot slot, int ticks) {
		slot.setTicksInHive(ticks);
		slot.setProgress(ticks / 100.0f);
	}
}
