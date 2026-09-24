package com.ayoshiko.productivebeesgenesis.multiblock.runtime;

import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 包含空气的整格占位；相邻但没有共享格的矩形不冲突。 */
public record MachineRegion(BlockPos min, BlockPos max) {
	public record Section(int x, int y, int z) { }
	public static final int MAX_SECTIONS = 64;
	public MachineRegion {
		min = min.immutable(); max = max.immutable();
		if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) throw new IllegalArgumentException("Invalid machine bounds");
	}
	public static MachineRegion at(StructureGeometry geometry, BlockPos controller, Direction facing) {
		var transform = geometry.at(controller, facing); var size = geometry.size();
		var a = transform.toWorld(BlockPos.ZERO); var b = transform.toWorld(new BlockPos(size.width() - 1, size.height() - 1, size.depth() - 1));
		return new MachineRegion(new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
				new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
	}
	public MachineRegion union(MachineRegion other) {
		return new MachineRegion(new BlockPos(Math.min(min.getX(), other.min.getX()), Math.min(min.getY(), other.min.getY()), Math.min(min.getZ(), other.min.getZ())),
				new BlockPos(Math.max(max.getX(), other.max.getX()), Math.max(max.getY(), other.max.getY()), Math.max(max.getZ(), other.max.getZ())));
	}
	public boolean contains(BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY() && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}
	public boolean intersects(MachineRegion other) {
		return min.getX() <= other.max.getX() && max.getX() >= other.min.getX() && min.getY() <= other.max.getY()
				&& max.getY() >= other.min.getY() && min.getZ() <= other.max.getZ() && max.getZ() >= other.min.getZ();
	}
	/** 目录接受的空间索引复杂度上限；扫描器自身仍可验证更大模板。 */
	public List<Section> sections() {
		int x0 = min.getX() >> 4, y0 = min.getY() >> 4, z0 = min.getZ() >> 4;
		int x1 = max.getX() >> 4, y1 = max.getY() >> 4, z1 = max.getZ() >> 4;
		long x = (long) x1 - x0 + 1, y = (long) y1 - y0 + 1, z = (long) z1 - z0 + 1;
		if (x > MAX_SECTIONS || y > MAX_SECTIONS || z > MAX_SECTIONS || x * y * z > MAX_SECTIONS) throw new IllegalArgumentException("Machine exceeds directory section budget");
		var result = new ArrayList<Section>();
		for (int sx = x0; sx <= x1; sx++) for (int sy = y0; sy <= y1; sy++) for (int sz = z0; sz <= z1; sz++) result.add(new Section(sx, sy, sz));
		return List.copyOf(result);
	}
}
