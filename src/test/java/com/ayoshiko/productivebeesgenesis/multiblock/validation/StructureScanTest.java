package com.ayoshiko.productivebeesgenesis.multiblock.validation;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole.*;
import static com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScan.Status.*;
import static com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScanDiagnostic.Reason.*;
import static com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScanFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class StructureScanTest {
	private static final StructureDefinition COMBINED = CombinedApiaryDefinition.DEFINITION;
	private static final BlockPos CONTROLLER = new BlockPos(-32, 65, -48);
	private StructureDefinition definition(StructureTemplate... templates) { return new StructureDefinition(COMBINED.id(), COMBINED.layoutVersion(), List.of(templates)); }
	private StructureScan scan(StructureDefinition definition, StructureScanFixture source) { return new StructureScan(definition, source.current, CONTROLLER, Direction.NORTH); }

	@Test void allSizesAndOrientationsResolveOneCandidateUnderSharedCellBudget() {
		for (int height : List.of(5, 7)) for (int depth : List.of(5, 7, 9)) for (var facing : Direction.Plane.HORIZONTAL) {
			var source = combined(depth, height, CONTROLLER, facing);
			var scan = new StructureScan(COMBINED, source.current, CONTROLLER, facing);
			finish(scan, source, 7);
			assertEquals(MATCHED, scan.status()); assertEquals(List.of("7x" + height + "x" + depth), scan.matchedVariants());
			assertEquals(depth, scan.readyMatch(source.current).orElseThrow().template().geometry().size().depth());
			assertTrue(source.reads >= 7 * height * depth);
			assertTrue(source.reads <= 7 * (5 + 7) * (5 + 7 + 9));
			int reads = source.reads; scan.advance(7, source); assertEquals(reads, source.reads);
		}
	}
	@Test void zeroBudgetAndLargeVolumesDoNotEagerlyReadOrAllocateTheShape() {
		var definition = definition(solid("large", 50000, 50000, 50000));
		var source = solid(definition, CONTROLLER); var scan = scan(definition, source);
		assertEquals(0, scan.advance(0, source).inspected()); assertEquals(0, source.stampChecks);
		var step = scan.advance(1, source);
		assertEquals(SCANNING, step.status()); assertEquals(1, step.reads()); assertEquals(1, source.availabilityChecks);
		assertThrows(IllegalArgumentException.class, () -> scan.advance(-1, source));
	}
	@Test void internalObstructionMissingOrDuplicateCoreAndMissingUnitHavePositions() {
		var template = COMBINED.candidates().getFirst(); var transform = template.geometry().at(CONTROLLER, Direction.NORTH);
		var definition = definition(template);
		var changes = List.of(new BlockPos(1, 2, 1), new BlockPos(3, 2, 2), new BlockPos(1, 1, 1), new BlockPos(2, 1, 2), new BlockPos(6, 1, 2));
		var roles = List.of(OTHER, AIR, CORE, AIR, StructureRole.CONTROLLER);
		for (int i = 0; i < changes.size(); i++) {
			var source = combined(5, CONTROLLER, Direction.NORTH);
			var position = transform.toWorld(changes.get(i)); source.overrides.put(position, state(roles.get(i)));
			var scan = scan(definition, source); finish(scan, source, 3);
			assertEquals(INVALID, scan.status()); assertTrue(scan.readyMatch(source.current).isEmpty());
			var diagnostic = scan.diagnostics().getFirst();
			assertEquals(WRONG_ROLE, diagnostic.reason()); assertEquals(position, diagnostic.world()); assertEquals(changes.get(i), diagnostic.local());
		}
	}
	@Test void allowedGlassFaceAndFrozenControllerRemainValid() {
		var source = combined(5, CONTROLLER, Direction.NORTH);
		var template = COMBINED.candidates().getFirst(); var transform = template.geometry().at(CONTROLLER, Direction.NORTH);
		source.overrides.put(transform.toWorld(new BlockPos(6, 2, 2)), state(GLASS));
		var mutable = CONTROLLER.mutable(); var scan = new StructureScan(definition(template), source.current, mutable, Direction.NORTH);
		mutable.set(0, 0, 0); finish(scan, source, 1);
		assertEquals(MATCHED, scan.status()); assertEquals(CONTROLLER, scan.readyMatch(source.current).orElseThrow().controller());
	}
	@Test void incorrectPortDirectionFailsAfterRotatingBothPositionAndRule() {
		for (var facing : Direction.Plane.HORIZONTAL) {
			var template = COMBINED.candidates().getFirst(); var source = combined(5, CONTROLLER, facing);
			var position = template.geometry().at(CONTROLLER, facing).toWorld(new BlockPos(0, 1, 2));
			source.overrides.put(position, new StructureScanAccess.State(ENERGY_PORT, facing));
			var scan = new StructureScan(definition(template), source.current, CONTROLLER, facing); finish(scan, source, 9);
			assertEquals(INVALID, scan.status()); assertEquals(WRONG_FACING, scan.diagnostics().getFirst().reason());
			assertEquals(facing.getCounterClockWise(), scan.diagnostics().getFirst().expected().facing());
		}
	}
	@Test void missingMiddleChunkIsDetectedWhenAllFourCornersAreLoaded() {
		var template = solid("wide", 49, 5, 49); var definition = definition(template);
		var source = solid(definition, BlockPos.ZERO); source.unloaded.add(new ChunkPos(1, 1));
		var transform = template.geometry().at(BlockPos.ZERO, Direction.NORTH);
		for (int x : List.of(0, 48)) for (int z : List.of(0, 48)) {
			assertFalse(source.unloaded.contains(new ChunkPos(transform.toWorld(new BlockPos(x, 0, z)))));
		}
		var scan = new StructureScan(definition, source.current, BlockPos.ZERO, Direction.NORTH); finish(scan, source, 17);
		assertEquals(SUSPENDED, scan.status()); assertEquals(UNLOADED_CHUNK, scan.diagnostics().getFirst().reason());
		assertEquals(new ChunkPos(1, 1), new ChunkPos(scan.diagnostics().getFirst().world()));
		source.unloaded.clear(); source.mutate();
		var retry = new StructureScan(definition, source.current, BlockPos.ZERO, Direction.NORTH); finish(retry, source, 67);
		assertEquals(MATCHED, retry.status());
	}
	@Test void unresolvedSecondCandidatePreventsPublishingTheFirstMatch() {
		var definition = definition(solid("short", 5, 3, 5), solid("long", 5, 3, 33));
		var source = solid(definition, BlockPos.ZERO); source.unloaded.add(new ChunkPos(0, 1));
		var scan = new StructureScan(definition, source.current, BlockPos.ZERO, Direction.NORTH); finish(scan, source, 11);
		assertEquals(List.of("short"), scan.matchedVariants()); assertEquals(SUSPENDED, scan.status());
		assertTrue(scan.readyMatch(source.current).isEmpty());
	}
	@Test void ambiguousDefinitionsNeverPickAFirstWinner() {
		var definition = definition(solid("first", 5, 3, 5), solid("second", 5, 3, 5));
		var source = solid(definition, CONTROLLER); var scan = scan(definition, source); finish(scan, source, 13);
		assertEquals(AMBIGUOUS, scan.status()); assertEquals(List.of("first", "second"), scan.matchedVariants());
		assertTrue(scan.readyMatch(source.current).isEmpty()); assertEquals(150, source.reads);
	}
	@Test void everyIdentityAndRevisionFieldInvalidatesBeforeBlockQueries() {
		var source = combined(5, CONTROLLER, Direction.NORTH); var original = source.current;
		var changed = List.of(new StructureScanStamp(UUID.randomUUID(), 1, 0, COMBINED.id(), original.layoutVersion()),
				new StructureScanStamp(original.machineId(), 2, 0, COMBINED.id(), original.layoutVersion()),
				new StructureScanStamp(original.machineId(), 1, 1, COMBINED.id(), original.layoutVersion()),
				new StructureScanStamp(original.machineId(), 1, 0, ResourceLocation.parse("test:other"), original.layoutVersion()),
				new StructureScanStamp(original.machineId(), 1, 0, COMBINED.id(), COMBINED.layoutVersion() + 1));
		for (var stamp : changed) {
			source.current = stamp; var scan = new StructureScan(COMBINED, original, CONTROLLER, Direction.NORTH);
			assertEquals(STALE, scan.advance(5, source).status()); assertEquals(0, source.reads); assertEquals(0, source.availabilityChecks);
		}
		var staleDefinition = new StructureScan(new StructureDefinition(COMBINED.id(), COMBINED.layoutVersion() + 1, COMBINED.candidates()), original, CONTROLLER, Direction.NORTH);
		assertEquals(STALE, staleDefinition.status());
	}
	@Test void mutationInLastReadOrBetweenStepsCannotPublishMixedWorldState() {
		var definition = definition(solid("one", 3, 3, 3)); var source = solid(definition, CONTROLLER); var scan = scan(definition, source);
		source.onRead = p -> { if (source.reads == 27) source.mutate(); };
		finish(scan, source, 27); assertEquals(STALE, scan.status()); assertTrue(scan.readyMatch(source.current).isEmpty());
		var next = scan(definition, source); next.advance(1, source); source.mutate(); int reads = source.reads;
		assertEquals(STALE, next.advance(1, source).status()); assertEquals(reads, source.reads);
	}
	@Test void completedMatchRequiresCurrentStampAndIsNotVisibleInsideValidationCallback() {
		var definition = definition(solid("one", 3, 3, 3)); var source = solid(definition, CONTROLLER); var scan = scan(definition, source);
		source.onStamp = () -> assertTrue(scan.readyMatch(source.current).isEmpty());
		finish(scan, source, 27); assertTrue(scan.readyMatch(source.current).isPresent());
		source.onStamp = () -> { }; source.mutate(); assertTrue(scan.readyMatch(source.current).isEmpty());
		assertEquals(STALE, scan.advance(1, source).status());
	}
	@Test void worldBoundsAndCoordinateOverflowRefuseReadsWithDifferentDiagnostics() {
		var definition = definition(COMBINED.candidates().getFirst()); var source = combined(5, CONTROLLER, Direction.NORTH);
		source.outside = true; var outside = scan(definition, source); finish(outside, source, 1);
		assertEquals(INVALID, outside.status()); assertEquals(OUTSIDE_WORLD, outside.diagnostics().getFirst().reason()); assertEquals(0, source.reads);
		source.outside = false;
		var overflow = new StructureScan(definition, source.current, new BlockPos(Integer.MAX_VALUE, 60, 0), Direction.NORTH);
		finish(overflow, source, 1); assertEquals(COORDINATE_OVERFLOW, overflow.diagnostics().getFirst().reason()); assertEquals(0, source.reads);
		assertThrows(IllegalArgumentException.class, () -> new StructureScan(definition, source.current, CONTROLLER, Direction.UP));
	}
	@Test void queryExceptionsAndReentryFailClosedWithoutAutomaticRetry() {
		var definition = definition(solid("one", 3, 3, 3)); var source = solid(definition, CONTROLLER); var scan = scan(definition, source);
		source.onRead = p -> { throw new IllegalStateException("fixture read failed"); };
		assertEquals(FAILED, scan.advance(1, source).status()); assertTrue(scan.failure().isPresent());
		assertEquals(QUERY_FAILED, scan.diagnostics().getFirst().reason()); int reads = source.reads;
		scan.advance(5, source); assertEquals(reads, source.reads);
		var reentry = scan(definition, source); source.onRead = p -> reentry.advance(1, source);
		assertEquals(FAILED, reentry.advance(1, source).status()); assertTrue(reentry.readyMatch(source.current).isEmpty());
	}
	@Test void cancellationInQueryCallbacksStopsFurtherWorkAndCannotBeReopened() {
		var definition = definition(solid("one", 3, 3, 3));
		for (int phase = 0; phase < 3; phase++) {
			var source = solid(definition, CONTROLLER); var scan = scan(definition, source);
			if (phase == 0) source.onStamp = () -> { scan.cancel(); source.mutate(); };
			if (phase == 1) source.onAvailability = p -> scan.cancel();
			if (phase == 2) source.onRead = p -> scan.cancel();
			assertEquals(CANCELLED, scan.advance(27, source).status()); assertTrue(source.reads <= 1);
			int reads = source.reads, checks = source.stampChecks;
			scan.advance(27, source); assertEquals(reads, source.reads); assertEquals(checks, source.stampChecks);
			assertTrue(scan.readyMatch(source.current).isEmpty());
		}
	}
}
