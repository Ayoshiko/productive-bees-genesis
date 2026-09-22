package com.ayoshiko.productivebeesgenesis.multiblock.geometry;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureTransformTest {
	@Test void everyFacingUsesTheSameControllerAndCellCenters() {
		var anchor = new BlockPos(1, 1, 0);
		var controller = new BlockPos(-20, -40, -60);
		var local = new BlockPos(3, 4, 5);
		var offsets = new int[][] {{2, 3, 5}, {-5, 3, 2}, {-2, 3, -5}, {5, 3, -2}};
		Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
		for (int i = 0; i < facings.length; i++) {
			var transform = new StructureTransform(controller, anchor, facings[i]);
			var expected = controller.offset(offsets[i][0], offsets[i][1], offsets[i][2]);
			assertEquals(controller, transform.toWorld(anchor));
			assertEquals(expected, transform.toWorld(local));
			assertEquals(local, transform.toLocal(expected));
			assertEquals(Vec3.atCenterOf(expected), transform.toWorldPoint(Vec3.atCenterOf(local)));
			assertEquals(facings[i], transform.toWorldDirection(Direction.NORTH));
			assertEquals(Direction.UP, transform.toWorldDirection(Direction.UP));
			assertEquals(Direction.DOWN, transform.toWorldDirection(Direction.DOWN));
		}
	}
	@Test void seededRectanglesRoundTripAndStayInsideRotatedBounds() {
		var random = new Random(20260922);
		for (int i = 0; i < 500; i++) {
			var size = new StructureSize(1 + random.nextInt(50), 1 + random.nextInt(40), 1 + random.nextInt(30));
			var anchor = size.positionAt(random.nextLong(size.volume()));
			var local = size.positionAt(random.nextLong(size.volume()));
			var controller = new BlockPos(random.nextInt(60_000_000) - 30_000_000,
					random.nextInt(384) - 64, random.nextInt(60_000_000) - 30_000_000);
			for (var facing : Direction.Plane.HORIZONTAL) {
				var transform = new StructureTransform(controller, anchor, facing);
				assertEquals(local, transform.toLocal(transform.toWorld(local)));
				var point = new Vec3(local.getX() + 0.25, local.getY() + 0.75, local.getZ() + 0.125);
				assertEquals(point, transform.toLocalPoint(transform.toWorldPoint(point)));
				var bounds = transform.toWorldBounds(size.bounds());
				assertTrue(bounds.contains(transform.toWorldPoint(point)));
				assertEquals(facing.getAxis() == Direction.Axis.X ? size.depth() : size.width(), bounds.getXsize());
				assertEquals(size.height(), bounds.getYsize());
				assertEquals(facing.getAxis() == Direction.Axis.X ? size.width() : size.depth(), bounds.getZsize());
			}
		}
	}
	@Test void capturedMutableCoordinatesCannotMoveTheStructure() {
		var controller = new BlockPos.MutableBlockPos(10, 20, 30);
		var anchor = new BlockPos.MutableBlockPos(1, 2, 3);
		var transform = new StructureTransform(controller, anchor, Direction.WEST);
		controller.set(100, 200, 300); anchor.set(4, 5, 6);
		assertEquals(new BlockPos(10, 20, 30), transform.toWorld(new BlockPos(1, 2, 3)));
		assertFalse(transform.controller() instanceof BlockPos.MutableBlockPos);
		assertFalse(transform.anchor() instanceof BlockPos.MutableBlockPos);
	}
	@Test void overflowCannotWrapToAnotherWorldPosition() {
		var transform = new StructureTransform(new BlockPos(Integer.MAX_VALUE, 0, 0), BlockPos.ZERO, Direction.NORTH);
		assertThrows(ArithmeticException.class, () -> transform.toWorld(new BlockPos(1, 0, 0)));
		assertThrows(ArithmeticException.class, () -> transform.toLocal(new BlockPos(Integer.MIN_VALUE, 0, 0)));
		var wideOffset = new StructureTransform(new BlockPos(Integer.MIN_VALUE, 0, 0),
				new BlockPos(Integer.MIN_VALUE, 0, 0), Direction.NORTH);
		assertEquals(new BlockPos(Integer.MAX_VALUE, 0, 0), wideOffset.toWorld(new BlockPos(Integer.MAX_VALUE, 0, 0)));
	}
	@Test void verticalFacingAndNonFiniteVisualGeometryAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new StructureTransform(BlockPos.ZERO, BlockPos.ZERO, Direction.UP));
		var transform = new StructureTransform(BlockPos.ZERO, BlockPos.ZERO, Direction.NORTH);
		assertThrows(IllegalArgumentException.class, () -> transform.toWorldPoint(new Vec3(Double.NaN, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> transform.toLocalPoint(new Vec3(0, Double.POSITIVE_INFINITY, 0)));
		assertThrows(IllegalArgumentException.class, () -> transform.toWorldBounds(new AABB(0, 0, 0, 1, 0, 1)));
	}
}
