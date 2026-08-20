package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import mekanism.api.RelativeSide;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 蜂箱直连离心机目标扫描器（缓存 + 配置签名失效）
 * <br/>
 * 从 {@link ApiaryDirectEjectHandler} 拆分而来，职责（SRP）：按蜂箱物品侧面配置
 * 查找相邻离心机并缓存目标列表，配置或朝向变化时自动失效重建。
 * <p>
 * 线程安全：服务端单线程调用，无需同步。
 */
final class ApiaryDirectEjectTargets {

	/** 所属蜂箱方块实体 */
	private final TileEntityMekApiary apiary;

	/** 缓存的直连目标列表 — 避免每 tick 遍历所有方向查找 */
	@Nullable
	private List<Target> cachedTargets;

	/** 缓存对应的物品侧面配置签名（含朝向）— 配置或朝向变化时强制重建目标列表 */
	private int cachedConfigVersion = -1;

	/** 最近一次解析目标的游戏刻；JDTE 同刻子循环直接复用结果，包括空结果。 */
	private long lastResolvedGameTick = Long.MIN_VALUE;

	/** 直连目标：已校验的相邻离心机位置、方块实体与接口引用。 */
	static final class Target {
		final BlockPos pos;
		final BlockEntity blockEntity;
		final IMekCentrifugeTile centrifuge;
		final CentrifugeInputSlotManager inputSlotManager = new CentrifugeInputSlotManager();
		int inputSlotCount;

		Target(BlockPos pos, BlockEntity blockEntity, IMekCentrifugeTile centrifuge) {
			this.pos = pos;
			this.blockEntity = blockEntity;
			this.centrifuge = centrifuge;
		}

		void preScanInputSlots() {
			inputSlotCount = Math.max(0, centrifuge.productivebeesgenesis$getInputSlotCount());
			inputSlotManager.preScanInputSlots(centrifuge, inputSlotCount);
		}
	}

	ApiaryDirectEjectTargets(TileEntityMekApiary apiary) {
		this.apiary = apiary;
	}

	/**
	 * 查找直连目标离心机列表
	 * <br/>
	 * 路由规则：若蜂箱物品侧面配置存在输出面（OUTPUT/INPUT_OUTPUT），
	 * 只直连这些面对应的相邻离心机；若未配置任何输出面，回退到任意相邻离心机（兼容旧存档）。
	 * <p>
	 * 缓存优化：非空缓存列表且配置签名一致、所有方块仍有效时直接返回；
	 * <b>空列表不跨 tick 缓存</b>（仅同 tick 内复用），保证后放置的离心机能被下一 tick 的
	 * 重扫发现。失效（方块被移除/替换）时重新扫描。
	 *
	 * @param level 世界实例
	 * @return 直连目标列表，可能为空
	 */
	List<Target> findDirectEjectTargets(Level level) {
		long gameTick = level.getGameTime();
		if (cachedTargets != null && lastResolvedGameTick == gameTick) {
			return cachedTargets;
		}

		BlockPos myPos = apiary.getBlockPos();
		Direction facing = apiary.getDirection();
		ConfigInfo itemConfig = apiary.getConfig().getConfig(TransmissionType.ITEM);
		int configVersion = computeConfigVersion(facing, itemConfig);

		// 优先使用缓存：非空列表 + 配置签名一致且所有方块仍有效时直接返回，不重复扫描全部方向
		if (cachedTargets != null && !cachedTargets.isEmpty() && configVersion == cachedConfigVersion) {
			boolean allValid = true;
			for (Target target : cachedTargets) {
				if (target.blockEntity.isRemoved() || level.getBlockEntity(target.pos) != target.blockEntity) {
					allValid = false;
					break;
				}
			}
			if (allValid) {
				lastResolvedGameTick = gameTick;
				return cachedTargets;
			}
			cachedTargets = null;
		}

		boolean hasConfiguredOutput = hasConfiguredOutputSide(itemConfig);

		List<Target> found = null;
		for (Direction side : Direction.values()) {
			if (hasConfiguredOutput) {
				// 仅路由到配置为输出面的方向
				if (itemConfig == null) continue;
				RelativeSide relativeSide = RelativeSide.fromDirections(facing, side);
				DataType dataType = itemConfig.getDataType(relativeSide);
				if (!itemConfig.isSideEnabled(relativeSide) || dataType == null || !dataType.canOutput()) {
					continue;
				}
			}
			BlockPos adjacentPos = myPos.relative(side);
			BlockEntity be = level.getBlockEntity(adjacentPos);
			if (be instanceof IMekCentrifugeTile centrifuge && !be.isRemoved()) {
				if (found == null) found = new ArrayList<>(4);
				found.add(new Target(adjacentPos, be, centrifuge));
			}
		}
		cachedConfigVersion = configVersion;
		lastResolvedGameTick = gameTick;
		cachedTargets = found == null ? List.of() : List.copyOf(found);
		return cachedTargets;
	}

	/** 计算物品侧面配置 + 朝向的轻量签名，配置或旋转变化时目标缓存自动失效 */
	private int computeConfigVersion(Direction facing, @Nullable ConfigInfo itemConfig) {
		int version = facing.ordinal();
		if (itemConfig != null) {
			for (RelativeSide relativeSide : RelativeSide.values()) {
				DataType dataType = itemConfig.getDataType(relativeSide);
				version = version * 31 + (itemConfig.isSideEnabled(relativeSide) ? 1 : 0);
				version = version * 31 + (dataType == null ? 0 : dataType.ordinal());
			}
		}
		return version;
	}

	/** 判断物品侧面配置是否存在任何输出面（OUTPUT/INPUT_OUTPUT 等 canOutput 类型） */
	private boolean hasConfiguredOutputSide(@Nullable ConfigInfo itemConfig) {
		if (itemConfig == null) return false;
		for (RelativeSide relativeSide : RelativeSide.values()) {
			if (!itemConfig.isSideEnabled(relativeSide)) continue;
			DataType dataType = itemConfig.getDataType(relativeSide);
			if (dataType != null && dataType.canOutput()) return true;
		}
		return false;
	}

	/** 清除缓存（方块移动/移除时调用） */
	void clearCache() {
		cachedTargets = null;
		cachedConfigVersion = -1;
		lastResolvedGameTick = Long.MIN_VALUE;
	}
}
