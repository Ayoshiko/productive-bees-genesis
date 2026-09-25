package com.ayoshiko.productivebeesgenesis.multiblock.runtime;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScan;
import com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScanStamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

/** 每维度、单主线程目录；只有成功提交的绑定可运行，部件不持有第二份机器状态。 */
public final class MachineDirectory {
	// 进程内只保存一个序号；同 UUID/代际重新加载或换目录也不能重放旧扫描。
	private static final AtomicLong NEXT_EPOCH = new AtomicLong();
	public enum State { UNFORMED, VALIDATING, FORMED, REBUILDING, SUSPENDED, RECOVERY, REMOVED }
	public enum Formation { FORMED, STALE, CONFLICT, BUSY, FAILED }
	public record Binding(Handle handle, StructureScanStamp stamp, MachineRegion region, String variant) { }
	public static final class Handle {
		private final UUID id;
		private final long generation;
		private final BlockPos controller;
		private final Direction facing;
		private final StructureDefinition definition;
		private final MachineRegion candidates;
		private final List<MachineRegion.Section> sections;
		private final Set<ChunkPos> chunks;
		private long epoch;
		private State state = State.UNFORMED;
		private boolean duplicate, exhausted;
		private MachineRegion validated;
		private Binding binding;
		private RuntimeException failure;
		private Handle(UUID id, long generation, BlockPos controller, Direction facing, StructureDefinition definition, MachineRegion candidates) {
			this.id = id; this.generation = generation; this.controller = controller.immutable(); this.facing = facing;
			this.definition = definition; this.candidates = candidates; sections = candidates.sections();
			epoch = nextEpoch();
			var covered = ConcurrentHashMap.<ChunkPos>newKeySet();
			for (var section : sections) covered.add(new ChunkPos(section.x(), section.z()));
			chunks = Set.copyOf(covered);
		}
		public UUID id() { return id; }
		public BlockPos controller() { return controller; }
		public Direction facing() { return facing; }
		public State state() { return state; }
		public MachineRegion candidates() { return candidates; }
		public Set<ChunkPos> chunks() { return chunks; }
		public StructureScanStamp stamp() { return new StructureScanStamp(id, generation, epoch, definition.id(), definition.layoutVersion()); }
		public Optional<Binding> binding() { return Optional.ofNullable(binding); }
		public Optional<RuntimeException> failure() { return Optional.ofNullable(failure); }
	}
	private final Thread thread = Thread.currentThread();
	private final StructureDefinition definition;
	private final Map<BlockPos, Handle> controllers = new ConcurrentHashMap<>();
	private final Map<UUID, Set<Handle>> identities = new ConcurrentHashMap<>();
	private final Map<MachineRegion.Section, Set<Handle>> sections = new ConcurrentHashMap<>();
	private final Map<ChunkPos, Set<Handle>> chunks = new ConcurrentHashMap<>();
	private boolean committing;
	public MachineDirectory(StructureDefinition definition) { this.definition = Objects.requireNonNull(definition); }
	private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Machine directory requires its owning thread"); }
	public int size() { checkThread(); return controllers.size(); }
	public int indexedSections() { checkThread(); return sections.size(); }
	public int indexedChunks() { checkThread(); return chunks.size(); }
	public List<Handle> sameIdentity(Handle handle) { checkThread(); var entries = identities.get(handle.id); return entries == null ? List.of() : List.copyOf(entries); }
	public boolean current(Handle handle) { checkThread(); return handle != null && controllers.get(handle.controller) == handle && handle.state != State.REMOVED; }
	public boolean active(Binding binding) {
		return binding != null && current(binding.handle) && binding.handle.state == State.FORMED && binding.handle.binding == binding;
	}
	public Handle attach(UUID id, long generation, BlockPos position, Direction facing) {
		checkThread(); Objects.requireNonNull(id);
		if (committing) throw new IllegalStateException("Cannot attach during formation callback");
		if (generation < 0 || controllers.containsKey(position)) throw new IllegalArgumentException("Invalid or occupied controller");
		MachineRegion region = null;
		for (var template : definition.candidates()) {
			var bounds = MachineRegion.at(template.geometry(), position, facing); region = region == null ? bounds : region.union(bounds);
		}
		var handle = new Handle(id, generation, position, facing, definition, region);
		controllers.put(handle.controller, handle);
		for (var key : handle.sections) sections.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(handle);
		for (var key : handle.chunks) chunks.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(handle);
		var sameId = identities.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()); sameId.add(handle);
		if (sameId.size() > 1) for (var entry : sameId) { entry.duplicate = true; change(entry, State.RECOVERY, false); }
		return handle;
	}
	public boolean beginValidation(Handle handle) {
		if (!current(handle) || committing || handle.duplicate || handle.exhausted || handle.state == State.RECOVERY || handle.state == State.FORMED || handle.state == State.VALIDATING) return false;
		handle.state = State.VALIDATING; handle.failure = null; return true;
	}
	public void invalidate(Handle handle, State reason) {
		checkThread();
		if (reason == State.FORMED || reason == State.VALIDATING || reason == State.REMOVED) throw new IllegalArgumentException("Invalid invalidation state");
		if (current(handle)) change(handle, reason, false);
	}
	private void change(Handle handle, State state, boolean retainValidated) {
		handle.binding = null;
		if (!retainValidated) handle.validated = null;
		try { handle.epoch = nextEpoch(); } catch (IllegalStateException exhausted) { handle.exhausted = true; handle.failure = exhausted; }
		handle.state = handle.duplicate || handle.exhausted ? State.RECOVERY : state;
	}
	private static long nextEpoch() {
		long epoch = NEXT_EPOCH.getAndUpdate(value -> value == Long.MAX_VALUE ? value : value + 1);
		if (epoch == Long.MAX_VALUE) throw new IllegalStateException("Machine mutation epochs exhausted");
		return epoch;
	}
	public List<Handle> affectedAt(BlockPos position) {
		checkThread(); var entries = sections.get(new MachineRegion.Section(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4));
		return entries == null ? List.of() : entries.stream().filter(entry -> entry.candidates.contains(position)).toList();
	}
	public List<Handle> affectedChunk(ChunkPos position) {
		checkThread(); var entries = chunks.get(position); return entries == null ? List.of() : List.copyOf(entries);
	}
	/** 回调只绑定可撤销的部件引用；不能在此交换物料或发放产物。 */
	public Formation form(Handle handle, StructureScan.Match match, Consumer<Binding> bindParts) {
		checkThread(); Objects.requireNonNull(bindParts);
		if (committing) return Formation.BUSY;
		if (!current(handle) || handle.state != State.VALIDATING || match == null || !handle.stamp().equals(match.stamp())
				|| !handle.controller.equals(match.controller()) || handle.facing != match.facing() || !definition.candidates().contains(match.template())) return Formation.STALE;
		var region = MachineRegion.at(match.template().geometry(), handle.controller, handle.facing);
		var nearby = ConcurrentHashMap.<Handle>newKeySet();
		for (var key : region.sections()) { var indexed = sections.get(key); if (indexed != null) nearby.addAll(indexed); }
		var conflicts = nearby.stream().filter(other -> other != handle && other.validated != null && region.intersects(other.validated)).toList();
		handle.validated = region;
		if (!conflicts.isEmpty()) {
			// 保留双方已验证的冲突范围，直到世界变更；不能下一 tick 按顺序选出赢家。
			change(handle, State.RECOVERY, true);
			for (var other : conflicts) change(other, State.RECOVERY, true);
			return Formation.CONFLICT;
		}
		committing = true;
		try {
			var binding = new Binding(handle, match.stamp(), region, match.template().variant());
			bindParts.accept(binding);
			if (!current(handle) || handle.state != State.VALIDATING || !handle.stamp().equals(match.stamp())) return Formation.STALE;
			handle.binding = binding; handle.state = State.FORMED; return Formation.FORMED;
		} catch (RuntimeException failure) {
			if (current(handle)) { handle.failure = failure; change(handle, State.RECOVERY, false); }
			return Formation.FAILED;
		} finally { committing = false; }
	}
	public void remove(Handle handle) {
		if (!current(handle)) return;
		change(handle, State.REMOVED, false); handle.state = State.REMOVED; controllers.remove(handle.controller, handle);
		for (var key : handle.sections) removeIndex(sections, key, handle);
		for (var key : handle.chunks) removeIndex(chunks, key, handle);
		var sameId = identities.get(handle.id); sameId.remove(handle);
		if (sameId.isEmpty()) identities.remove(handle.id);
		else if (sameId.size() == 1) { var remaining = sameId.iterator().next(); remaining.duplicate = false; change(remaining, State.REBUILDING, false); }
	}
	private static <K> void removeIndex(Map<K, Set<Handle>> index, K key, Handle handle) {
		var entries = index.get(key); entries.remove(handle); if (entries.isEmpty()) index.remove(key);
	}
	public void clear() { checkThread(); for (var handle : List.copyOf(controllers.values())) remove(handle); }
}
