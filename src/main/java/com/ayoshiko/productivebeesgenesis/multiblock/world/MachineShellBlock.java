package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/** 框架、外壳、玻璃没有方块实体和 ticker。 */
final class MachineShellBlock extends Block implements MachineContent.RoleBlock {
	private final StructureRole role;
	MachineShellBlock(StructureRole role) { super(properties(role)); this.role = role; }
	private static Properties properties(StructureRole role) {
		var properties = Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(4).sound(SoundType.METAL);
		return role == StructureRole.GLASS ? properties.noOcclusion() : properties;
	}
	@Override protected MapCodec<? extends Block> codec() { return MapCodec.unit(this); }
	@Override public StructureRole role() { return role; }
	@Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
		super.onPlace(state, level, pos, previous, moved); if (state.getBlock() != previous.getBlock()) MachineWorldService.changed(level, pos);
	}
	@Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
		if (state.getBlock() != next.getBlock()) MachineWorldService.changed(level, pos); super.onRemove(state, level, pos, next, moved);
	}
}
