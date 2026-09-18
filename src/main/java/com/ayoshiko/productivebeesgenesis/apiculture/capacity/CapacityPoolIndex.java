package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 按显式刷新批次构建不可变索引，不做全世界扫描，也不监听生产 tick。 */
public final class CapacityPoolIndex {
	public record Contribution(UUID memberId, long revision, int lanes) { }
	public record Pool(WorkCapacity capability, List<Contribution> contributions) {
		public Pool { contributions = List.copyOf(contributions); }
		public BigInteger lanes() {
			return contributions.stream().map(c -> BigInteger.valueOf(c.lanes())).reduce(BigInteger.ZERO, BigInteger::add);
		}
	}
	public record Summary(ExactRate operationsPerTick, BigInteger fullLoadEnergyPerTick, List<Pool> pools) {
		public Summary { pools = List.copyOf(pools); }
	}
	private final Map<WorkKey, Summary> work;
	private final BigInteger ownedBeeSlots;
	private final BigInteger onlineBeeSlots;

	public CapacityPoolIndex(Collection<MemberCapabilitySnapshot> members) {
		var identities = ConcurrentHashMap.<UUID>newKeySet();
		Map<WorkCapacity, List<Contribution>> groups = new ConcurrentHashMap<>();
		BigInteger owned = BigInteger.ZERO;
		BigInteger online = BigInteger.ZERO;
		for (MemberCapabilitySnapshot member : members) {
			if (!identities.add(member.memberId())) throw new IllegalArgumentException("Duplicate member identity");
			owned = owned.add(BigInteger.valueOf(member.beeSlots()));
			if (!member.online()) continue;
			online = online.add(BigInteger.valueOf(member.beeSlots()));
			for (WorkCapacity capacity : member.alternatives()) {
				groups.computeIfAbsent(capacity, ignored -> new ArrayList<>()).add(
						new Contribution(member.memberId(), member.revision(), member.laneCount()));
			}
		}
		Map<WorkKey, List<Pool>> byWork = new ConcurrentHashMap<>();
		groups.forEach((capacity, sources) -> byWork.computeIfAbsent(capacity.work(), ignored -> new ArrayList<>())
				.add(new Pool(capacity, sources)));
		Map<WorkKey, Summary> summaries = new ConcurrentHashMap<>();
		byWork.forEach((key, pools) -> {
			ExactRate rate = ExactRate.ZERO;
			BigInteger energy = BigInteger.ZERO;
			for (Pool pool : pools) {
				WorkCapacity capacity = pool.capability();
				BigInteger lanes = pool.lanes();
				rate = rate.add(new ExactRate(lanes.multiply(BigInteger.valueOf(capacity.operationsPerCycle())),
						BigInteger.valueOf(capacity.cycleTicks())));
				energy = energy.add(lanes.multiply(BigInteger.valueOf(capacity.fullLaneEnergyPerTick())));
			}
			summaries.put(key, new Summary(rate, energy, pools));
		});
		work = Map.copyOf(summaries);
		ownedBeeSlots = owned;
		onlineBeeSlots = online;
	}

	public Summary forWork(WorkKey key) {
		return work.getOrDefault(key, new Summary(ExactRate.ZERO, BigInteger.ZERO, List.of()));
	}
	public BigInteger ownedBeeSlots() { return ownedBeeSlots; }
	public BigInteger ownedFeedingSlots() { return ownedBeeSlots; }
	public BigInteger onlineBeeSlots() { return onlineBeeSlots; }
	public BigInteger onlineFeedingSlots() { return onlineBeeSlots; }
}
