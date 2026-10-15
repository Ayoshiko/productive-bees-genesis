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
		public Pool { contributions = contributions instanceof FrozenList<?> ? contributions : List.copyOf(contributions); }
		public BigInteger lanes() {
			return contributions.stream().map(c -> BigInteger.valueOf(c.lanes())).reduce(BigInteger.ZERO, BigInteger::add);
		}
	}
	public record Summary(ExactRate operationsPerTick, BigInteger fullLoadEnergyPerTick, List<Pool> pools) {
		public Summary { pools = pools instanceof FrozenList<?> ? pools : List.copyOf(pools); }
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
						new Contribution(member.memberId(), member.revision(), member.layout().lanes(capacity.work().kind())));
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

	/** 一次只查询同一配方；采集和汇总分别领预算，冻结阶段不再复制全部来源。 */
	public static final class QueryBuilder {
		private final WorkKey key;
		private final java.util.Set<UUID> members = new java.util.HashSet<>();
		private final Map<WorkCapacity, com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<Integer, Contribution>> groups = new java.util.LinkedHashMap<>();
		private final com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<Integer, Pool> pools =
				new com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<>(Integer::compare);
		private java.util.Iterator<Map.Entry<WorkCapacity, com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<Integer, Contribution>>> cursor;
		private ExactRate rate = ExactRate.ZERO;
		private BigInteger energy = BigInteger.ZERO;
		private Summary result;
		public QueryBuilder(WorkKey key) { this.key = java.util.Objects.requireNonNull(key); }
		public void add(WorkCapacity capacity, UUID member, long revision, int lanes) {
			if (cursor != null || !key.equals(capacity.work()) || lanes < 1 || revision < 0 || member == null || !members.add(member)) throw new IllegalArgumentException("Invalid capacity contribution");
			var sources = groups.computeIfAbsent(capacity, ignored -> new com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords<>(Integer::compare));
			sources.put(sources.size(), new Contribution(member, revision, lanes));
			var count = BigInteger.valueOf(lanes);
			rate = rate.add(new ExactRate(count.multiply(BigInteger.valueOf(capacity.operationsPerCycle())), BigInteger.valueOf(capacity.cycleTicks())));
			energy = energy.add(count.multiply(BigInteger.valueOf(capacity.fullLaneEnergyPerTick())));
		}
		public Summary finishStep(int budget) {
			if (budget < 1 || budget > 128) throw new IllegalArgumentException("Invalid capacity budget");
			if (result != null) return result;
			if (cursor == null) cursor = groups.entrySet().iterator();
			for (int i = 0; i < budget && cursor.hasNext(); i++) {
				var group = cursor.next();
				pools.put(pools.size(), new Pool(group.getKey(), new FrozenList<>(group.getValue().valuesSnapshot())));
			}
			if (cursor.hasNext()) return null;
			result = new Summary(rate, energy, new FrozenList<>(pools.valuesSnapshot())); groups.clear(); members.clear(); return result;
		}
	}
	private static final class FrozenList<T> extends java.util.AbstractList<T> {
		private final List<T> values;
		FrozenList(List<T> values) { this.values = values; }
		@Override public T get(int index) { return values.get(index); }
		@Override public int size() { return values.size(); }
		@Override public java.util.Iterator<T> iterator() { return values.iterator(); }
	}
	public Summary forWork(WorkKey key) {
		return work.getOrDefault(key, new Summary(ExactRate.ZERO, BigInteger.ZERO, List.of()));
	}
	public BigInteger ownedBeeSlots() { return ownedBeeSlots; }
	public BigInteger ownedFeedingSlots() { return ownedBeeSlots; }
	public BigInteger onlineBeeSlots() { return onlineBeeSlots; }
	public BigInteger onlineFeedingSlots() { return onlineBeeSlots; }
}
