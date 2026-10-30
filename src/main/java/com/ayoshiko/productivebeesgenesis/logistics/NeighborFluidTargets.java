package com.ayoshiko.productivebeesgenesis.logistics;

import mekanism.api.RelativeSide;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 缓存流体输出方向与相邻能力，避免原版弹出器每刻重建映射和目标列表。 */
public final class NeighborFluidTargets {

	private final TileEntityMekanism tile;
	private final List<Direction> outputSides = new ArrayList<>(6);
	private boolean sidesValid;
	@Nullable
	private Direction cachedFacing;

	public NeighborFluidTargets(TileEntityMekanism tile) {
		this.tile = tile;
	}

	public void invalidate() {
		sidesValid = false;
	}

	/** 返回配置为流体输出的世界方向；内部列表跨 tick 复用。 */
	public List<Direction> outputSides(@Nullable ConfigInfo fluidConfig) {
		Direction facing = tile.getDirection();
		if (!sidesValid || facing != cachedFacing) rebuild(fluidConfig, facing);
		return outputSides;
	}

	@Nullable
	public IFluidHandler handler(Direction side) {
		Level level = tile.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) return null;
		var neighborPos = tile.getBlockPos().relative(side);
		if (!serverLevel.hasChunkAt(neighborPos)) return null;
		BlockEntity neighbor = serverLevel.getBlockEntity(neighborPos);
		return neighbor == null ? null : neighbor.getCapability(
				ForgeCapabilities.FLUID_HANDLER, side.getOpposite()).orElse(null);
	}

	private void rebuild(@Nullable ConfigInfo fluidConfig, Direction facing) {
		outputSides.clear();
		cachedFacing = facing;
		sidesValid = true;
		if (fluidConfig == null) return;
		for (RelativeSide side : RelativeSide.values()) {
			if (!fluidConfig.isSideEnabled(side)) continue;
			DataType dataType = fluidConfig.getDataType(side);
			if (dataType == null || !dataType.canOutput()) continue;
			if (fluidConfig.getSlotInfo(dataType) == null) continue;
			Direction direction = side.getDirection(facing);
			if (!outputSides.contains(direction)) outputSides.add(direction);
		}
	}

	@Nullable
	public static ConfigInfo fluidConfig(TileEntityMekanism tile) {
		if (!(tile instanceof ISideConfiguration sideConfiguration)) return null;
		TileComponentConfig config = sideConfiguration.getConfig();
		return config == null ? null : config.getConfig(TransmissionType.FLUID);
	}
}
