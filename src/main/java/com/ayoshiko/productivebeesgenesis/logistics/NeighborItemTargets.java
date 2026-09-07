package com.ayoshiko.productivebeesgenesis.logistics;

import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import mekanism.api.RelativeSide;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 输出面相邻容器目标解析与缓存（每台机器一份）。
 * <p>
 * <b>职责：</b>回答两个问题 —— 「哪些方向被配置成了物品输出面」以及「那个方向上现在有没有可用的
 * {@link IItemHandler}」。逐刻弹出与产物直通共用同一份结果。
 * <p>
 * <b>性能设计：</b>
 * <ul>
 *   <li>方向列表按「朝向 + 侧面配置」缓存，只有玩家转动机器或改侧面配置时才重建
 *       （侧面配置变更由 {@link TileComponentConfig#addConfigChangeListener} 主动通知）。</li>
 *   <li>每个方向持有一个 {@link BlockCapabilityCache}，由 NeoForge 负责失效，
 *       避免每刻 {@code getCapability} 走方块实体查找。</li>
 *   <li>坐标固定，因此能力缓存可跨重建复用，只有方向集合会变。</li>
 * </ul>
 * <p>
 * 线程安全：仅服务端 tick 线程访问。
 *
 * @since 2.1.0
 */
public final class NeighborItemTargets {

	/** 所属机器 */
	private final TileEntityMekanism tile;

	/** 每个方向的相邻容器能力缓存（坐标固定，可长期持有） */
	private final Map<Direction, BlockCapabilityCache<IItemHandler, @Nullable Direction>> caches =
			new EnumMap<>(Direction.class);

	/** 当前输出方向列表（按侧面配置解析） */
	private final List<Direction> outputSides = new ArrayList<>(6);

	/** 目标列表是否有效（false 表示需要重建） */
	private boolean sidesValid;

	/** 上次解析时机器的朝向 */
	@Nullable
	private Direction cachedFacing;

	/**
	 * @param tile 所属机器（需实现 Mekanism 的侧面配置接口）
	 */
	public NeighborItemTargets(TileEntityMekanism tile) {
		this.tile = tile;
	}

	/** 侧面配置或朝向变化时调用，下次访问重新解析方向集合。 */
	public void invalidate() {
		sidesValid = false;
	}

	/**
	 * 返回被配置为物品输出的方向列表。
	 *
	 * @param itemConfig 机器的 ITEM 侧面配置（null 时返回空列表）
	 * @return 输出方向列表（内部复用，调用方只读）
	 */
	public List<Direction> outputSides(@Nullable ConfigInfo itemConfig) {
		Direction facing = tile.getDirection();
		if (!sidesValid || facing != cachedFacing) {
			rebuild(itemConfig, facing);
		}
		return outputSides;
	}

	/**
	 * 取某个方向上的相邻容器。
	 *
	 * @param side 方向
	 * @return 相邻容器；不存在时返回 null
	 */
	@Nullable
	public IItemHandler handler(Direction side) {
		Level level = tile.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) return null;
		BlockCapabilityCache<IItemHandler, @Nullable Direction> cache = caches.get(side);
		if (cache == null) {
			cache = Capabilities.ITEM.createCache(serverLevel,
					tile.getBlockPos().relative(side), side.getOpposite());
			caches.put(side, cache);
		}
		return cache.getCapability();
	}

	/** 是否存在任何输出方向（快速短路用）。 */
	public boolean hasAnyOutputSide(@Nullable ConfigInfo itemConfig) {
		return !outputSides(itemConfig).isEmpty();
	}

	private void rebuild(@Nullable ConfigInfo itemConfig, Direction facing) {
		outputSides.clear();
		cachedFacing = facing;
		sidesValid = true;
		if (itemConfig == null) return;
		for (Map.Entry<RelativeSide, DataType> entry : itemConfig.getSideConfig()) {
			DataType dataType = entry.getValue();
			if (dataType == null || !dataType.canOutput()) continue;
			if (itemConfig.getSlotInfo(dataType) == null) continue;
			Direction direction = entry.getKey().getDirection(facing);
			if (!outputSides.contains(direction)) outputSides.add(direction);
		}
	}

	/**
	 * 便捷入口：从机器身上取 ITEM 侧面配置。
	 *
	 * @param tile 机器
	 * @return ITEM 的 {@link ConfigInfo}；机器没有侧面配置组件时返回 null
	 */
	@Nullable
	public static ConfigInfo itemConfig(TileEntityMekanism tile) {
		if (!(tile instanceof ISideConfiguration sideConfiguration)) return null;
		TileComponentConfig config = sideConfiguration.getConfig();
		return config == null ? null : config.getConfig(TransmissionType.ITEM);
	}
}
