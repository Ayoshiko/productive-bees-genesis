package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 逐成员供能模式与占用 lane；共享模式不保留本地 FE，空闲 lane 不分配占位作业。 */
public record CentrifugeWorkState(UUID member, long revision, int laneCount, long energy,
		long energyCapacity, Map<Integer, CentrifugeJob> jobs, boolean networkPowered) {
	public CentrifugeWorkState(UUID member, long revision, int laneCount, long energy, long energyCapacity, Map<Integer, CentrifugeJob> jobs) {
		this(member, revision, laneCount, energy, energyCapacity, jobs, false);
	}
	public CentrifugeWorkState {
		Objects.requireNonNull(member); jobs = Map.copyOf(jobs);
		if (revision < 0 || laneCount < 1 || energy < 0 || energyCapacity < energy || networkPowered && energy != 0) throw new IllegalArgumentException("Invalid centrifuge state");
		var ids = ConcurrentHashMap.newKeySet();
		for (var entry : jobs.entrySet()) if (entry.getKey() < 0 || entry.getKey() >= laneCount || !ids.add(entry.getValue().id()))
			throw new IllegalArgumentException("Duplicate or out of range centrifuge lane");
	}
	public boolean drained() { return jobs.isEmpty(); }
	public CentrifugeWorkState transferEnergy() {
		if (networkPowered) return this;
		return new CentrifugeWorkState(member, Math.incrementExact(revision), laneCount, 0, energyCapacity, jobs, true);
	}
	public void validateSuccessor(CentrifugeWorkState next) {
		if (!member.equals(next.member) || next.revision != Math.incrementExact(revision) || laneCount != next.laneCount
				|| next.energy > energy || energyCapacity != next.energyCapacity) throw new IllegalArgumentException("Invalid centrifuge successor");
		if (networkPowered != next.networkPowered) {
			if (networkPowered || !jobs.equals(next.jobs) || next.energy != 0) throw new IllegalArgumentException("Invalid energy transfer");
			return;
		}
		var lanes = ConcurrentHashMap.<Integer>newKeySet(); lanes.addAll(jobs.keySet()); lanes.addAll(next.jobs.keySet());
		int changed = 0;
		long spent = 0;
		for (int lane : lanes) {
			var old = jobs.get(lane); var job = next.jobs.get(lane);
			if (Objects.equals(old, job)) continue;
			changed++;
			if (old == null) {
				if (job.progress() != 0 || job.sampled()) throw new IllegalArgumentException("New work must start unpaid");
			} else if (job == null) {
				if (old.progress() != 0 && !old.sampled()) throw new IllegalArgumentException("Unsettled centrifuge work disappeared");
			} else {
				if (!old.id().equals(job.id()) || !old.plan().equals(job.plan()) || old.operations() != job.operations()
						|| old.seed() != job.seed() || old.progress() > job.progress() || old.sampled())
					throw new IllegalArgumentException("Paid centrifuge plan changed");
				spent = Math.multiplyExact(job.progress() - old.progress(), old.plan().energyPerTick(old.operations()));
			}
		}
		if (changed != 1 || !networkPowered && energy - next.energy != spent) throw new IllegalArgumentException("Expected one exactly funded centrifuge lane transition");
	}
	CentrifugeWorkState replace(int lane, CentrifugeJob job, long remainingEnergy) {
		if (lane < 0 || lane >= laneCount || remainingEnergy < 0 || remainingEnergy > energy) throw new IllegalArgumentException("Invalid centrifuge successor");
		var next = new ConcurrentHashMap<>(jobs);
		if (job == null) next.remove(lane); else next.put(lane, job);
		return new CentrifugeWorkState(member, Math.incrementExact(revision), laneCount, remainingEnergy, energyCapacity, next, networkPowered);
	}
}
