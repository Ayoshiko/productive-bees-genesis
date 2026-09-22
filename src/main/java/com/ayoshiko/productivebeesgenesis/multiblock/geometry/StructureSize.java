package com.ayoshiko.productivebeesgenesis.multiblock.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.Objects;

/** 矩形格域；只保存尺寸，按索引定位，不提前分配整个体积。 */
public record StructureSize(int width, int height, int depth) {
	/** 按接触边界的轴数分类，与具体业务角色分离。 */
	public enum Region { INTERIOR, FACE, EDGE, CORNER }

	public StructureSize {
		if (width <= 0 || height <= 0 || depth <= 0) {
			throw new IllegalArgumentException("Positive structure dimensions required");
		}
		Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
	}

	/** 精确体积；构造时已检查 long 溢出。 */
	public long volume() { return (long) width * height * depth; }

	/** 判断局部格是否处于矩形内部（包含外壳格）。 */
	public boolean contains(BlockPos local) {
		Objects.requireNonNull(local);
		return local.getX() >= 0 && local.getX() < width && local.getY() >= 0 && local.getY() < height
				&& local.getZ() >= 0 && local.getZ() < depth;
	}

	/** x 最快、z 次之、y 最慢；供有预算的扫描游标使用。 */
	public BlockPos positionAt(long index) {
		if (index < 0 || index >= volume()) throw new IndexOutOfBoundsException("Outside structure volume");
		return new BlockPos((int) (index % width), (int) (index / width / depth), (int) (index / width % depth));
	}

	/** 将有效局部格转换为同一遍历顺序的索引；越界拒绝。 */
	public long indexOf(BlockPos local) {
		requireInside(local);
		return ((long) local.getY() * depth + local.getZ()) * width + local.getX();
	}

	/** 薄层的同一轴只计一次边界；尺寸为一不产生虚构内部格。 */
	public Region regionAt(BlockPos local) {
		requireInside(local);
		int axes = boundary(local.getX(), width) + boundary(local.getY(), height) + boundary(local.getZ(), depth);
		return switch (axes) {
			case 0 -> Region.INTERIOR;
			case 1 -> Region.FACE;
			case 2 -> Region.EDGE;
			default -> Region.CORNER;
		};
	}

	/** 连续格角范围，最大边界等于尺寸。 */
	public AABB bounds() { return new AABB(0, 0, 0, width, height, depth); }

	private void requireInside(BlockPos local) {
		if (!contains(local)) throw new IllegalArgumentException("Position outside structure");
	}
	private static int boundary(int coordinate, int extent) {
		return coordinate == 0 || coordinate == extent - 1 ? 1 : 0;
	}
}
