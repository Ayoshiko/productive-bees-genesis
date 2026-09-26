package com.ayoshiko.productivebeesgenesis.multiblock.definition;

import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureSize.Region;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole.*;
import static org.junit.jupiter.api.Assertions.*;

class CombinedApiaryDefinitionTest {
	private final StructureDefinition definition = CombinedApiaryDefinition.DEFINITION;
	@Test void eachDepthHasExactlyOneCoreAndTwoWorkUnitsWithReservedAir() {
		assertEquals(List.of(5, 7, 9, 5, 7, 9), definition.candidates().stream().map(t -> t.geometry().size().depth()).toList());
		for (var template : definition.candidates()) {
			var size = template.geometry().size();
			assertEquals(7, size.width()); assertTrue(size.height() == 5 || size.height() == 7);
			var counts = new EnumMap<StructureRole, Integer>(StructureRole.class);
			for (long i = 0; i < size.volume(); i++) for (var role : template.cellAt(size.positionAt(i)).roles()) counts.merge(role, 1, Integer::sum);
			for (var role : List.of(CONTROLLER, CORE, APIARY_UNIT, CENTRIFUGE_UNIT, INTERFACE, ENERGY_PORT, INPUT_PORT, OUTPUT_PORT)) {
				assertEquals(1, counts.get(role));
			}
			assertEquals(8 + 4 * ((7 - 2) + (size.height() - 2) + (size.depth() - 2)), counts.get(FRAME));
			assertEquals((7 - 2) * (size.height() - 2) * (size.depth() - 2) - 3, counts.get(AIR));
			assertFalse(counts.containsKey(UPGRADE)); assertFalse(counts.containsKey(NETWORK_PORT));
			assertEquals(Set.of(AIR), template.cellAt(new BlockPos(3, size.height() / 2 + 1, size.depth() / 2)).roles());
		}
	}
	@Test void originalLayoutIndicesAndAnchorsRemainStable() {
		assertEquals(List.of("7x5x5", "7x5x7", "7x5x9", "7x7x5", "7x7x7", "7x7x9"),
				definition.candidates().stream().map(StructureTemplate::variant).toList());
		for (var template : definition.candidates()) {
			assertEquals(new BlockPos(3, 1, 0), template.geometry().controllerAnchor());
			assertEquals(Set.of(CORE), template.cellAt(BlockPos.containing(template.geometry().coreCenter())).roles());
			assertEquals(template.geometry().size().height() / 2 + 0.5, template.geometry().coreCenter().y);
		}
	}
	@Test void fullRectangleSeparatesFrameFacesAndInteriorWithoutWildcardCells() {
		var template = definition.candidates().getFirst();
		assertEquals(Set.of(FRAME), template.cellAt(new BlockPos(0, 0, 0)).roles());
		assertEquals(Set.of(FRAME), template.cellAt(new BlockPos(0, 2, 0)).roles());
		assertEquals(Set.of(CASING, GLASS), template.cellAt(new BlockPos(6, 2, 2)).roles());
		assertEquals(Set.of(AIR), template.cellAt(new BlockPos(1, 2, 1)).roles());
		assertThrows(IllegalArgumentException.class, () -> template.cellAt(new BlockPos(7, 0, 0)));
	}
	@Test void portAndControllerDirectionsRotateWithTheSameAnchor() {
		for (var template : definition.candidates()) for (var facing : Direction.Plane.HORIZONTAL) {
			var transform = template.geometry().at(new BlockPos(-20, 65, -30), facing);
			assertEquals(facing, transform.toWorldDirection(template.cellAt(template.geometry().controllerAnchor()).facing()));
			assertEquals(facing.getOpposite(), transform.toWorldDirection(template.cellAt(new BlockPos(2, 1, template.geometry().size().depth() - 1)).facing()));
			assertEquals(facing.getCounterClockWise(), transform.toWorldDirection(template.cellAt(new BlockPos(0, 1, 2)).facing()));
		}
	}
	@Test void templateFreezesMutableFeaturePositionsAndRejectsDuplicateCore() {
		var template = definition.candidates().getFirst();
		var features = new ConcurrentHashMap<>(template.features());
		var mutable = new BlockPos.MutableBlockPos(1, 1, 1); features.put(mutable, StructureCell.of(GLASS));
		var frozen = new StructureTemplate("frozen", template.geometry(), template.regions(), features);
		mutable.set(1, 2, 1); features.clear();
		assertEquals(Set.of(GLASS), frozen.cellAt(new BlockPos(1, 1, 1)).roles());
		assertThrows(UnsupportedOperationException.class, () -> frozen.features().clear());
		features.putAll(template.features()); features.put(new BlockPos(1, 1, 1), StructureCell.of(CORE));
		assertThrows(IllegalArgumentException.class, () -> new StructureTemplate("bad", template.geometry(), template.regions(), features));
	}
	@Test void incompleteOrOptionalAuthorityRulesAreRejected() {
		var template = definition.candidates().getFirst();
		var regions = new EnumMap<>(template.regions()); regions.remove(Region.FACE);
		assertThrows(IllegalArgumentException.class, () -> new StructureTemplate("missing", template.geometry(), regions, template.features()));
		regions.put(Region.FACE, StructureCell.of(CORE));
		assertThrows(IllegalArgumentException.class, () -> new StructureTemplate("repeated", template.geometry(), regions, template.features()));
		var features = new ConcurrentHashMap<>(template.features());
		features.put(template.geometry().controllerAnchor(), new StructureCell(Set.of(CONTROLLER, CASING), Direction.NORTH));
		assertThrows(IllegalArgumentException.class, () -> new StructureTemplate("optional", template.geometry(), template.regions(), features));
		assertThrows(IllegalArgumentException.class, () -> new StructureCell(Set.of(AIR, CASING), null));
	}
	@Test void definitionRejectsInvalidVersionDuplicateNamesAndUnboundedCandidates() {
		var template = definition.candidates().getFirst();
		assertThrows(IllegalArgumentException.class, () -> new StructureDefinition(definition.id(), 0, List.of(template)));
		assertThrows(IllegalArgumentException.class, () -> new StructureDefinition(definition.id(), 1, List.of(template, template)));
		assertThrows(IllegalArgumentException.class, () -> new StructureDefinition(definition.id(), 1, java.util.Collections.nCopies(17, template)));
		assertThrows(UnsupportedOperationException.class, () -> definition.candidates().clear());
	}
}
