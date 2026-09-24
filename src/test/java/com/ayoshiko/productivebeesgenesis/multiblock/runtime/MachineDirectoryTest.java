package com.ayoshiko.productivebeesgenesis.multiblock.runtime;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScan;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.Formation.*;
import static com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.State.VALIDATING;
import static com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.State.RECOVERY;
import static com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.State.REBUILDING;
import static com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.State.SUSPENDED;
import static com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.State.REMOVED;
import static org.junit.jupiter.api.Assertions.*;

class MachineDirectoryTest {
	private final MachineDirectory directory = new MachineDirectory(CombinedApiaryDefinition.DEFINITION);
	private MachineDirectory.Handle attach(int x) { return directory.attach(UUID.randomUUID(), 1, new BlockPos(x, 64, 0), Direction.NORTH); }
	private StructureScan.Match candidate(MachineDirectory.Handle handle) {
		assertTrue(directory.beginValidation(handle));
		return new StructureScan.Match(handle.stamp(), CombinedApiaryDefinition.DEFINITION.candidates().getFirst(), handle.controller(), handle.facing());
	}
	private MachineDirectory.Binding form(MachineDirectory.Handle handle) {
		assertEquals(FORMED, directory.form(handle, candidate(handle), ignored -> { }));
		return handle.binding().orElseThrow();
	}
	@Test void bindingsBecomeActiveOnlyAfterAllReferencesAreBound() {
		var handle = attach(0); var match = candidate(handle);
		assertEquals(FORMED, directory.form(handle, match, binding -> {
			assertFalse(directory.active(binding)); assertTrue(handle.binding().isEmpty()); assertEquals(VALIDATING, handle.state());
		}));
		var binding = handle.binding().orElseThrow(); assertTrue(directory.active(binding));
		assertFalse(directory.active(new MachineDirectory.Binding(handle, binding.stamp(), binding.region(), binding.variant())));
		assertEquals(STALE, directory.form(handle, match, ignored -> fail("Replayed formation")));
	}
	@Test void adjacentMachinesCoexistButSharedWallQuarantinesBothInEitherOrder() {
		var first = attach(0); var adjacent = attach(7); var firstBinding = form(first); var adjacentBinding = form(adjacent);
		assertTrue(directory.active(firstBinding)); assertTrue(directory.active(adjacentBinding)); directory.clear();
		for (boolean reversed : new boolean[]{false, true}) {
			first = attach(0); var second = attach(6);
			var initial = reversed ? second : first; var later = reversed ? first : second;
			var old = form(initial);
			assertEquals(CONFLICT, directory.form(later, candidate(later), ignored -> fail("Conflicting parts bound")));
			assertFalse(directory.active(old)); assertEquals(RECOVERY, first.state()); assertEquals(RECOVERY, second.state());
			assertFalse(directory.beginValidation(first)); assertFalse(directory.beginValidation(second)); directory.clear();
		}
	}
	@Test void indexedEventsIncludeAirAndAllCandidateDepthsButFilterUnrelatedPositions() {
		var handle = attach(15); var binding = form(handle);
		var insideAir = new BlockPos(13, 65, 1);
		assertEquals(java.util.List.of(handle), directory.affectedAt(insideAir));
		assertEquals(java.util.List.of(handle), directory.affectedAt(new BlockPos(15, 65, 8)));
		assertTrue(directory.affectedAt(new BlockPos(15, 70, 8)).isEmpty());
		assertTrue(directory.affectedChunk(new ChunkPos(1, 0)).contains(handle));
		for (var affected : directory.affectedAt(insideAir)) directory.invalidate(affected, REBUILDING);
		assertFalse(directory.active(binding)); assertNotEquals(binding.stamp(), handle.stamp());
		assertTrue(directory.active(form(handle)));
	}
	@Test void duplicateIdsInvalidateExistingBindingAndRecoverOnlyAfterDuplicateRemoval() {
		var first = attach(0); var binding = form(first);
		var duplicate = directory.attach(first.id(), 1, new BlockPos(100, 64, 0), Direction.NORTH);
		assertEquals(RECOVERY, first.state()); assertEquals(RECOVERY, duplicate.state()); assertFalse(directory.active(binding));
		assertFalse(directory.beginValidation(first)); directory.remove(duplicate);
		assertEquals(REBUILDING, first.state()); assertTrue(directory.active(form(first)));
	}
	@Test void unloadReplacementAndRebuildCannotReactivateOldBindingsOrScans() {
		var oldHandle = attach(0); var oldMatch = candidate(oldHandle);
		assertEquals(FORMED, directory.form(oldHandle, oldMatch, ignored -> { })); var oldBinding = oldHandle.binding().orElseThrow();
		directory.remove(oldHandle);
		var replacement = directory.attach(oldHandle.id(), 1, oldHandle.controller(), Direction.NORTH);
		assertEquals(STALE, directory.form(oldHandle, oldMatch, ignored -> fail()));
		assertFalse(directory.current(oldHandle)); assertFalse(directory.active(oldBinding));
		assertNotEquals(oldMatch.stamp(), replacement.stamp());
		var newBinding = form(replacement); assertTrue(directory.active(newBinding));
		directory.invalidate(replacement, SUSPENDED); assertFalse(directory.active(newBinding));
		var stale = new StructureScan.Match(newBinding.stamp(), oldMatch.template(), replacement.controller(), replacement.facing());
		assertTrue(directory.beginValidation(replacement)); assertEquals(STALE, directory.form(replacement, stale, ignored -> fail()));
	}
	@Test void aNewDirectoryCannotAcceptAnOldMatchEvenWithSameSavedIdentity() {
		var handle = attach(0); var match = candidate(handle); directory.clear();
		var other = new MachineDirectory(CombinedApiaryDefinition.DEFINITION);
		var restored = other.attach(handle.id(), 1, handle.controller(), handle.facing()); assertTrue(other.beginValidation(restored));
		assertEquals(STALE, other.form(restored, match, ignored -> fail()));
	}
	@Test void callbackChangesRemovalAndReentryCannotPublishHalfFormedMachines() {
		var first = attach(0); var second = attach(100); var firstMatch = candidate(first); var secondMatch = candidate(second);
		assertEquals(STALE, directory.form(first, firstMatch, binding -> {
			assertEquals(BUSY, directory.form(second, secondMatch, ignored -> fail()));
			directory.invalidate(first, REBUILDING);
		}));
		assertTrue(first.binding().isEmpty());
		assertEquals(STALE, directory.form(first, candidate(first), binding -> directory.remove(first)));
		assertEquals(REMOVED, first.state());
		assertEquals(FORMED, directory.form(second, secondMatch, ignored -> { }));
	}
	@Test void failedBindingLeavesInactiveReferenceAndExplicitFailure() {
		var handle = attach(0); var reference = new AtomicReference<MachineDirectory.Binding>();
		assertEquals(FAILED, directory.form(handle, candidate(handle), binding -> {
			reference.set(binding); throw new IllegalStateException("fixture failure");
		}));
		assertFalse(directory.active(reference.get())); assertEquals(RECOVERY, handle.state()); assertTrue(handle.failure().isPresent());
		directory.invalidate(handle, REBUILDING); assertTrue(directory.active(form(handle)));
	}
	@Test void positionFacingAndDefinitionMismatchesAreRejected() {
		var handle = attach(0); var match = candidate(handle);
		var moved = new StructureScan.Match(match.stamp(), match.template(), handle.controller().above(), handle.facing());
		assertEquals(STALE, directory.form(handle, moved, ignored -> fail()));
		var rotated = new StructureScan.Match(match.stamp(), match.template(), handle.controller(), Direction.EAST);
		assertEquals(STALE, directory.form(handle, rotated, ignored -> fail()));
		directory.invalidate(handle, REBUILDING); assertEquals(STALE, directory.form(handle, match, ignored -> fail()));
		assertThrows(IllegalArgumentException.class, () -> directory.attach(UUID.randomUUID(), 1, handle.controller(), Direction.NORTH));
	}
	@Test void indicesAreReleasedAndCallsFromAnotherThreadCannotMutateDirectory() {
		var handle = attach(0); var binding = form(handle);
		CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> directory.remove(handle))).join();
		assertTrue(directory.active(binding)); directory.clear();
		assertFalse(directory.active(binding)); assertEquals(0, directory.size()); assertEquals(0, directory.indexedSections()); assertEquals(0, directory.indexedChunks());
		assertTrue(directory.affectedAt(handle.controller()).isEmpty());
	}
	@Test void integerBoundsFreezeAndDistinguishContactFromOverlap() {
		var min = new BlockPos.MutableBlockPos(-17, -1, -17); var region = new MachineRegion(min, new BlockPos(-1, 1, -1)); min.set(0, 0, 0);
		assertTrue(region.contains(new BlockPos(-16, 0, -16))); assertEquals(8, region.sections().size());
		assertFalse(region.intersects(new MachineRegion(new BlockPos(0, -1, -17), new BlockPos(2, 1, -1))));
		assertTrue(region.intersects(new MachineRegion(new BlockPos(-1, -1, -17), new BlockPos(2, 1, -1))));
		assertThrows(IllegalArgumentException.class, () -> new MachineRegion(BlockPos.ZERO, new BlockPos(100000, 100000, 100000)).sections());
	}
}
