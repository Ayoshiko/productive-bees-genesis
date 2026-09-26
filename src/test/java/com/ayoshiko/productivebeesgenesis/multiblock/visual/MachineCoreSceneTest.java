package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineCoreSceneTest {
	@Test void spacesFitEveryTemplateAndWorldOrientation() {
		var templates = CombinedApiaryDefinition.DEFINITION.candidates();
		for (int variant = 0; variant < templates.size(); variant++) {
			var geometry = templates.get(variant).geometry();
			var space = MachineCoreScene.space(variant);
			assertEquals(geometry.coreCenter(), space.center());
			assertTrue(space.radius().x > 0 && space.radius().y > 0 && space.radius().z > 0);
			assertEquals(space.bounds(), space.bounds().intersect(geometry.visualBounds()));
			for (var facing : Direction.Plane.HORIZONTAL) {
				var pos = new BlockPos(-231, 81, 407);
				var box = geometry.at(pos, facing).toWorldBounds(space.bounds());
				assertEquals(box, box.intersect(geometry.renderBoundsAt(pos, facing)));
			}
		}
	}
	@Test void phasesStayFiniteAndDeterministicAtLargeTimesAndAfterRewind() {
		for (long tick : new long[]{0, 79, 80, 1439, 1440, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
			var motion = MachineCoreScene.sample(tick, 0.5F);
			assertEquals(motion, MachineCoreScene.sample(tick, 0.5F));
			assertTrue(Float.isFinite(motion.yaw()) && motion.yaw() >= 0 && motion.yaw() <= 360);
			assertTrue(motion.axis() >= 0 && motion.axis() <= 2);
			assertTrue(motion.layer() >= -1 && motion.layer() <= 1);
			assertTrue(Math.abs(motion.turn()) <= 90 && motion.pulse() >= 0.69F && motion.pulse() <= 1);
		}
		assertNotEquals(MachineCoreScene.sample(Long.MAX_VALUE, 0), MachineCoreScene.sample(Long.MAX_VALUE, 0.5F));
		var before = MachineCoreScene.sample(100, 0);
		MachineCoreScene.sample(100000, 0);
		assertEquals(before, MachineCoreScene.sample(100, 0));
	}
	@Test void invalidTimeFallsBackWithoutRetainingAnyState() {
		var fallback = MachineCoreScene.sample(0, 0);
		assertEquals(fallback, MachineCoreScene.sample(-1, 0));
		for (float partial : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -0.1F, 1.1F}) {
			assertEquals(fallback, MachineCoreScene.sample(5, partial));
		}
	}
}
