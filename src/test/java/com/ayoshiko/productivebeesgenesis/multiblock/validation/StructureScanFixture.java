package com.ayoshiko.productivebeesgenesis.multiblock.validation;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.*;
import com.ayoshiko.productivebeesgenesis.multiblock.geometry.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole.*;
import static org.junit.jupiter.api.Assertions.*;

final class StructureScanFixture implements StructureScanAccess {
	StructureScanStamp current;
	int reads, availabilityChecks, stampChecks;
	final Set<ChunkPos> unloaded = ConcurrentHashMap.newKeySet();
	final Map<BlockPos, State> overrides = new ConcurrentHashMap<>();
	final Function<BlockPos, State> states;
	Consumer<BlockPos> onRead = ignored -> { };
	Consumer<BlockPos> onAvailability = ignored -> { };
	Runnable onStamp = () -> { };
	boolean outside;

	StructureScanFixture(StructureDefinition definition, Function<BlockPos, State> states) {
		current = new StructureScanStamp(UUID.randomUUID(), 1, 0, definition.id(), definition.layoutVersion());
		this.states = states;
	}
	@Override public StructureScanStamp stamp() { stampChecks++; onStamp.run(); return current; }
	@Override public Availability availability(BlockPos position) {
		availabilityChecks++; onAvailability.accept(position);
		return outside ? Availability.OUTSIDE_WORLD : unloaded.contains(new ChunkPos(position)) ? Availability.UNLOADED : Availability.LOADED;
	}
	@Override public State read(BlockPos position) {
		assertFalse(unloaded.contains(new ChunkPos(position)), "Scanner read an unloaded chunk");
		assertFalse(outside); reads++; onRead.accept(position);
		return overrides.containsKey(position) ? overrides.get(position) : states.apply(position);
	}
	void mutate() { current = new StructureScanStamp(current.machineId(), current.generation(), current.mutationEpoch() + 1, current.definitionId(), current.layoutVersion()); }
	static State state(StructureRole role) { return new State(role, null); }

	/** 独立按产品布局摆放，不调用被测模板的 cellAt。 */
	static StructureScanFixture combined(int depth, BlockPos controller, Direction facing) {
		return combined(depth, 5, controller, facing);
	}
	static StructureScanFixture combined(int depth, int height, BlockPos controller, Direction facing) {
		var transform = new StructureTransform(controller, new BlockPos(3, 1, 0), facing);
		var placed = new ConcurrentHashMap<BlockPos, State>();
		for (int x = 0; x < 7; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) {
			int boundaries = (x == 0 || x == 6 ? 1 : 0) + (y == 0 || y == height - 1 ? 1 : 0) + (z == 0 || z == depth - 1 ? 1 : 0);
			placed.put(transform.toWorld(new BlockPos(x, y, z)), state(boundaries >= 2 ? FRAME : boundaries == 1 ? CASING : AIR));
		}
		placed.put(controller, new State(CONTROLLER, facing));
		placed.put(transform.toWorld(new BlockPos(3, 2, 0)), new State(INTERFACE, facing));
		placed.put(transform.toWorld(new BlockPos(3, height / 2, depth / 2)), state(CORE));
		placed.put(transform.toWorld(new BlockPos(2, 1, depth / 2)), new State(APIARY_UNIT, facing));
		placed.put(transform.toWorld(new BlockPos(4, 1, depth / 2)), new State(CENTRIFUGE_UNIT, facing));
		placed.put(transform.toWorld(new BlockPos(0, 1, 2)), new State(ENERGY_PORT, facing.getCounterClockWise()));
		placed.put(transform.toWorld(new BlockPos(2, 1, depth - 1)), new State(INPUT_PORT, facing.getOpposite()));
		placed.put(transform.toWorld(new BlockPos(4, 1, depth - 1)), new State(OUTPUT_PORT, facing.getOpposite()));
		return new StructureScanFixture(CombinedApiaryDefinition.DEFINITION, p -> placed.getOrDefault(p, state(AIR)));
	}
	static StructureTemplate solid(String variant, int width, int height, int depth) {
		var size = new StructureSize(width, height, depth);
		var anchor = new BlockPos(1, 1, 0); var core = new BlockPos(1, 1, 1);
		var rule = StructureCell.of(CASING);
		return new StructureTemplate(variant, new StructureGeometry(size, anchor, Vec3.atCenterOf(core), new AABB(core)),
				Map.of(StructureSize.Region.CORNER, rule, StructureSize.Region.EDGE, rule, StructureSize.Region.FACE, rule, StructureSize.Region.INTERIOR, rule),
				Map.of(anchor, StructureCell.facing(CONTROLLER, Direction.NORTH), core, StructureCell.of(CORE)));
	}
	static StructureScanFixture solid(StructureDefinition definition, BlockPos controller) {
		return new StructureScanFixture(definition, p -> p.equals(controller) ? new State(CONTROLLER, Direction.NORTH)
				: p.equals(controller.south()) ? state(CORE) : state(CASING));
	}
	static void finish(StructureScan scan, StructureScanFixture source, int budget) {
		int loops = 0;
		while (scan.status() == StructureScan.Status.SCANNING) {
			assertTrue(++loops < 100000, "Scanner failed to advance");
			int beforeReads = source.reads, beforeAvailability = source.availabilityChecks, beforeStamps = source.stampChecks;
			var step = scan.advance(budget, source);
			assertTrue(step.inspected() <= budget); assertTrue(step.reads() <= step.inspected());
			assertEquals(source.reads - beforeReads, step.reads());
			assertTrue(source.availabilityChecks - beforeAvailability <= step.inspected());
			assertTrue(source.stampChecks - beforeStamps <= 2);
		}
	}
}
