package com.ayoshiko.productivebeesgenesis.apiculture.feeding;

import java.util.*;
import java.util.function.Predicate;

/** 一蜂位一有限槽的不可变根；计划不持有外部库存，也不在模拟时修改权威内容。 */
public record FeedingSlotStore(long revision, int legacySlots, String sourceFingerprint, List<Slot> slots) {
	public record Slot(FeedingItem item, int count, boolean disabled, int group) {
		public Slot {
			if (group < 0 || count < 0 || (item == null ? count != 0 || disabled : count == 0 || count > item.limit())) throw new IllegalArgumentException("Invalid feeding slot");
		}
		public boolean active() { return item != null && !disabled; }
		Slot amount(int value) { return value == 0 ? new Slot(null, 0, false, group) : new Slot(item, value, disabled, group); }
	}
	public record Demand(int beeSlot, FeedingItem item, int count) {
		public Demand { Objects.requireNonNull(item); if (beeSlot < 0 || count < 1) throw new IllegalArgumentException("Invalid feeding demand"); }
	}
	/** 未提交计划仅属于创建它的不可变根；重启／槽位变更后必须重新规划。 */
	public static final class Plan {
		private final FeedingSlotStore before, after;
		private final int moved;
		private Plan(FeedingSlotStore before, FeedingSlotStore after, int moved) { this.before = before; this.after = after; this.moved = moved; }
		public int moved() { return moved; }
		public FeedingSlotStore apply(FeedingSlotStore current) {
			if (current != before) throw new IllegalArgumentException("Stale feeding plan"); return after;
		}
	}
	public FeedingSlotStore {
		slots = List.copyOf(slots);
		if (revision < 0 || slots.size() != 3 || legacySlots != 9 || sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Unverified feeding layout");
		for (int i = 0; i < slots.size(); i++) {
			int group = slots.get(i).group();
			if (group > i || slots.get(group).group() != group) throw new IllegalArgumentException("Noncanonical feeding group");
		}
	}
	public boolean matches(int beeSlot, Predicate<FeedingItem> matcher) {
		int group = slots.get(beeSlot).group();
		for (var slot : slots) if (slot.group() == group && slot.active() && matcher.test(slot.item())) return true;
		return false;
	}
	public Plan groups(List<Integer> groups) {
		if (groups.size() != slots.size()) throw new IllegalArgumentException("Feeding group layout differs from bee slots");
		var changed = new ArrayList<Slot>();
		for (int i = 0; i < slots.size(); i++) { var slot = slots.get(i); changed.add(new Slot(slot.item(), slot.count(), slot.disabled(), groups.get(i))); }
		return plan(changed, 0);
	}
	public Plan disabled(int index, boolean disabled) {
		var slot = slots.get(index); var changed = new ArrayList<>(slots);
		changed.set(index, new Slot(slot.item(), slot.count(), slot.item() != null && disabled, slot.group())); return plan(changed, 0);
	}
	/** 内部补料按实际可放量移动；不同禁用状态不能混合，余量仍在来源槽。 */
	public Plan refill(int from, int to, int requested) {
		if (requested < 1 || from == to) throw new IllegalArgumentException("Invalid feeding transfer");
		var source = slots.get(from); var target = slots.get(to); var changed = new ArrayList<>(slots);
		if (source.item() == null || target.item() != null && (!source.item().equals(target.item()) || source.disabled() != target.disabled())) return plan(changed, 0);
		int accepted = Math.min(requested, Math.min(source.count(), source.item().limit() - target.count()));
		if (accepted > 0) {
			changed.set(from, source.amount(source.count() - accepted));
			changed.set(to, new Slot(source.item(), target.count() + accepted, source.disabled(), target.group()));
		}
		return plan(changed, accepted);
	}
	/** 外部供给计划仅增加实际容纳量；调用方须在同一提交中扣减真实来源。 */
	public Plan deposit(int index, FeedingItem item, int offered) {
		Objects.requireNonNull(item);
		if (offered < 1) throw new IllegalArgumentException("Invalid feeding offer");
		var target = slots.get(index); var changed = new ArrayList<>(slots);
		if (target.item() != null && !target.item().equals(item)) return plan(changed, 0);
		int accepted = Math.min(offered, item.limit() - target.count());
		if (accepted > 0) changed.set(index, new Slot(item, target.count() + accepted, target.disabled(), target.group()));
		return plan(changed, accepted);
	}
	/** 手动取回只触及选中实物槽，不从共享组的其它槽代扣。 */
	public Plan withdraw(int index, int requested) {
		if (requested < 1) throw new IllegalArgumentException("Invalid feeding request");
		var source = slots.get(index); int taken = Math.min(requested, source.count());
		var changed = new ArrayList<>(slots); changed.set(index, source.amount(source.count() - taken));
		return plan(changed, taken);
	}
	/** 同批请求共用一份扣减候选，不会让两个蜂位同时预约最后一份食物。 */
	public Optional<Plan> consume(List<Demand> demands) {
		var changed = new ArrayList<>(slots); int consumed = 0;
		for (var demand : demands) {
			int group = slots.get(demand.beeSlot()).group(), remaining = demand.count();
			for (int i = 0; i < changed.size() && remaining > 0; i++) {
				var slot = changed.get(i);
				if (slot.group() != group || !slot.active() || !demand.item().equals(slot.item())) continue;
				int count = Math.min(remaining, slot.count()); remaining -= count; consumed = Math.addExact(consumed, count); changed.set(i, slot.amount(slot.count() - count));
			}
			if (remaining != 0) return Optional.empty();
		}
		return Optional.of(plan(changed, consumed));
	}
	public FeedingSlotStore moveWithBee(int from, int to) {
		if (slots.get(to).item() != null || shared(from) || shared(to)) throw new IllegalArgumentException("Move feeding only into an empty ungrouped slot");
		return slots.get(from).item() == null ? this : refill(from, to, slots.get(from).count()).apply(this);
	}
	private boolean shared(int index) { int group = slots.get(index).group(); return slots.stream().filter(slot -> slot.group() == group).count() > 1; }
	private Plan plan(List<Slot> changed, int moved) {
		return new Plan(this, slots.equals(changed) ? this : new FeedingSlotStore(Math.incrementExact(revision), legacySlots, sourceFingerprint, changed), moved);
	}
	public void validateSuccessor(FeedingSlotStore next) {
		if (this == next) return;
		if (next == null || next.revision != Math.incrementExact(revision) || next.legacySlots != legacySlots || !next.sourceFingerprint.equals(sourceFingerprint)) throw new IllegalArgumentException("Invalid feeding successor");
	}
}
