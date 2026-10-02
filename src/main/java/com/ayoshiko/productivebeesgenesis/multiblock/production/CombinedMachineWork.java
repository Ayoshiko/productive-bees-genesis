package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** 单台一体机的复合工作根；不使用网络账本或两个伪成员，不读取世界。 */
public final class CombinedMachineWork {
	public static final class Change {
		private final CombinedMachineWork before, after;
		private final long moved;
		private Change(CombinedMachineWork before, CombinedMachineWork after, long moved) { this.before = before; this.after = after; this.moved = moved; }
		public long moved() { return moved; }
		public boolean changed() { return before != after; }
		public CombinedMachineWork apply(CombinedMachineWork current) {
			if (current != before) throw new IllegalArgumentException("Stale combined machine work");
			return after;
		}
	}
	private final UUID machine;
	private final long generation, revision, energy, energyCapacity;
	private final int beeSlots, lanes;
	private final List<BeeRecord> bees;
	private final Map<Integer, CentrifugeDelivery> centrifuges;
	private final FiniteProductBuffer buffer;
	public CombinedMachineWork(UUID machine, long generation, long revision, int beeSlots, int lanes,
			long energy, long energyCapacity, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> centrifuges, FiniteProductBuffer buffer) {
		this.machine = Objects.requireNonNull(machine); this.bees = List.copyOf(bees); this.centrifuges = Map.copyOf(centrifuges);
		this.buffer = Objects.requireNonNull(buffer);
		if (generation < 1 || revision < 0 || beeSlots < 1 || lanes < 1 || energy < 0 || energyCapacity < energy)
			throw new IllegalArgumentException("Invalid combined machine capacity or identity");
		var ids = new HashSet<UUID>(); var slots = new HashSet<Integer>();
		for (var bee : bees) if (!machine.equals(bee.member()) || bee.slot() >= beeSlots || !ids.add(bee.id()) || !slots.add(bee.slot()))
			throw new IllegalArgumentException("Duplicate or foreign combined bee");
		for (var entry : centrifuges.entrySet()) if (entry.getKey() < 0 || entry.getKey() >= lanes || !ids.add(entry.getValue().job().id()))
			throw new IllegalArgumentException("Duplicate or out-of-range combined job");
		this.generation = generation; this.revision = revision; this.beeSlots = beeSlots; this.lanes = lanes;
		this.energy = energy; this.energyCapacity = energyCapacity;
	}
	public UUID machine() { return machine; }
	public long generation() { return generation; }
	public long revision() { return revision; }
	public long energy() { return energy; }
	public long energyCapacity() { return energyCapacity; }
	public int beeSlots() { return beeSlots; }
	public int lanes() { return lanes; }
	public List<BeeRecord> bees() { return bees; }
	public Map<Integer, CentrifugeDelivery> centrifuges() { return centrifuges; }
	public FiniteProductBuffer buffer() { return buffer; }
	public BeeRecord bee(int slot) { return bees.stream().filter(bee -> bee.slot() == slot).findFirst().orElseThrow(); }
	public Change receiveEnergy(long offered) {
		if (offered < 0) throw new IllegalArgumentException("Negative energy offer");
		long accepted = Math.min(offered, energyCapacity - energy);
		return accepted == 0 ? unchanged() : change(energy + accepted, bees, centrifuges, buffer, accepted);
	}
	public Change insert(ProductKey key, long offered, int stackLimit) {
		var transfer = buffer.insert(key, offered, stackLimit);
		return transfer.moved() == 0 ? unchanged() : change(energy, bees, centrifuges, transfer.buffer(), transfer.moved());
	}
	public Change extract(ProductKey key, long requested) {
		var transfer = buffer.extract(key, requested);
		return transfer.moved() == 0 ? unchanged() : change(energy, bees, centrifuges, transfer.buffer(), transfer.moved());
	}
	public Change advanceBee(int slot, long expectedBeeRevision, BeeWorkExecutor.Context context, int ticks, int samplingBudget, BeeWorkExecutor.Cycle nextCycle) {
		var current = bee(slot);
		// 已付费周期只能沿用原能力到周期边界；下一次调用才切入新计划。
		if (current.progress() != 0 && ticks > 0) { ticks = Math.min(ticks, current.plan().cycleTicks() - current.progress() % current.plan().cycleTicks()); nextCycle = null; }
		var result = BeeWorkExecutor.advanceBee(current, expectedBeeRevision, context, ticks, samplingBudget, energy, nextCycle);
		if (result.status() != BeeWorkExecutor.Status.READY) return unchanged();
		return change(energy - result.energyUsed(), replaceBee(result.candidate()), centrifuges, buffer, 0);
	}
	public Change settleBee(int slot, int stackLimit) {
		var current = bee(slot); if (current.frozen().isZero()) return unchanged();
		var transfer = buffer.insert(current.plan().output(), current.frozen().longSaturated(), stackLimit);
		if (transfer.moved() == 0) return unchanged();
		var next = current.work(current.progress(), current.pendingCycles(), current.frozen().subtract(ProductAmount.of(transfer.moved())));
		return change(energy, replaceBee(next), centrifuges, transfer.buffer(), transfer.moved());
	}
	public Change assignCentrifuge(int lane, CentrifugeRecipePlan plan, int requested, UUID jobId, long seed) {
		checkLane(lane); Objects.requireNonNull(plan); Objects.requireNonNull(jobId);
		if (requested < 1) throw new IllegalArgumentException("Invalid operation request");
		if (centrifuges.containsKey(lane)) return unchanged();
		int operations = (int) Math.min(Math.min(requested, plan.maxParallel()), buffer.count(plan.input()));
		operations = CentrifugeEnergyPricing.affordableOperations(plan.unitEnergyPerTick(), operations, energy);
		if (operations == 0) return unchanged();
		var transfer = buffer.extract(plan.input(), operations); var next = new HashMap<>(centrifuges);
		next.put(lane, new CentrifugeDelivery(new CentrifugeJob(jobId, plan, operations, 0, seed, null), Map.of()));
		return change(energy, bees, next, transfer.buffer(), 0);
	}
	public Change advanceCentrifuge(int lane, int ticks, boolean ready) {
		checkLane(lane); if (ticks < 0) throw new IllegalArgumentException("Negative work ticks");
		var current = centrifuges.get(lane); if (!ready || current == null) return unchanged();
		var result = current.job().advance(ticks, energy); if (result.executedTicks() == 0) return unchanged();
		var next = new HashMap<>(centrifuges); next.put(lane, new CentrifugeDelivery(result.job(), current.delivered()));
		return change(energy - result.energyUsed(), bees, next, buffer, 0);
	}
	public Change freezeCentrifuge(int lane) {
		checkLane(lane); var current = centrifuges.get(lane);
		if (current == null || !current.job().paid() || current.job().sampled()) return unchanged();
		var next = new HashMap<>(centrifuges); next.put(lane, new CentrifugeDelivery(current.job().freeze(), Map.of()));
		return change(energy, bees, next, buffer, 0);
	}
	public Change settleCentrifuge(int lane, ToIntFunction<ProductKey> itemLimits) {
		checkLane(lane); var current = centrifuges.get(lane); if (current == null) return unchanged();
		var result = current.deliver(buffer, itemLimits);
		if (result.moved() == 0 && !result.work().complete()) return unchanged();
		var next = new HashMap<>(centrifuges);
		if (result.work().complete()) next.remove(lane); else next.put(lane, result.work());
		return change(energy, bees, next, result.buffer(), result.moved());
	}
	private List<BeeRecord> replaceBee(BeeRecord next) { return bees.stream().map(bee -> bee.slot() == next.slot() ? next : bee).toList(); }
	private void checkLane(int lane) { if (lane < 0 || lane >= lanes) throw new IllegalArgumentException("Invalid centrifuge lane"); }
	private Change unchanged() { return new Change(this, this, 0); }
	private Change change(long energy, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> jobs, FiniteProductBuffer buffer, long moved) {
		return new Change(this, new CombinedMachineWork(machine, generation, Math.incrementExact(revision), beeSlots, lanes, energy, energyCapacity, bees, jobs, buffer), moved);
	}
}
