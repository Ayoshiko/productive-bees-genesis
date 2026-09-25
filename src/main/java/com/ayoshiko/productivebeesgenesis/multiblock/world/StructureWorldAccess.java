package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScanAccess;
import com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScanStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 已加载世界的只读适配；查询不接触能力或方块实体库存。 */
record StructureWorldAccess(ServerLevel level, MachineDirectory.Handle handle) implements StructureScanAccess {
	@Override public StructureScanStamp stamp() { return handle.stamp(); }
	@Override public Availability availability(BlockPos pos) {
		if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return Availability.OUTSIDE_WORLD;
		return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? Availability.LOADED : Availability.UNLOADED;
	}
	@Override public State read(BlockPos pos) {
		var state = level.getBlockState(pos);
		return new State(state.isAir() ? StructureRole.AIR : state.getBlock() instanceof MachineContent.RoleBlock role ? role.role() : StructureRole.OTHER,
				state.hasProperty(MachinePartBlock.FACING) ? state.getValue(MachinePartBlock.FACING) : null);
	}
}
