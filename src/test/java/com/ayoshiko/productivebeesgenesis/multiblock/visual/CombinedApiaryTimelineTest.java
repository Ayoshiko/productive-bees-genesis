package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineVisualState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombinedApiaryTimelineTest {
	static MachineActivityEvent event(int variant, MachineActivityEvent.Activity activity) {
		return new MachineActivityEvent(new MachineVisualSnapshot(1, new UUID(0, 1), 1, variant, Direction.NORTH, MachineVisualState.READY),
				1, 100, 77, activity, IntStream.range(0, 8).mapToObj(i -> new MachineActivityEvent.Appearance(
						MachineActivityEvent.ResourceKind.ITEM, ResourceLocation.parse("minecraft:sample_" + i))).toList());
	}
	@Test void deterministicStagesAndPartialTicksDoNotAccumulateOrReadWallClock() {
		var e = event(0, MachineActivityEvent.Activity.COMBINED);
		var times = new double[] {0, 30, 55, 85, 95, 125, 160};
		var stages = new CombinedApiaryTimeline.Stage[] {CombinedApiaryTimeline.Stage.FLY_IN, CombinedApiaryTimeline.Stage.HIVE_FADE,
				CombinedApiaryTimeline.Stage.PRESS, CombinedApiaryTimeline.Stage.BURST, CombinedApiaryTimeline.Stage.HOVER,
				CombinedApiaryTimeline.Stage.COLLECT, CombinedApiaryTimeline.Stage.REST};
		for (int i = 0; i < times.length; i++) assertEquals(stages[i], CombinedApiaryTimeline.sample(e, times[i]).stage());
		var pose = CombinedApiaryTimeline.sample(e, 10.25);
		CombinedApiaryTimeline.sample(e, 140);
		assertEquals(pose, CombinedApiaryTimeline.sample(e, 10.25));
		assertNotEquals(pose.bee(), CombinedApiaryTimeline.sample(e, 10.75).bee());
		for (double invalid : new double[] {-1, 160, 1e20, Double.NaN, Double.POSITIVE_INFINITY})
			assertEquals(CombinedApiaryTimeline.Stage.REST, CombinedApiaryTimeline.sample(e, invalid).stage());
	}
	@Test void activityFragmentsDoNotInventOtherWork() {
		var apiary = event(0, MachineActivityEvent.Activity.APIARY);
		assertEquals(CombinedApiaryTimeline.Stage.REST, CombinedApiaryTimeline.sample(apiary, 55).stage());
		assertTrue(CombinedApiaryTimeline.sample(apiary, 54).icons().isEmpty());
		var centrifuge = CombinedApiaryTimeline.sample(event(0, MachineActivityEvent.Activity.CENTRIFUGE), 0);
		assertEquals(CombinedApiaryTimeline.Stage.PRESS, centrifuge.stage());
		assertEquals(0, centrifuge.bee().alpha()); assertEquals(1, centrifuge.hive().alpha());
	}
	@Test void everyVisiblePoseStaysInAirAndFiniteBoundsForAllLayoutsAndFacings() {
		for (int variant = 0; variant < 3; variant++) {
			var template = CombinedApiaryDefinition.DEFINITION.candidates().get(variant);
			for (var activity : MachineActivityEvent.Activity.values()) {
				var e = event(variant, activity);
				for (double time = 0; time <= activity.duration(); time += 0.5) {
					var frame = CombinedApiaryTimeline.sample(e, time);
					var poses = new ArrayList<>(List.of(frame.bee(), frame.hive(), frame.comb(), frame.chest(), frame.liquid(), frame.ring()));
					assertTrue(frame.icons().size() <= MachineActivityEvent.MAX_ICONS);
					frame.icons().forEach(icon -> poses.add(icon.pose()));
					for (var pose : poses) {
						assertTrue(pose.alpha() >= 0 && pose.alpha() <= 1);
						assertTrue(pose.scale().x > 0 && pose.scale().y > 0 && pose.scale().z > 0);
						if (pose.alpha() == 0) continue;
						var bounds = pose.bounds();
						for (long i = 0; i < template.geometry().size().volume(); i++) {
							var pos = template.geometry().size().positionAt(i);
							if (bounds.intersects(new AABB(pos))) assertEquals(Set.of(StructureRole.AIR), template.cellAt(pos).roles(), "time=" + time + " pose=" + pose);
						}
						for (var facing : Direction.Plane.HORIZONTAL) {
							var origin = new BlockPos(-20, 90, 31);
							var world = template.geometry().at(origin, facing).toWorldBounds(bounds);
							assertEquals(world, world.intersect(template.geometry().renderBoundsAt(origin, facing)));
						}
					}
				}
			}
		}
	}
	@Test void descriptionsAreImmutableBoundedAndRejectInvalidIdentities() {
		var e = event(0, MachineActivityEvent.Activity.COMBINED);
		var input = new ArrayList<>(e.resources());
		var copy = new MachineActivityEvent(e.structure(), 1, 0, 0, e.activity(), input);
		input.clear(); assertEquals(8, copy.resources().size());
		assertThrows(UnsupportedOperationException.class, () -> copy.resources().clear());
		assertThrows(IllegalArgumentException.class, () -> new MachineActivityEvent(e.structure(), 0, 0, 0, e.activity(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new MachineActivityEvent(e.structure(), 1, -1, 0, e.activity(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new MachineActivityEvent(e.structure(), 1, 0, 0, e.activity(), List.of(e.resources().getFirst(), e.resources().getFirst())));
		assertThrows(IllegalArgumentException.class, () -> new MachineActivityEvent(e.structure(), 1, 0, 0, e.activity(), java.util.Collections.nCopies(9, e.resources().getFirst())));
		assertThrows(IllegalArgumentException.class, () -> new MachineActivityEvent.Appearance(MachineActivityEvent.ResourceKind.ITEM, ResourceLocation.parse("a:" + "b".repeat(129))));
		var inactive = new MachineVisualSnapshot(2, new UUID(0, 1), 1, -1, Direction.NORTH, MachineVisualState.UNFORMED);
		assertThrows(IllegalArgumentException.class, () -> new MachineActivityEvent(inactive, 1, 0, 0, e.activity(), List.of()));
	}
}
