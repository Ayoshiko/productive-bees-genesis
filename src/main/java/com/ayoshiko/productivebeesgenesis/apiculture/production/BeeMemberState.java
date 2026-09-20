package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 基础蜂箱有限保管账户；D16 接统一能量账户前，FE 仅在这里持有一次。 */
public record BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees) {
	public BeeMemberState {
		Objects.requireNonNull(member); bees = List.copyOf(bees);
		if (revision < 0 || energy < 0 || energyCapacity < energy || bees.size() > 3) throw new IllegalArgumentException("Invalid basic apiary state");
		var slots = ConcurrentHashMap.newKeySet();
		for (var bee : bees) if (!member.equals(bee.member()) || bee.slot() >= 3 || !slots.add(bee.slot())) throw new IllegalArgumentException("Duplicate or foreign bee slot");
	}
	public BeeRecord bee(int slot) { return bees.stream().filter(bee -> bee.slot() == slot).findFirst().orElseThrow(); }
	public BeeMemberState update(BeeRecord next, long remainingEnergy) {
		var old = bee(next.slot());
		if (!old.id().equals(next.id()) || !old.originalSlot().equals(next.originalSlot()) || !old.plan().equals(next.plan())
				|| next.revision() != Math.incrementExact(old.revision()) || remainingEnergy < 0 || remainingEnergy > energy)
			throw new IllegalArgumentException("Invalid bee successor");
		return new BeeMemberState(member, Math.incrementExact(revision), remainingEnergy, energyCapacity,
				bees.stream().map(bee -> bee.slot() == next.slot() ? next : bee).toList());
	}
	public boolean drained() { return bees.stream().allMatch(BeeRecord::drained); }
	public void validateSuccessor(BeeMemberState next) {
		if (!member.equals(next.member) || next.revision != Math.incrementExact(revision) || next.energy > energy
				|| energyCapacity != next.energyCapacity || bees.size() != next.bees.size()) throw new IllegalArgumentException("Invalid member production successor");
		int changed = 0;
		for (var previous : bees) {
			var candidate = next.bee(previous.slot());
			if (previous.equals(candidate)) continue;
			if (!previous.originalSlot().equals(candidate.originalSlot()) || !previous.plan().equals(candidate.plan())
					|| candidate.revision() != Math.incrementExact(previous.revision())) throw new IllegalArgumentException("Bee plan changed within paid work");
			changed++;
		}
		if (changed != 1) throw new IllegalArgumentException("A work segment must update exactly one bee");
	}
}
