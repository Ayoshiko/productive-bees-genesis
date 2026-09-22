package com.ayoshiko.productivebeesgenesis.multiblock.geometry;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureSizeTest {
	@Test void rectangularTraversalAndRegionsMatchIndependentCounts() {
		var size = new StructureSize(3, 4, 5);
		int index = 0;
		int[] regions = new int[4];
		for (int y = 0; y < 4; y++) {
			for (int z = 0; z < 5; z++) {
				for (int x = 0; x < 3; x++) {
					var position = new BlockPos(x, y, z);
					assertEquals(position, size.positionAt(index));
					assertEquals(index++, size.indexOf(position));
					regions[size.regionAt(position).ordinal()]++;
				}
			}
		}
		assertEquals(60, size.volume());
		assertArrayEquals(new int[] {6, 22, 24, 8}, regions);
	}
	@Test void thinDimensionsCountEachBoundaryAxisOnce() {
		assertEquals(StructureSize.Region.CORNER, new StructureSize(1, 1, 1).regionAt(BlockPos.ZERO));
		var sheet = new StructureSize(1, 4, 5);
		assertEquals(StructureSize.Region.FACE, sheet.regionAt(new BlockPos(0, 1, 2)));
		assertEquals(StructureSize.Region.EDGE, sheet.regionAt(new BlockPos(0, 0, 2)));
		for (long i = 0; i < sheet.volume(); i++) {
			assertNotEquals(StructureSize.Region.INTERIOR, sheet.regionAt(sheet.positionAt(i)));
		}
	}
	@Test void largeVolumeUsesLongAndConstantSpaceIndexedAccess() {
		var size = new StructureSize(1_000_000, 1_000_000, 1_000_000);
		assertEquals(1_000_000_000_000_000_000L, size.volume());
		var last = new BlockPos(999_999, 999_999, 999_999);
		assertEquals(last, size.positionAt(size.volume() - 1));
		assertEquals(size.volume() - 1, size.indexOf(last));
		assertEquals(new BlockPos(0, 1, 0), size.positionAt(1_000_000_000_000L));
	}
	@Test void invalidDimensionsAndOutOfRangePositionsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new StructureSize(0, 3, 5));
		assertThrows(IllegalArgumentException.class, () -> new StructureSize(3, -1, 5));
		assertThrows(ArithmeticException.class, () -> new StructureSize(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
		var size = new StructureSize(3, 4, 5);
		assertThrows(IndexOutOfBoundsException.class, () -> size.positionAt(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> size.positionAt(60));
		for (var position : new BlockPos[] {new BlockPos(-1, 0, 0), new BlockPos(3, 0, 0),
				new BlockPos(0, 4, 0), new BlockPos(0, 0, 5)}) {
			assertFalse(size.contains(position));
			assertThrows(IllegalArgumentException.class, () -> size.regionAt(position));
			assertThrows(IllegalArgumentException.class, () -> size.indexOf(position));
		}
	}
}
