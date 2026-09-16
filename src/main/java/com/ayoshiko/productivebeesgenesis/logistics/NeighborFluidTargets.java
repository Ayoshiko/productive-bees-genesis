package com.ayoshiko.productivebeesgenesis.logistics;

import mekanism.api.RelativeSide;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 缓存流体输出方向与相邻能力，避免原版弹出器每刻重建映射和目标列表。 */
public final class NeighborFluidTargets {

	private final TileEntityMekanism tile;
	private final Map<Direction, BlockCapabilityCache<IFluidHandler, @Nullable Direction>> caches =
			new EnumMap<>(Direction.class);
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
		BlockCapabilityCache<IFluidHandler, @Nullable Direction> cache = caches.get(side);
		if (cache == null) {
			cache = Capabilities.FLUID.createCache(serverLevel,
					tile.getBlockPos().relative(side), side.getOpposite());
			caches.put(side, cache);
		}
		return cache.getCapability();
	}

	private void rebuild(@Nullable ConfigInfo fluidConfig, Direction facing) {
		outputSides.clear();
		cachedFacing = facing;
		sidesValid = true;
		if (fluidConfig == null) return;
		for (Map.Entry<RelativeSide, DataType> entry : fluidConfig.getSideConfig()) {
			DataType dataType = entry.getValue();
			if (dataType == null || !dataType.canOutput()) continue;
			if (fluidConfig.getSlotInfo(dataType) == null) continue;
			Direction direction = entry.getKey().getDirection(facing);
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
