package com.ayoshiko.productivebeesgenesis.multiblock.definition;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;

/** 空朝向表示不检查；方向使用局部坐标，由扫描／预览共同旋转。 */
public record StructureCell(Set<StructureRole> roles, Direction facing) {
	public StructureCell {
		roles = Set.copyOf(roles);
		if (roles.isEmpty()) throw new IllegalArgumentException("Empty structure rule");
		if (roles.contains(StructureRole.AIR) && (roles.size() != 1 || facing != null)) {
			throw new IllegalArgumentException("Air must be checked explicitly");
		}
	}
	public static StructureCell of(StructureRole role) { return new StructureCell(Set.of(role), null); }
	public static StructureCell facing(StructureRole role, Direction facing) {
		return new StructureCell(Set.of(role), Objects.requireNonNull(facing));
	}
}
