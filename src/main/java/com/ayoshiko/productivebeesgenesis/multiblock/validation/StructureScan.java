package com.ayoshiko.productivebeesgenesis.multiblock.validation;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureCell;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureTemplate;
import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import static com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScanDiagnostic.Reason.*;

/** 主线程增量验证，不持有世界或逐格副本；匹配结果仍须经过 M03 排他占位与绑定。 */
public final class StructureScan {
	public enum Status { SCANNING, MATCHED, INVALID, SUSPENDED, AMBIGUOUS, STALE, FAILED, CANCELLED }
	public record Step(Status status, int inspected, int reads) { }
	public record Match(StructureScanStamp stamp, StructureTemplate template, BlockPos controller, Direction facing) {
		public Match { controller = controller.immutable(); }
	}
	private final StructureDefinition definition;
	private final StructureScanStamp captured;
	private final BlockPos controller;
	private final Direction facing;
	private final List<StructureScanDiagnostic> diagnostics = new ArrayList<>();
	private final List<Match> matches = new ArrayList<>(2);
	private int candidate;
	private long cursor;
	private StructureTransform transform;
	private Status status = Status.SCANNING;
	private boolean unavailable, advancing;
	private RuntimeException failure;

	public StructureScan(StructureDefinition definition, StructureScanStamp stamp, BlockPos controller, Direction facing) {
		this.definition = Objects.requireNonNull(definition); this.captured = Objects.requireNonNull(stamp);
		this.controller = Objects.requireNonNull(controller).immutable(); this.facing = Objects.requireNonNull(facing);
		if (!facing.getAxis().isHorizontal()) throw new IllegalArgumentException("Horizontal controller facing required");
		if (!definition.id().equals(stamp.definitionId()) || definition.layoutVersion() != stamp.layoutVersion()) status = Status.STALE;
	}
	public Status status() { return status; }
	public List<StructureScanDiagnostic> diagnostics() { return List.copyOf(diagnostics); }
	public List<String> matchedVariants() { return matches.stream().map(match -> match.template().variant()).toList(); }
	/** 查询故障保留原异常，由未来世界适配层记录一次并按事件重试。 */
	public Optional<RuntimeException> failure() { return Optional.ofNullable(failure); }
	/** 发布前必须传入当前凭据；这不代替提交期间的重入守卫或排他占位。 */
	public Optional<Match> readyMatch(StructureScanStamp current) {
		return !advancing && status == Status.MATCHED && captured.equals(current) ? Optional.of(matches.getFirst()) : Optional.empty();
	}
	public void cancel() { status = Status.CANCELLED; matches.clear(); }

	/** 一个工作单位最多一次区块／边界检查和一次状态读取；每批另有两次 O(1) 凭据检查。 */
	public Step advance(int budget, StructureScanAccess access) {
		if (budget < 0) throw new IllegalArgumentException("Negative scan budget");
		if (advancing) throw new IllegalStateException("Reentrant structure scan");
		Objects.requireNonNull(access);
		if (budget == 0 || status == Status.CANCELLED || status == Status.STALE || status == Status.FAILED) return new Step(status, 0, 0);
		int inspected = 0, reads = 0;
		BlockPos local = null, world = null;
		StructureCell expected = null;
		advancing = true;
		try {
			if (!current(access)) return new Step(status, 0, 0);
			while (status == Status.SCANNING && inspected < budget) {
				inspected++;
				var template = definition.candidates().get(candidate);
				local = template.geometry().size().positionAt(cursor); world = null; expected = null;
				if (transform == null) {
					try { transform = template.geometry().at(controller, facing); }
					catch (ArithmeticException overflow) { reject(COORDINATE_OVERFLOW, local, null, null, null); continue; }
				}
				world = transform.toWorld(local);
				var rule = template.cellAt(local);
				expected = rule.facing() == null || facing == Direction.NORTH ? rule
						: new StructureCell(rule.roles(), transform.toWorldDirection(rule.facing()));
				var availability = Objects.requireNonNull(access.availability(world));
				if (status == Status.CANCELLED) break;
				if (availability != StructureScanAccess.Availability.LOADED) {
					boolean unloaded = availability == StructureScanAccess.Availability.UNLOADED;
					unavailable |= unloaded;
					reject(unloaded ? UNLOADED_CHUNK : OUTSIDE_WORLD, local, world, expected, null); continue;
				}
				reads++;
				var observed = Objects.requireNonNull(access.read(world));
				if (status == Status.CANCELLED) break;
				if (!expected.roles().contains(observed.role())) {
					reject(WRONG_ROLE, local, world, expected, observed); continue;
				}
				if (expected.facing() != null && expected.facing() != observed.facing()) {
					reject(WRONG_FACING, local, world, expected, observed); continue;
				}
				if (++cursor == template.geometry().size().volume()) {
					matches.add(new Match(captured, template, controller, facing));
					if (matches.size() == 2) status = Status.AMBIGUOUS;
					else nextCandidate();
				}
			}
			// 查询回调期间若发生变化，刚刚完成的候选也不得发布。
			if (status != Status.CANCELLED) current(access);
		} catch (RuntimeException error) {
			if (status != Status.CANCELLED) {
				failure = error; status = Status.FAILED; matches.clear();
				diagnostics.add(new StructureScanDiagnostic(candidate < definition.candidates().size()
						? definition.candidates().get(candidate).variant() : "", QUERY_FAILED, local, world, expected, null));
			}
		} finally { advancing = false; }
		return new Step(status, inspected, reads);
	}
	private boolean current(StructureScanAccess access) {
		var current = access.stamp();
		if (status == Status.CANCELLED) return false;
		if (captured.equals(current)) return true;
		status = Status.STALE; matches.clear(); return false;
	}
	private void reject(StructureScanDiagnostic.Reason reason, BlockPos local, BlockPos world,
			StructureCell expected, StructureScanAccess.State observed) {
		diagnostics.add(new StructureScanDiagnostic(definition.candidates().get(candidate).variant(), reason, local, world, expected, observed));
		nextCandidate();
	}
	private void nextCandidate() {
		cursor = 0; transform = null;
		if (++candidate == definition.candidates().size()) status = unavailable ? Status.SUSPENDED : matches.isEmpty() ? Status.INVALID : Status.MATCHED;
	}
}
