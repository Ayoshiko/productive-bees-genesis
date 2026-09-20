package com.ayoshiko.productivebeesgenesis.apiculture.production;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;

/** 基础蜂箱有限保管账户；D16 接统一能量账户前，FE 仅在这里持有一次。 */
public record BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees, FeedingSlotStore feeding) {
	public BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees) { this(member, revision, energy, energyCapacity, bees, null); }
	public BeeMemberState {
		Objects.requireNonNull(member); bees = List.copyOf(bees);
		if (revision < 0 || energy < 0 || energyCapacity < energy || bees.size() > 3) throw new IllegalArgumentException("Invalid basic apiary state");
		var slots = ConcurrentHashMap.newKeySet();
		var ids = ConcurrentHashMap.newKeySet();
		for (var bee : bees) if (!member.equals(bee.member()) || bee.slot() >= 3 || !slots.add(bee.slot()) || !ids.add(bee.id())) throw new IllegalArgumentException("Duplicate or foreign bee slot");
	}
	public BeeRecord bee(int slot) { return bees.stream().filter(bee -> bee.slot() == slot).findFirst().orElseThrow(); }
	public BeeMemberState update(BeeRecord next, long remainingEnergy) {
		var old = bee(next.slot());
		if (!old.id().equals(next.id()) || !old.originalSlot().equals(next.originalSlot()) || !old.plan().equals(next.plan())
				|| next.revision() != Math.incrementExact(old.revision()) || remainingEnergy < 0 || remainingEnergy > energy)
			throw new IllegalArgumentException("Invalid bee successor");
		return new BeeMemberState(member, Math.incrementExact(revision), remainingEnergy, energyCapacity,
				bees.stream().map(bee -> bee.slot() == next.slot() ? next : bee).toList(), feeding);
	}
	public BeeMemberState withFeeding(FeedingSlotStore next) {
		if (feeding == next) return this;
		if (feeding == null ? next == null || next.revision() != 0 : next == null) throw new IllegalArgumentException("Invalid feeding migration");
		if (feeding != null) feeding.validateSuccessor(next);
		return new BeeMemberState(member, Math.incrementExact(revision), energy, energyCapacity, bees, next);
	}
	public BeeMemberState moveBee(int from, int to, boolean withFeeding) {
		var moved = bee(from).relocate(to);
		if (to < 0 || to >= 3 || bees.stream().anyMatch(bee -> bee.slot() == to)) throw new IllegalArgumentException("Bee target occupied or out of range");
		var nextFeeding = withFeeding ? Objects.requireNonNull(feeding).moveWithBee(from, to) : feeding;
		return new BeeMemberState(member, Math.incrementExact(revision), energy, energyCapacity,
				bees.stream().map(bee -> bee.slot() == from ? moved : bee).sorted(java.util.Comparator.comparingInt(BeeRecord::slot)).toList(), nextFeeding);
	}
	public boolean drained() { return bees.stream().allMatch(BeeRecord::drained); }
	public void validateSuccessor(BeeMemberState next) {
		if (!member.equals(next.member) || next.revision != Math.incrementExact(revision) || next.energy > energy
				|| energyCapacity != next.energyCapacity || bees.size() != next.bees.size()) throw new IllegalArgumentException("Invalid member production successor");
		int changed = 0;
		for (var previous : bees) {
			var candidate = next.bees.stream().filter(bee -> bee.id().equals(previous.id())).findFirst().orElseThrow();
			if (previous.equals(candidate)) continue;
			if (!previous.originalSlot().equals(candidate.originalSlot()) || !previous.plan().equals(candidate.plan())
					|| candidate.revision() != Math.incrementExact(previous.revision()) || previous.slot() != candidate.slot() && !previous.drained()) throw new IllegalArgumentException("Bee plan changed within paid work");
			changed++;
		}
		if (feeding != next.feeding) {
			if (next.feeding == null || feeding == null && next.feeding.revision() != 0) throw new IllegalArgumentException("Feeding ownership cannot disappear");
			if (feeding != null) feeding.validateSuccessor(next.feeding);
		}
		if (changed > 1 || changed == 0 && (feeding == next.feeding || energy != next.energy)) throw new IllegalArgumentException("Invalid bee or feeding work segment");
	}
}
