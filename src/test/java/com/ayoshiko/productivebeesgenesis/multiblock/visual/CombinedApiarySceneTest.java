package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombinedApiarySceneTest {
	@Test void propsFitAirCellsAndFiniteBoundsInEveryLayoutAndDirection() {
		var templates = CombinedApiaryDefinition.DEFINITION.candidates();
		for (int variant = 0; variant < templates.size(); variant++) {
			var template = templates.get(variant);
			var props = CombinedApiaryScene.props(variant);
			assertEquals(3, props.size());
			assertEquals(3, props.stream().map(CombinedApiaryScene.Prop::kind).distinct().count());
			for (var prop : props) {
				var bounds = prop.bounds();
				for (long i = 0; i < template.geometry().size().volume(); i++) {
					var local = template.geometry().size().positionAt(i);
					if (bounds.intersects(new net.minecraft.world.phys.AABB(local))) {
						assertEquals(Set.of(StructureRole.AIR), template.cellAt(local).roles());
					}
				}
				for (var facing : Direction.Plane.HORIZONTAL) {
					var controller = new BlockPos(-231, 81, 407);
					var transform = template.geometry().at(controller, facing);
					var world = transform.toWorldBounds(bounds);
					var render = template.geometry().renderBoundsAt(controller, facing);
					assertEquals(world, world.intersect(render));
					var recovered = transform.toLocalPoint(transform.toWorldPoint(prop.center()));
					assertEquals(0, recovered.distanceTo(prop.center()), 1e-9);
				}
			}
			for (int i = 0; i < props.size(); i++) for (int j = i + 1; j < props.size(); j++) {
				assertFalse(props.get(i).bounds().intersects(props.get(j).bounds()));
			}
		}
	}
	@Test void inactiveAndUnknownLayoutsHaveNoProps() {
		assertTrue(CombinedApiaryScene.props(-1).isEmpty());
		assertTrue(CombinedApiaryScene.props(3).isEmpty());
	}
}
