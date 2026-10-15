package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapacityPoolIndexTest {
	@Test void incrementalQueryKeepsHeterogeneousContributionsAndRejectsDuplicateMachine() {
		var query = new CapacityPoolIndex.QueryBuilder(IRON);
		var first = UUID.randomUUID(); var second = UUID.randomUUID();
		query.add(capacity(IRON, 4, 10, 400), first, 1, 3);
		query.add(capacity(IRON, 2, 20, 200), second, 2, 5);
		assertThrows(IllegalArgumentException.class, () -> query.add(capacity(IRON, 1, 1, 1), first, 3, 3));
		assertNull(query.finishStep(1));
		assertThrows(IllegalArgumentException.class, () -> query.add(capacity(IRON, 1, 1, 1), UUID.randomUUID(), 3, 1));
		var result = query.finishStep(1);
		assertEquals(ExactRate.of(17, 10), result.operationsPerTick());
		assertEquals(BigInteger.valueOf(2200), result.fullLoadEnergyPerTick());
		assertEquals(BigInteger.valueOf(8), result.pools().stream().map(CapacityPoolIndex.Pool::lanes).reduce(BigInteger.ZERO, BigInteger::add));
		assertSame(result, query.finishStep(1));
		assertThrows(UnsupportedOperationException.class, () -> result.pools().clear());
	}
	static final WorkKey IRON = new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:iron", 1, "plain");
	static final WorkKey COPPER = new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:copper", 1, "plain");

	static WorkCapacity capacity(WorkKey work, int operations, int ticks, long energy) {
		return new WorkCapacity(work, operations, ticks, energy / operations, energy, 1, 0, Map.of());
	}

	static MemberCapabilitySnapshot member(UUID id, long revision, int lanes, int bees,
			MemberCapabilitySnapshot.Availability state, WorkCapacity... work) {
		return new MemberCapabilitySnapshot(id, revision, "test:machine",
				new MemberCapabilitySnapshot.Origin("test:dimension", 0, 80, 0), state, bees, lanes, List.of(work));
	}

	@Test
	void heterogeneousMachinesProduceSeventeenTenthsWithoutCrossMultiplyingUpgrades() {
		var a = member(UUID.randomUUID(), 1, 3, 0, MemberCapabilitySnapshot.Availability.ONLINE, capacity(IRON, 4, 10, 400));
		var b = member(UUID.randomUUID(), 1, 5, 0, MemberCapabilitySnapshot.Availability.ONLINE, capacity(IRON, 2, 20, 200));
		var summary = new CapacityPoolIndex(List.of(a, b)).forWork(IRON);
		assertEquals(ExactRate.of(17, 10), summary.operationsPerTick());
		assertEquals(BigInteger.valueOf(2200), summary.fullLoadEnergyPerTick());
		assertEquals(2, summary.pools().size());
	}

	@Test
	void offlineBeeSlotsRetainOwnershipAndOneFeedingSlotEach() {
		var beeWork = new WorkKey(WorkKey.Kind.BEE_CYCLE, "test:bee", 1, "day");
		var a = member(UUID.randomUUID(), 1, 3, 3, MemberCapabilitySnapshot.Availability.ONLINE, capacity(beeWork, 1, 1200, 50));
		var b = member(UUID.randomUUID(), 1, 20, 20, MemberCapabilitySnapshot.Availability.OFFLINE, capacity(beeWork, 1, 75, 100));
		var index = new CapacityPoolIndex(List.of(a, b));
		assertEquals(BigInteger.valueOf(23), index.ownedBeeSlots());
		assertEquals(index.ownedBeeSlots(), index.ownedFeedingSlots());
		assertEquals(BigInteger.valueOf(3), index.onlineFeedingSlots());
		assertEquals(ExactRate.of(1, 400), index.forWork(beeWork).operationsPerTick());
	}

	@Test
	void recipeAlternativesNeverCreateAdditionalPhysicalLanes() {
		var a = member(UUID.randomUUID(), 1, 3, 0, MemberCapabilitySnapshot.Availability.ONLINE,
				capacity(IRON, 1, 10, 100), capacity(COPPER, 1, 20, 100));
		var b = member(UUID.randomUUID(), 1, 5, 0, MemberCapabilitySnapshot.Availability.REDSTONE_PAUSED, capacity(IRON, 9, 1, 900));
		var index = new CapacityPoolIndex(List.of(a, b));
		assertEquals(ExactRate.of(3, 10), index.forWork(IRON).operationsPerTick());
		assertEquals(ExactRate.of(3, 20), index.forWork(COPPER).operationsPerTick());
		assertEquals(3, a.laneCount());
		assertEquals(ExactRate.ZERO, index.forWork(new WorkKey(IRON.kind(), IRON.id(), 2, IRON.contextKey())).operationsPerTick());
	}

	@Test
	void immutableCopiesRejectDuplicateIdentitiesAndKeepPoolSemanticsSeparate() {
		var mutable = new ArrayList<WorkCapacity>();
		mutable.add(capacity(IRON, 17, 10, 1700));
		var id = UUID.randomUUID();
		var a = new MemberCapabilitySnapshot(id, 1, "test:m", new MemberCapabilitySnapshot.Origin("test:d", 0, 0, 0),
				MemberCapabilitySnapshot.Availability.ONLINE, 0, 2, mutable);
		mutable.clear();
		assertEquals(1, a.alternatives().size());
		assertThrows(IllegalArgumentException.class, () -> new CapacityPoolIndex(List.of(a, a)));
		assertEquals(BigInteger.valueOf(3400), new CapacityPoolIndex(List.of(a)).forWork(IRON).fullLoadEnergyPerTick());
		var modifiedOutput = new WorkCapacity(IRON, 17, 10, 100, 1700, 2, 0.3, Map.of("pb:STABILITY", 1));
		var b = member(UUID.randomUUID(), 1, 1, 0, MemberCapabilitySnapshot.Availability.ONLINE, modifiedOutput);
		assertEquals(2, new CapacityPoolIndex(List.of(a, b)).forWork(IRON).pools().size());
	}

	@Test
	void compositeMemberKeepsBeeAndCentrifugeCapacityIndependent() {
		var beeWork = new WorkKey(WorkKey.Kind.BEE_CYCLE, "test:bee", 1, "day");
		var bee = capacity(beeWork, 2, 10, 8);
		var centrifuge = capacity(IRON, 4, 20, 12);
		var builder = new MemberCapabilitySnapshot.AlternativesBuilder();
		builder.add(bee); builder.add(centrifuge);
		var frozen = builder.finish();
		builder.add(capacity(COPPER, 3, 30, 6));
		var member = new MemberCapabilitySnapshot(UUID.randomUUID(), 1, "test:combined",
				new MemberCapabilitySnapshot.Origin("test:dimension", 0, 80, 0),
				MemberCapabilitySnapshot.Availability.ONLINE, 6, 9, frozen);
		var index = new CapacityPoolIndex(List.of(member));
		assertEquals(ExactRate.of(6, 5), index.forWork(beeWork).operationsPerTick());
		assertEquals(ExactRate.of(3, 5), index.forWork(IRON).operationsPerTick());
		assertEquals(BigInteger.valueOf(48), index.forWork(beeWork).fullLoadEnergyPerTick());
		assertEquals(BigInteger.valueOf(36), index.forWork(IRON).fullLoadEnergyPerTick());
		assertEquals(BigInteger.valueOf(6), index.ownedFeedingSlots());
		assertEquals(ExactRate.ZERO, index.forWork(COPPER).operationsPerTick());
		assertEquals(2, member.alternatives().size());
		// 总索引 0 属于蜂位，6 属于离心局部 lane 0，不能跨种类借位。
		assertThrows(IllegalArgumentException.class, () -> new VirtualLaneState(member.memberId(), 1, 0, centrifuge, 0).advanceFunded(member, 20));
		assertThrows(IllegalArgumentException.class, () -> new VirtualLaneState(member.memberId(), 1, 6, bee, 0).advanceFunded(member, 10));
		var result = new VirtualLaneState(member.memberId(), 1, 6, centrifuge, 0).advanceFunded(member, 20);
		assertEquals(BigInteger.valueOf(4), result.completedOperations());
		assertEquals(BigInteger.valueOf(240), result.energyUsed());
	}

	@Test
	void layoutAddressesBothKindsWithoutOverflowOrCrossKindBorrowing() {
		var layout = new MemberWorkLayout(6, 3);
		for (int i = 0; i < 9; i++) assertEquals(i, layout.ordinal(layout.slot(i)));
		assertEquals(new MemberWorkLayout.Slot(WorkKey.Kind.BEE_CYCLE, 0), layout.slot(0));
		assertEquals(new MemberWorkLayout.Slot(WorkKey.Kind.CENTRIFUGE_RECIPE, 0), layout.slot(6));
		assertFalse(layout.contains(new MemberWorkLayout.Slot(WorkKey.Kind.CENTRIFUGE_RECIPE, 3)));
		assertThrows(IllegalArgumentException.class, () -> layout.slot(9));
		assertThrows(IllegalArgumentException.class, () -> MemberWorkLayout.fromTotal(6, 5));
		assertThrows(IllegalArgumentException.class, () -> new MemberWorkLayout(Integer.MAX_VALUE, 1));
		var bee = capacity(new WorkKey(WorkKey.Kind.BEE_CYCLE, "test:bee", 1, "day"), 1, 10, 1);
		assertThrows(IllegalArgumentException.class, () -> member(UUID.randomUUID(), 1, 3, 3,
				MemberCapabilitySnapshot.Availability.ONLINE, capacity(IRON, 1, 10, 1)));
		assertThrows(IllegalArgumentException.class, () -> member(UUID.randomUUID(), 1, 3, 0,
				MemberCapabilitySnapshot.Availability.ONLINE, bee));
	}

	@Test
	void aggregateEnergyAndRateDoNotOverflowLong() {
		var a = member(UUID.randomUUID(), 1, Integer.MAX_VALUE, 0, MemberCapabilitySnapshot.Availability.ONLINE,
				capacity(IRON, Integer.MAX_VALUE, 1, Long.MAX_VALUE));
		var b = member(UUID.randomUUID(), 1, Integer.MAX_VALUE, 0, MemberCapabilitySnapshot.Availability.ONLINE,
				capacity(IRON, Integer.MAX_VALUE, 1, Long.MAX_VALUE));
		var summary = new CapacityPoolIndex(List.of(a, b)).forWork(IRON);
		assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(Integer.MAX_VALUE)).multiply(BigInteger.TWO), summary.fullLoadEnergyPerTick());
		assertEquals(new ExactRate(BigInteger.valueOf(Integer.MAX_VALUE).pow(2).multiply(BigInteger.TWO), BigInteger.ONE), summary.operationsPerTick());
	}
}
