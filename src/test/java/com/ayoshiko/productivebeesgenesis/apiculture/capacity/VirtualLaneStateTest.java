package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.math.BigInteger;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.capacity.CapacityPoolIndexTest.*;

class VirtualLaneStateTest {
	@Test
	void fundedBatchesMatchIndividualTicksAndRetainIntegerRemainders() {
		Random random = new Random(0xD03BEE);
		for (int scenario = 0; scenario < 500; scenario++) {
			int period = 1 + random.nextInt(1200);
			var capability = capacity(IRON, 1 + random.nextInt(100), period, random.nextInt(1000));
			var owner = member(UUID.randomUUID(), 3, 1, 0, MemberCapabilitySnapshot.Availability.ONLINE, capability);
			int progress = random.nextInt(period);
			var batch = new VirtualLaneState(owner.memberId(), 3, 0, capability, progress);
			int ticks = random.nextInt(4096);
			long completed = 0;
			for (int tick = 0; tick < ticks; tick++) {
				if (++progress == period) {
					progress = 0;
					completed += capability.operationsPerCycle();
				}
			}
			var result = batch.advanceFunded(owner, ticks);
			assertEquals(BigInteger.valueOf(completed), result.completedOperations());
			assertEquals(progress, result.state().progress());
			assertEquals(BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(capability.fullLaneEnergyPerTick())), result.energyUsed());
		}
	}

	@Test
	void upgradesCannotSilentlyRepriceInflightWorkAndOfflineDoesNotCatchUp() {
		var work = capacity(IRON, 4, 10, 400);
		var id = UUID.randomUUID();
		var state = new VirtualLaneState(id, 1, 0, work, 9);
		var upgraded = member(id, 2, 1, 0, MemberCapabilitySnapshot.Availability.ONLINE, capacity(IRON, 8, 2, 800));
		assertThrows(IllegalArgumentException.class, () -> state.advanceFunded(upgraded, 1));
		var offline = member(id, 1, 1, 0, MemberCapabilitySnapshot.Availability.OFFLINE, work);
		assertEquals(state, state.advanceFunded(offline, 1000).state());
		assertEquals(BigInteger.ZERO, state.advanceFunded(offline, 1000).completedOperations());
		var online = member(id, 1, 1, 0, MemberCapabilitySnapshot.Availability.ONLINE, work);
		assertEquals(BigInteger.valueOf(4), state.advanceFunded(online, 1).completedOperations());
	}

	@Test
	void hugeBudgetRemainsExactWithoutLoopingPerTick() {
		var work = capacity(IRON, Integer.MAX_VALUE, 1, Long.MAX_VALUE);
		var owner = member(UUID.randomUUID(), 1, 1, 0, MemberCapabilitySnapshot.Availability.ONLINE, work);
		var state = new VirtualLaneState(owner.memberId(), 1, 0, work, 0);
		var result = state.advanceFunded(owner, Long.MAX_VALUE);
		assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(Integer.MAX_VALUE)), result.completedOperations());
		assertEquals(BigInteger.valueOf(Long.MAX_VALUE).pow(2), result.energyUsed());
		assertThrows(IllegalArgumentException.class, () -> state.advanceFunded(owner, -1));
	}
}
