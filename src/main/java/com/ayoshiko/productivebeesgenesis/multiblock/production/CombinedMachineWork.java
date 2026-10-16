package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import java.nio.charset.StandardCharsets;
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
	private final List<FeedingSlotStore.Slot> feeding;
	private final MachineUpgrades upgrades;
	private final CentrifugeWorkState centrifugeView;
	private final Object rosterVersion;
	private final long feedingRevision;
	public CombinedMachineWork(UUID machine, long generation, long revision, int beeSlots, int lanes,
			long energy, long energyCapacity, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> centrifuges, FiniteProductBuffer buffer) {
		this(machine, generation, revision, beeSlots, lanes, energy, energyCapacity, bees, centrifuges, buffer, emptyFeeding(beeSlots));
	}
	public CombinedMachineWork(UUID machine, long generation, long revision, int beeSlots, int lanes,
			long energy, long energyCapacity, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> centrifuges,
			FiniteProductBuffer buffer, List<FeedingSlotStore.Slot> feeding) {
		this(machine, generation, revision, beeSlots, lanes, energy, energyCapacity, bees, centrifuges, buffer, feeding, MachineUpgrades.EMPTY);
	}
	public CombinedMachineWork(UUID machine, long generation, long revision, int beeSlots, int lanes,
			long energy, long energyCapacity, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> centrifuges,
			FiniteProductBuffer buffer, List<FeedingSlotStore.Slot> feeding, MachineUpgrades upgrades) {
		this(machine, generation, revision, beeSlots, lanes, energy, energyCapacity, bees, centrifuges, buffer, feeding, upgrades, null);
	}
	private CombinedMachineWork(UUID machine, long generation, long revision, int beeSlots, int lanes,
			long energy, long energyCapacity, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> centrifuges,
			FiniteProductBuffer buffer, List<FeedingSlotStore.Slot> feeding, MachineUpgrades upgrades, CombinedMachineWork previous) {
		this.upgrades = Objects.requireNonNull(upgrades);
		this.feeding = List.copyOf(feeding);
		if (feeding.size() != beeSlots) throw new IllegalArgumentException("Feeding capacity differs from bee slots");
		for (int i = 0; i < feeding.size(); i++) {
			int group = feeding.get(i).group();
			if (group > i || feeding.get(group).group() != group) throw new IllegalArgumentException("Noncanonical machine feeding group");
		}
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
		rosterVersion = previous != null && sameRoster(previous.bees, this.bees) ? previous.rosterVersion : new Object();
		feedingRevision = previous == null ? 0 : previous.feeding.equals(this.feeding) ? previous.feedingRevision : Math.incrementExact(previous.feedingRevision);
		var jobs = new HashMap<Integer, CentrifugeJob>();
		var receipts = new HashMap<Integer, Map<ProductKey, ProductAmount>>();
		centrifuges.forEach((lane, delivery) -> { jobs.put(lane, delivery.job()); if (!delivery.delivered().isEmpty()) receipts.put(lane, delivery.delivered()); });
		centrifugeView = new CentrifugeWorkState(machine, revision, lanes, energy, energyCapacity, jobs, energy == 0, receipts);
	}
	/** 只生成原根凭据；网络激活时移交 FE，返还时不另造一份共享能量。 */
	public Change transferEnergy() { return change(0, bees, centrifuges, buffer, energy); }
	public CentrifugeWorkState centrifugeView() { return centrifugeView; }
	public Change applyCentrifuge(CentrifugeWorkTransaction transaction, LedgerCheckpoint ledger) {
		if (energy != 0 || !transaction.matches(centrifugeView, ledger)) throw new IllegalArgumentException("Stale combined centrifuge transaction");
		var jobs = new HashMap<Integer, CentrifugeDelivery>();
		transaction.state().jobs().forEach((lane, job) -> jobs.put(lane, new CentrifugeDelivery(job, transaction.state().delivered().getOrDefault(lane, Map.of()))));
		return change(0, bees, jobs, buffer, 0);
	}
	public Change applyBee(BeeWorkExecutor.BeeResult result) {
		if (energy != 0 || result.status() != BeeWorkExecutor.Status.READY || !result.matches(bee(result.candidate().slot()))) throw new IllegalArgumentException("Stale combined bee work");
		return change(0, replaceBee(result.candidate()), centrifuges, buffer, 0);
	}
	public Change clearFrozenBee(int slot, long revision) {
		var bee = bee(slot); if (bee.revision() != revision || !bee.hasFrozen()) return unchanged();
		return change(energy, replaceBee(bee.work(bee.progress(), bee.pendingCycles(), Map.of())), centrifuges, buffer, 0);
	}
	public boolean drained() { return bees.stream().allMatch(BeeRecord::drained) && centrifuges.isEmpty(); }
	public UUID machine() { return machine; }
	public long generation() { return generation; }
	public long revision() { return revision; }
	public long energy() { return energy; }
	public long energyCapacity() { return energyCapacity; }
	public int beeSlots() { return beeSlots; }
	public int lanes() { return lanes; }
	public List<BeeRecord> bees() { return bees; }
	public Object rosterVersion() { return rosterVersion; }
	public long feedingRevision() { return feedingRevision; }
	public Map<Integer, CentrifugeDelivery> centrifuges() { return centrifuges; }
	public FiniteProductBuffer buffer() { return buffer; }
	public List<FeedingSlotStore.Slot> feeding() { return feeding; }
	public MachineUpgrades upgrades() { return upgrades; }
	public Change exchangeUpgrade(int slot, int delta) {
		var next = upgrades.change(slot, delta);
		return next == upgrades ? unchanged() : new Change(this, new CombinedMachineWork(machine, generation, Math.incrementExact(revision),
				beeSlots, lanes, energy, energyCapacity, bees, centrifuges, buffer, feeding, next, this), Math.abs((long) delta));
	}
	public static List<FeedingSlotStore.Slot> emptyFeeding(int size) {
		if (size < 1) throw new IllegalArgumentException("Invalid feeding size");
		return java.util.stream.IntStream.range(0, size).mapToObj(i -> new FeedingSlotStore.Slot(null, 0, false, i)).toList();
	}
	public Change depositFeeding(int slot, FeedingItem item, int offered) {
		Objects.requireNonNull(item);
		if (offered < 1) throw new IllegalArgumentException("Invalid feeding offer");
		var target = feeding.get(slot);
		if (target.item() != null && !target.item().equals(item)) return unchanged();
		int accepted = Math.min(offered, item.limit() - target.count());
		return accepted == 0 ? unchanged() : feedingChange(slot, new FeedingSlotStore.Slot(item, target.count() + accepted, target.disabled(), target.group()), accepted);
	}
	public Change withdrawFeeding(int slot, int requested) {
		if (requested < 1) throw new IllegalArgumentException("Invalid feeding request");
		var source = feeding.get(slot); int taken = Math.min(requested, source.count()), remaining = source.count() - taken;
		return taken == 0 ? unchanged() : feedingChange(slot, new FeedingSlotStore.Slot(remaining == 0 ? null : source.item(), remaining, remaining > 0 && source.disabled(), source.group()), taken);
	}
	private Change feedingChange(int slot, FeedingSlotStore.Slot value, int moved) {
		var next = new ArrayList<>(feeding); next.set(slot, value);
		return new Change(this, new CombinedMachineWork(machine, generation, Math.incrementExact(revision), beeSlots, lanes, energy, energyCapacity, bees, centrifuges, buffer, next, upgrades, this), moved);
	}
	public Change feedingDisabled(int slot, boolean disabled) {
		var old = feeding.get(slot);
		return old.disabled() == disabled ? unchanged() : feedingChange(slot, new FeedingSlotStore.Slot(old.item(), old.count(), disabled, old.group()), 0);
	}
	public Change beeEnabled(int slot, UUID expected, boolean enabled) {
		var old = bee(slot); if (!old.id().equals(expected)) throw new IllegalArgumentException("Stale combined bee");
		var next = old.enabled(enabled); return next == old ? unchanged() : change(energy, replaceBee(next), centrifuges, buffer, 0);
	}
	/** 新身份由原根版本派生；模拟不消耗随机数，取出后再插入也不会复活旧身份。 */
	public Change insertBee(int slot, AssetImage original, StaticBeePlan plan) {
		if (slot < 0 || slot >= beeSlots || bees.stream().anyMatch(bee -> bee.slot() == slot)) throw new IllegalArgumentException("Occupied or invalid bee slot");
		var raw = original.copy();
		if (raw.getInt("slot_index") != slot || raw.getInt("ticks_in_hive") != 0) throw new IllegalArgumentException("Inserted bee carries foreign progress");
		var id = UUID.nameUUIDFromBytes((machine + ":cage:" + generation + ":" + revision + ":" + slot).getBytes(StandardCharsets.UTF_8));
		var next = new ArrayList<>(bees);
		next.add(new BeeRecord(id, machine, slot, original, plan, 0, 0, 0, ProductAmount.ZERO)); next.sort(Comparator.comparingInt(BeeRecord::slot));
		return change(energy, next, centrifuges, buffer, 1);
	}
	/** 已付费结果先结清；装笼取消未完成周期，不退 FE，食物仍留在原位。 */
	public Change extractBee(int slot, UUID expectedBee) {
		var current = bee(slot);
		if (!current.id().equals(expectedBee)) throw new IllegalArgumentException("Stale machine bee");
		if (!current.drained()) throw new IllegalStateException("Drain paid work before caging");
		return change(energy, bees.stream().filter(bee -> bee != current).toList(), centrifuges, buffer, 1);
	}
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
	public Change insertItem(int slot, ProductKey key, long offered, int stackLimit) {
		var transfer = buffer.insertItem(slot, key, offered, stackLimit);
		return transfer.moved() == 0 ? unchanged() : change(energy, bees, centrifuges, transfer.buffer(), transfer.moved());
	}
	public Change extractItem(int slot, long requested) {
		var transfer = buffer.extractItem(slot, requested);
		return transfer.moved() == 0 ? unchanged() : change(energy, bees, centrifuges, transfer.buffer(), transfer.moved());
	}
	public record BeeStep(Change change, BeeWorkExecutor.Status status) { }
	public Change advanceBee(int slot, long expectedBeeRevision, BeeWorkExecutor.Context context, int ticks, int samplingBudget, BeeWorkExecutor.Cycle nextCycle) {
		return stepBee(slot, expectedBeeRevision, context, ticks, samplingBudget, nextCycle).change();
	}
	/** 将同一次计算的阻塞原因交给显示层，不重复执行或从进度猜测。 */
	public BeeStep stepBee(int slot, long expectedBeeRevision, BeeWorkExecutor.Context context, int ticks, int samplingBudget, BeeWorkExecutor.Cycle nextCycle) {
		var current = bee(slot);
		// 已付费周期只能沿用原能力到周期边界；下一次调用才切入新计划。
		if (current.progress() != 0 && ticks > 0) { ticks = Math.min(ticks, current.plan().cycleTicks() - current.progress() % current.plan().cycleTicks()); nextCycle = null; }
		var result = BeeWorkExecutor.advanceBee(current, expectedBeeRevision, context, ticks, samplingBudget, energy, nextCycle);
		if (result.status() != BeeWorkExecutor.Status.READY) return new BeeStep(unchanged(), result.status());
		return new BeeStep(change(energy - result.energyUsed(), replaceBee(result.candidate()), centrifuges, buffer, 0), result.status());
	}
	public Change settleBee(int slot, int stackLimit) {
		return settleBee(slot, key -> stackLimit);
	}
	public Change resolveGeneration(int slot, BeeGeneration generated) {
		return change(energy, replaceBee(bee(slot).resolveGeneration(generated)), centrifuges, buffer, 0);
	}
	public Change settleBee(int slot, ToIntFunction<ProductKey> itemLimits) {
		var current = bee(slot); if (!current.hasFrozen()) return unchanged();
		var nextBuffer = buffer; long moved = 0;
		var remaining = new HashMap<>(current.frozenOutputs());
		for (var entry : current.frozenOutputs().entrySet()) {
			var key = entry.getKey();
			var transfer = nextBuffer.insert(key, entry.getValue().longSaturated(), key.kind() == ProductKey.Kind.ITEM ? itemLimits.applyAsInt(key) : 0);
			nextBuffer = transfer.buffer(); moved = Math.addExact(moved, transfer.moved());
			var rest = entry.getValue().subtract(ProductAmount.of(transfer.moved()));
			if (rest.isZero()) remaining.remove(key); else remaining.put(key, rest);
		}
		if (moved == 0) return unchanged();
		var next = current.work(current.progress(), current.pendingCycles(), remaining);
		return change(energy, replaceBee(next), centrifuges, nextBuffer, moved);
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
	@Override public boolean equals(Object other) {
		if (this == other) return true;
		return other instanceof CombinedMachineWork next && machine.equals(next.machine) && generation == next.generation
				&& revision == next.revision && energy == next.energy && energyCapacity == next.energyCapacity && beeSlots == next.beeSlots
				&& lanes == next.lanes && bees.equals(next.bees) && centrifuges.equals(next.centrifuges) && buffer.items().equals(next.buffer.items()) && buffer.fluids().equals(next.buffer.fluids()) && buffer.tankCapacity() == next.buffer.tankCapacity()
				&& feeding.equals(next.feeding) && upgrades.equals(next.upgrades);
	}
	@Override public int hashCode() { return Objects.hash(machine, generation, revision, energy, energyCapacity, beeSlots, lanes, bees, centrifuges, buffer.items(), buffer.fluids(), buffer.tankCapacity(), feeding, upgrades); }
	private void checkLane(int lane) { if (lane < 0 || lane >= lanes) throw new IllegalArgumentException("Invalid centrifuge lane"); }
	private Change unchanged() { return new Change(this, this, 0); }
	private Change change(long energy, List<BeeRecord> bees, Map<Integer, CentrifugeDelivery> jobs, FiniteProductBuffer buffer, long moved) {
		return new Change(this, new CombinedMachineWork(machine, generation, Math.incrementExact(revision), beeSlots, lanes, energy, energyCapacity, bees, jobs, buffer, feeding, upgrades, this), moved);
	}
	private static boolean sameRoster(List<BeeRecord> before, List<BeeRecord> after) {
		if (before.size() != after.size()) return false;
		for (int i = 0; i < before.size(); i++) {
			var a = before.get(i); var b = after.get(i);
			if (a.slot() != b.slot() || !a.id().equals(b.id()) || a.enabled() != b.enabled()) return false;
		}
		return true;
	}
}
