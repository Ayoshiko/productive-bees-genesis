package com.ayoshiko.productivebeesgenesis.multiblock.definition;

import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureGeometry;
import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureSize;
import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureSize.Region;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole.*;

/** 首台蜂箱／离心一体机；外壳深度不增加工作单元或能力。 */
public final class CombinedApiaryDefinition {
	public static final StructureDefinition DEFINITION = new StructureDefinition(
			ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "combined_apiary"), 2,
			// 旧索引和模板保持稳定；高版追加，已搭建的 5 格高机器仍可重新形成。
			List.of(template(5, 5), template(7, 5), template(9, 5), template(5, 7), template(7, 7), template(9, 7)));

	private static StructureTemplate template(int depth, int height) {
		var size = new StructureSize(7, height, depth);
		var controller = new BlockPos(3, 1, 0);
		var core = new BlockPos(3, height / 2, depth / 2);
		var geometry = new StructureGeometry(size, controller, Vec3.atCenterOf(core),
				height == 5 ? new AABB(core).inflate(1) : new AABB(1, 1, 1, 6, height - 1, depth - 1));
		return new StructureTemplate("7x" + height + "x" + depth, geometry,
				Map.of(Region.CORNER, StructureCell.of(FRAME), Region.EDGE, StructureCell.of(FRAME),
						Region.FACE, new StructureCell(Set.of(CASING, GLASS), null), Region.INTERIOR, StructureCell.of(AIR)),
				Map.of(controller, StructureCell.facing(CONTROLLER, Direction.NORTH), core, StructureCell.of(CORE),
						new BlockPos(2, 1, depth / 2), StructureCell.facing(APIARY_UNIT, Direction.NORTH),
						new BlockPos(4, 1, depth / 2), StructureCell.facing(CENTRIFUGE_UNIT, Direction.NORTH),
						new BlockPos(3, 2, 0), StructureCell.facing(INTERFACE, Direction.NORTH),
						new BlockPos(0, 1, 2), StructureCell.facing(ENERGY_PORT, Direction.WEST),
						new BlockPos(2, 1, depth - 1), StructureCell.facing(INPUT_PORT, Direction.SOUTH),
						new BlockPos(4, 1, depth - 1), StructureCell.facing(OUTPUT_PORT, Direction.SOUTH)));
	}
	private CombinedApiaryDefinition() { }
}
