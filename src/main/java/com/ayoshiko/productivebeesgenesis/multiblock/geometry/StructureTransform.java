package com.ayoshiko.productivebeesgenesis.multiblock.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** 默认朝北；整数按格定位，连续坐标绕控制器格中心旋转。 */
public record StructureTransform(BlockPos controller, BlockPos anchor, Direction facing) {
	public StructureTransform {
		controller = Objects.requireNonNull(controller).immutable();
		anchor = Objects.requireNonNull(anchor).immutable();
		if (!Objects.requireNonNull(facing).getAxis().isHorizontal()) {
			throw new IllegalArgumentException("Horizontal facing required");
		}
	}

	/** 局部格转世界格；最终整数坐标溢出时拒绝，不检查世界边界或区块。 */
	public BlockPos toWorld(BlockPos local) {
		long x = (long) local.getX() - anchor.getX();
		long y = (long) local.getY() - anchor.getY();
		long z = (long) local.getZ() - anchor.getZ();
		long rx = switch (facing) {
			case NORTH -> x;
			case EAST -> -z;
			case SOUTH -> -x;
			case WEST -> z;
			default -> throw new AssertionError();
		};
		long rz = switch (facing) {
			case NORTH -> z;
			case EAST -> x;
			case SOUTH -> -z;
			case WEST -> -x;
			default -> throw new AssertionError();
		};
		return exact(controller.getX() + rx, controller.getY() + y, controller.getZ() + rz);
	}

	/** 世界格转局部格；局部结果超出 int 时拒绝。 */
	public BlockPos toLocal(BlockPos world) {
		long x = (long) world.getX() - controller.getX();
		long y = (long) world.getY() - controller.getY();
		long z = (long) world.getZ() - controller.getZ();
		long rx = switch (facing) {
			case NORTH -> x;
			case EAST -> z;
			case SOUTH -> -x;
			case WEST -> -z;
			default -> throw new AssertionError();
		};
		long rz = switch (facing) {
			case NORTH -> z;
			case EAST -> -x;
			case SOUTH -> -z;
			case WEST -> x;
			default -> throw new AssertionError();
		};
		return exact(anchor.getX() + rx, anchor.getY() + y, anchor.getZ() + rz);
	}

	/** 连续局部坐标采用格角坐标系；格中心为 (x+0.5, y+0.5, z+0.5)。 */
	public Vec3 toWorldPoint(Vec3 local) {
		requireFinite(local);
		Vec3 rotated = rotate(local.subtract(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5), facing);
		Vec3 result = rotated.add(controller.getX() + 0.5, controller.getY() + 0.5, controller.getZ() + 0.5);
		requireFinite(result);
		return result;
	}

	/** 连续坐标的逆变换；拒绝非有限输入／结果。 */
	public Vec3 toLocalPoint(Vec3 world) {
		requireFinite(world);
		Direction inverse = switch (facing) {
			case EAST -> Direction.WEST;
			case WEST -> Direction.EAST;
			default -> facing;
		};
		Vec3 offset = world.subtract(controller.getX() + 0.5, controller.getY() + 0.5, controller.getZ() + 0.5);
		Vec3 rotated = rotate(offset, inverse);
		Vec3 result = rotated.add(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
		requireFinite(result);
		return result;
	}

	/** 转换连接面；上下方向保持不变。 */
	public Direction toWorldDirection(Direction local) {
		if (!Objects.requireNonNull(local).getAxis().isHorizontal()) return local;
		return switch (facing) {
			case NORTH -> local;
			case EAST -> local.getClockWise();
			case SOUTH -> local.getOpposite();
			case WEST -> local.getCounterClockWise();
			default -> throw new AssertionError();
		};
	}

	/** 旋转有限包围盒；正交旋转下两个对角足以确定各轴极值。 */
	public AABB toWorldBounds(AABB local) {
		requireBounds(local);
		var result = new AABB(toWorldPoint(new Vec3(local.minX, local.minY, local.minZ)),
				toWorldPoint(new Vec3(local.maxX, local.maxY, local.maxZ)));
		requireBounds(result);
		return result;
	}

	static void requireFinite(Vec3 point) {
		if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
			throw new IllegalArgumentException("Finite geometry required");
		}
	}
	static void requireBounds(AABB bounds) {
		requireFinite(new Vec3(bounds.minX, bounds.minY, bounds.minZ));
		requireFinite(new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ));
		if (bounds.minX >= bounds.maxX || bounds.minY >= bounds.maxY || bounds.minZ >= bounds.maxZ) {
			throw new IllegalArgumentException("Nonempty visual bounds required");
		}
	}
	private static Vec3 rotate(Vec3 point, Direction facing) {
		return switch (facing) {
			case NORTH -> point;
			case EAST -> new Vec3(-point.z, point.y, point.x);
			case SOUTH -> new Vec3(-point.x, point.y, -point.z);
			case WEST -> new Vec3(point.z, point.y, -point.x);
			default -> throw new AssertionError();
		};
	}
	private static BlockPos exact(long x, long y, long z) {
		return new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
	}
}
