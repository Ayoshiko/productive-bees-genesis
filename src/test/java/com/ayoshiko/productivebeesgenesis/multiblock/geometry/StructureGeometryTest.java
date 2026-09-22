package com.ayoshiko.productivebeesgenesis.multiblock.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureGeometryTest {
	@Test void offCenterCoreAndCullingBoundsFollowTheSameRotation() {
		var geometry = new StructureGeometry(new StructureSize(5, 4, 7), new BlockPos(1, 0, 0),
				new Vec3(2.5, 2, 4.5), new AABB(1, 0.5, 3, 4, 3.5, 6));
		var controller = new BlockPos(-100, 40, 200);
		assertEquals(new Vec3(-103.5, 42, 201.5), geometry.coreCenterAt(controller, Direction.EAST));
		assertEquals(new AABB(-105, 40, 200, -99, 43.5, 203), geometry.renderBoundsAt(controller, Direction.EAST));
		for (var facing : Direction.Plane.HORIZONTAL) {
			var bounds = geometry.renderBoundsAt(controller, facing);
			assertTrue(bounds.contains(geometry.coreCenterAt(controller, facing)));
			assertTrue(bounds.contains(Vec3.atCenterOf(controller)));
			var occupied = geometry.occupiedBoundsAt(controller, facing);
			for (long i = 0; i < geometry.size().volume(); i++) {
				assertTrue(occupied.contains(Vec3.atCenterOf(geometry.at(controller, facing).toWorld(geometry.size().positionAt(i)))));
			}
		}
	}
	@Test void evenSizedCoreMayLieBetweenBlocksAndVisualBoundsMayExtendOutsideShell() {
		var anchor = new BlockPos.MutableBlockPos(1, 0, 0);
		var geometry = new StructureGeometry(new StructureSize(4, 4, 6), anchor,
				new Vec3(2, 2, 3), new AABB(-1, -1, -1, 5, 5, 7));
		anchor.set(3, 3, 5);
		assertEquals(new BlockPos(1, 0, 0), geometry.controllerAnchor());
		assertEquals(new Vec3(1, 2, 3), geometry.coreCenterAt(BlockPos.ZERO, Direction.NORTH));
		assertEquals(new Vec3(1, 2, 3), geometry.at(BlockPos.ZERO, Direction.NORTH).toWorldPoint(geometry.coreCenter()));
	}
	@Test void malformedDefinitionsAndUnrepresentableWorldBoundsAreRejected() {
		var size = new StructureSize(3, 4, 5);
		var center = new Vec3(1.5, 2, 2.5);
		assertThrows(IllegalArgumentException.class, () -> new StructureGeometry(size, new BlockPos(3, 0, 0), center, size.bounds()));
		assertThrows(IllegalArgumentException.class, () -> new StructureGeometry(size, BlockPos.ZERO, new Vec3(3, 2, 2), size.bounds()));
		assertThrows(IllegalArgumentException.class, () -> new StructureGeometry(size, BlockPos.ZERO, center, new AABB(0, 0, 0, 1, 1, 1)));
		assertThrows(IllegalArgumentException.class, () -> new StructureGeometry(size, BlockPos.ZERO, center, new AABB(0, 0, 0, Double.POSITIVE_INFINITY, 5, 5)));
		var geometry = new StructureGeometry(size, BlockPos.ZERO, center, size.bounds());
		assertThrows(ArithmeticException.class, () -> geometry.at(new BlockPos(Integer.MAX_VALUE, 0, 0), Direction.NORTH));
		assertThrows(ArithmeticException.class, () -> geometry.at(new BlockPos(0, Integer.MAX_VALUE, 0), Direction.NORTH));
	}
}
