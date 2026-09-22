package com.ayoshiko.productivebeesgenesis.multiblock.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** 扫描与未来预览／渲染共用的空间定义；不含角色、世界查询或运行状态。 */
public record StructureGeometry(StructureSize size, BlockPos controllerAnchor, Vec3 coreCenter, AABB visualBounds) {
	public StructureGeometry {
		Objects.requireNonNull(size);
		controllerAnchor = Objects.requireNonNull(controllerAnchor).immutable();
		if (!size.contains(controllerAnchor)) throw new IllegalArgumentException("Controller anchor outside structure");
		StructureTransform.requireFinite(coreCenter);
		StructureTransform.requireBounds(visualBounds);
		if (!size.bounds().contains(coreCenter) || !visualBounds.contains(coreCenter)) {
			throw new IllegalArgumentException("Core center outside structure or visual bounds");
		}
	}

	/** 绑定实际控制器位置和水平朝向，并验证全部格坐标可表示。 */
	public StructureTransform at(BlockPos controller, Direction facing) {
		var transform = new StructureTransform(controller, controllerAnchor, facing);
		// 先拒绝整格范围溢出；不能形成已绕回另一侧世界坐标的结构。
		transform.toWorld(BlockPos.ZERO);
		transform.toWorld(new BlockPos(size.width() - 1, size.height() - 1, size.depth() - 1));
		return transform;
	}

	/** 核心的世界连续坐标；偶数尺寸可位于两个方块之间。 */
	public Vec3 coreCenterAt(BlockPos controller, Direction facing) {
		return at(controller, facing).toWorldPoint(coreCenter);
	}

	/** 包含全部结构格的世界连续范围，不代表已获得占位权限。 */
	public AABB occupiedBoundsAt(BlockPos controller, Direction facing) {
		return at(controller, facing).toWorldBounds(size.bounds());
	}

	/** 动画最大范围与控制器自身合并后的有限剔除盒。 */
	public AABB renderBoundsAt(BlockPos controller, Direction facing) {
		var transform = at(controller, facing);
		return transform.toWorldBounds(visualBounds).minmax(new AABB(transform.controller()));
	}
}
