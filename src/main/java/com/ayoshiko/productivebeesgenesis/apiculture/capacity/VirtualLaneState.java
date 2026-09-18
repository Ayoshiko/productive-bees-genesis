package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** 仅做已获足额物料／能量预算的满载进度数学；不扣库存，不代表调度器已实现。 */
public record VirtualLaneState(UUID memberId, long capabilityRevision, int laneIndex,
		WorkCapacity capability, int progress) {
	public record Advancement(VirtualLaneState state, BigInteger completedOperations, BigInteger energyUsed) { }

	public VirtualLaneState {
		Objects.requireNonNull(memberId);
		Objects.requireNonNull(capability);
		if (capabilityRevision < 0 || laneIndex < 0 || progress < 0 || progress >= capability.cycleTicks()) {
			throw new IllegalArgumentException("Invalid lane state");
		}
	}

	public Advancement advanceFunded(MemberCapabilitySnapshot owner, long virtualTicks) {
		if (virtualTicks < 0) throw new IllegalArgumentException("Negative work budget");
		if (!owner.memberId().equals(memberId) || owner.revision() != capabilityRevision
				|| laneIndex >= owner.laneCount() || !owner.requireCapacity(capability.work()).equals(capability)) {
			throw new IllegalArgumentException("Lane requires its original capability revision");
		}
		if (!owner.online()) return new Advancement(this, BigInteger.ZERO, BigInteger.ZERO);
		BigInteger ticks = BigInteger.valueOf(virtualTicks);
		BigInteger[] cycle = ticks.add(BigInteger.valueOf(progress))
				.divideAndRemainder(BigInteger.valueOf(capability.cycleTicks()));
		return new Advancement(new VirtualLaneState(memberId, capabilityRevision, laneIndex, capability, cycle[1].intValueExact()),
				cycle[0].multiply(BigInteger.valueOf(capability.operationsPerCycle())),
				ticks.multiply(BigInteger.valueOf(capability.fullLaneEnergyPerTick())));
	}
}
