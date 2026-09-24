package com.ayoshiko.productivebeesgenesis.multiblock.validation;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureCell;
import net.minecraft.core.BlockPos;

/** 每个失败候选只保留首个位置；不可读取的格没有 observed，坐标溢出时 world 为空。 */
public record StructureScanDiagnostic(String variant, Reason reason, BlockPos local, BlockPos world,
		StructureCell expected, StructureScanAccess.State observed) {
	public enum Reason { WRONG_ROLE, WRONG_FACING, UNLOADED_CHUNK, OUTSIDE_WORLD, COORDINATE_OVERFLOW, QUERY_FAILED }
	public StructureScanDiagnostic {
		local = local == null ? null : local.immutable();
		world = world == null ? null : world.immutable();
	}
}
