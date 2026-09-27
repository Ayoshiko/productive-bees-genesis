package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineCoreSceneTest {
	@Test void centralHiveAndSatelliteEnvelopesFitEveryLayout() {
		var templates = CombinedApiaryDefinition.DEFINITION.candidates();
		for (int variant = 0; variant < templates.size(); variant++) {
			var geometry = templates.get(variant).geometry(); var space = MachineCoreScene.space(variant);
			assertEquals(geometry.coreCenter(), space.center());
			double radius = space.bodyRadius();
			var core = new AABB(space.center().subtract(radius, radius, radius), space.center().add(radius, radius, radius));
			assertEquals(core, core.intersect(space.bounds()));
			for (int tick = 0; tick < 720; tick++) for (int bee = 0; bee < 5; bee++) {
				var position = space.point(MachineCoreScene.atTime(MachineCoreScene.beeTime(tick, 0.5F, bee, 0), bee).edge(1));
				double wingRadius = space.beeScale() * 0.45;
				var box = new AABB(position.subtract(wingRadius, wingRadius, wingRadius), position.add(wingRadius, wingRadius, wingRadius));
				assertEquals(box, box.intersect(space.bounds()));
				assertTrue(position.distanceTo(space.center()) > radius + wingRadius, "Satellite intersects the central hive");
			}
			for (var facing : Direction.Plane.HORIZONTAL) {
				var pos = new BlockPos(-231, 81, 407);
				var box = geometry.at(pos, facing).toWorldBounds(space.bounds());
				assertEquals(box, box.intersect(geometry.renderBoundsAt(pos, facing)));
			}
		}
	}
	@Test void mobiusEdgesSwapAfterOneLapAndCloseAfterTwo() {
		for (int bee = 0; bee < 5; bee++) for (int tick = 0; tick < 360; tick += 7) {
			var a = MachineCoreScene.atTime(tick + 0.25, bee); var b = MachineCoreScene.atTime(tick + 360.25, bee);
			assertEquals(0, a.center().distanceTo(b.center()), 1e-12);
			assertEquals(0, a.across().add(b.across()).length(), 1e-12);
			assertEquals(0, a.edge(1).distanceTo(b.edge(-1)), 1e-12);
			assertEquals(a, MachineCoreScene.atTime(tick + 720.25, bee));
			assertEquals(0.105, a.across().length(), 1e-12);
		}
		assertEquals(360, MachineCoreScene.TRAIL_TICKS);
		assertTrue(MachineCoreScene.trailAlpha(120) > 0.6F);
		assertEquals(0, MachineCoreScene.trailAlpha(360));
	}
	@Test void fiveBeesStaySeparatedAndFlyOppositeTheCoreSpin() {
		for (int tick = 1; tick < 700; tick += 3) {
			assertTrue(MachineCoreScene.bodyAngle(tick + 1, 0) > MachineCoreScene.bodyAngle(tick, 0));
			for (int bee = 0; bee < 5; bee++) {
				var a = MachineCoreScene.atTime(tick, bee); var b = MachineCoreScene.atTime(tick + 1, bee);
				assertTrue(a.edge(1).cross(b.edge(1)).dot(MachineCoreScene.orbitNormal(bee)) < 0);
				assertTrue(MachineCoreScene.orbitNormal(bee).z > 0);
				assertTrue(b.edge(1).subtract(a.edge(1)).normalize().dot(a.tangent()) > 0.999);
			}
			for (int i = 0; i < 5; i++) for (int j = i + 1; j < 5; j++) {
				var first = MachineCoreScene.atTime(MachineCoreScene.beeTime(tick, 0, i, 0), i).edge(1);
				var second = MachineCoreScene.atTime(MachineCoreScene.beeTime(tick, 0, j, 0), j).edge(1);
				assertTrue(first.distanceTo(second) > 0.70, "Satellite phases collided");
				assertTrue(MachineCoreScene.orbitNormal(i).distanceTo(MachineCoreScene.orbitNormal(j)) > 0.45);
			}
		}
	}
	@Test void pauseRewindAndLongWorldsRemainStatelessAndPrecise() {
		for (long tick : new long[]{0, 359, 360, 719, 720, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
			assertEquals(MachineCoreScene.sample(tick, 0.5F), MachineCoreScene.sample(tick, 0.5F));
			assertEquals(0.755, MachineCoreScene.sample(tick, 0.5F).center().length(), 1e-12);
		}
		assertNotEquals(MachineCoreScene.sample(Long.MAX_VALUE, 0), MachineCoreScene.sample(Long.MAX_VALUE, 0.5F));
		var before = MachineCoreScene.sample(100, 0); MachineCoreScene.sample(100000, 0);
		assertEquals(before, MachineCoreScene.sample(100, 0));
		var fallback = MachineCoreScene.sample(0, 0);
		assertEquals(fallback, MachineCoreScene.sample(-1, 0));
		for (float partial : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -0.1F, 1.1F}) assertEquals(fallback, MachineCoreScene.sample(5, partial));
	}
}
