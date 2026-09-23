package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 基础蜂箱保管状态；迁入共享供能后，本地 FE 恒为零。 */
public final class BeeMemberState {
	/** 只供当前权威会话的选择校验；恢复后重新签发，不写入存档或参与值相等。 */
	public static final class RosterVersion { private RosterVersion() { } }
	private final UUID member;
	private final long revision, energy, energyCapacity;
	private final List<BeeRecord> bees;
	private final FeedingSlotStore feeding;
	private final boolean networkPowered;
	private final RosterVersion rosterVersion;

	public BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees,
			FeedingSlotStore feeding, boolean networkPowered) {
		this(member, revision, energy, energyCapacity, bees, feeding, networkPowered, new RosterVersion());
	}
	public BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees, FeedingSlotStore feeding) {
		this(member, revision, energy, energyCapacity, bees, feeding, false);
	}
	public BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees) { this(member, revision, energy, energyCapacity, bees, null); }
	private BeeMemberState(UUID member, long revision, long energy, long energyCapacity, List<BeeRecord> bees,
			FeedingSlotStore feeding, boolean networkPowered, RosterVersion rosterVersion) {
		Objects.requireNonNull(member); bees = List.copyOf(bees);
		if (revision < 0 || energy < 0 || energyCapacity < energy || bees.size() > 3 || networkPowered && energy != 0) throw new IllegalArgumentException("Invalid basic apiary state");
		var slots = ConcurrentHashMap.newKeySet();
		var ids = ConcurrentHashMap.newKeySet();
		for (var bee : bees) if (!member.equals(bee.member()) || bee.slot() >= 3 || !slots.add(bee.slot()) || !ids.add(bee.id())) throw new IllegalArgumentException("Duplicate or foreign bee slot");
		this.member = member; this.revision = revision; this.energy = energy; this.energyCapacity = energyCapacity;
		this.bees = bees; this.feeding = feeding; this.networkPowered = networkPowered; this.rosterVersion = rosterVersion;
	}
	public UUID member() { return member; }
	public long revision() { return revision; }
	public long energy() { return energy; }
	public long energyCapacity() { return energyCapacity; }
	public List<BeeRecord> bees() { return bees; }
	public FeedingSlotStore feeding() { return feeding; }
	public boolean networkPowered() { return networkPowered; }
	/** 生产、喂食和供能保持不变，换蜂／移位或重新恢复会失效。 */
	public RosterVersion rosterVersion() { return rosterVersion; }
	public BeeRecord bee(int slot) { return bees.stream().filter(bee -> bee.slot() == slot).findFirst().orElseThrow(); }
	public BeeMemberState update(BeeRecord next, long remainingEnergy) {
		var old = bee(next.slot());
		if (!old.id().equals(next.id()) || !old.originalSlot().equals(next.originalSlot()) || !old.plan().equals(next.plan())
				|| next.revision() != Math.incrementExact(old.revision()) || remainingEnergy < 0 || remainingEnergy > energy)
			throw new IllegalArgumentException("Invalid bee successor");
		return new BeeMemberState(member, Math.incrementExact(revision), remainingEnergy, energyCapacity,
				bees.stream().map(bee -> bee.slot() == next.slot() ? next : bee).toList(), feeding, networkPowered, rosterVersion);
	}
	public BeeMemberState withFeeding(FeedingSlotStore next) {
		if (feeding == next) return this;
		if (feeding == null ? next == null || next.revision() != 0 : next == null) throw new IllegalArgumentException("Invalid feeding migration");
		if (feeding != null) feeding.validateSuccessor(next);
		return new BeeMemberState(member, Math.incrementExact(revision), energy, energyCapacity, bees, next, networkPowered, rosterVersion);
	}
	public BeeMemberState moveBee(int from, int to, boolean withFeeding) {
		var moved = bee(from).relocate(to);
		if (to < 0 || to >= 3 || bees.stream().anyMatch(bee -> bee.slot() == to)) throw new IllegalArgumentException("Bee target occupied or out of range");
		var nextFeeding = withFeeding ? Objects.requireNonNull(feeding).moveWithBee(from, to) : feeding;
		return new BeeMemberState(member, Math.incrementExact(revision), energy, energyCapacity,
				bees.stream().map(bee -> bee.slot() == from ? moved : bee).sorted(java.util.Comparator.comparingInt(BeeRecord::slot)).toList(), nextFeeding, networkPowered);
	}
	public BeeMemberState transferEnergy() {
		return networkPowered ? this : new BeeMemberState(member, Math.incrementExact(revision), 0, energyCapacity, bees, feeding, true, rosterVersion);
	}
	/** 喂食和移位可沿用通用所有权更新；推进／冻结产物必须走带付款证明的入口。 */
	public boolean sameProduction(BeeMemberState next) {
		if (bees.size() != next.bees.size()) return false;
		for (var bee : bees) {
			var other = next.bees.stream().filter(value -> value.id().equals(bee.id())).findFirst().orElse(null);
			if (other == null || !bee.plan().equals(other.plan()) || bee.progress() != other.progress()
					|| bee.pendingCycles() != other.pendingCycles() || !bee.frozen().equals(other.frozen())) return false;
		}
		return true;
	}
	public boolean drained() { return bees.stream().allMatch(BeeRecord::drained); }
	public void validateSuccessor(BeeMemberState next) {
		if (!member.equals(next.member) || next.revision != Math.incrementExact(revision) || next.energy > energy
				|| energyCapacity != next.energyCapacity || bees.size() != next.bees.size()) throw new IllegalArgumentException("Invalid member production successor");
		if (networkPowered != next.networkPowered) {
			if (networkPowered || !bees.equals(next.bees) || !Objects.equals(feeding, next.feeding) || next.energy != 0) throw new IllegalArgumentException("Invalid bee energy transfer");
			return;
		}
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
	@Override public boolean equals(Object other) {
		return this == other || other instanceof BeeMemberState state && revision == state.revision
				&& energy == state.energy && energyCapacity == state.energyCapacity && networkPowered == state.networkPowered
				&& member.equals(state.member) && bees.equals(state.bees) && Objects.equals(feeding, state.feeding);
	}
	@Override public int hashCode() { return Objects.hash(member, revision, energy, energyCapacity, bees, feeding, networkPowered); }
	@Override public String toString() { return "BeeMemberState[member=" + member + ", revision=" + revision + ", bees=" + bees.size() + "]"; }
}
