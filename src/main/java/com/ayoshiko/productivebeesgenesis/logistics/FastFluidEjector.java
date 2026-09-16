package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.FluidSlotInfo;
import mekanism.common.tile.component.config.slot.ISlotInfo;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 本模组机器的整罐流体弹出通道。
 * <p>
 * 每真实游戏刻至多执行一轮，每个槽单次最多提供 {@link Integer#MAX_VALUE} mB；输出方向与
 * NeoForge 能力长期缓存。连续拒收时短退避，槽内容变化立即恢复，兼顾高倍加速吞吐与空转成本。
 */
public final class FastFluidEjector {

	private static final int IDLE_BACKOFF_THRESHOLD = 3;
	private static final int IDLE_BACKOFF_TICKS = 10;

	private final NeighborFluidTargets targets;
	private int tankCursor;
	private int sideCursor;
	private int consecutiveIdleTicks;
	private long lastAttemptGameTime = Long.MIN_VALUE;
	private long backoffUntilTick = Long.MIN_VALUE;
	private long backoffFingerprint = Long.MIN_VALUE;
	private long sampledAmount;

	public FastFluidEjector(TileEntityMekanism tile) {
		this.targets = new NeighborFluidTargets(tile);
	}

	public void onConfigChanged() {
		targets.invalidate();
		clearBackoff();
	}

	/** 批量弹出所有可提取流体；调用方应在本模组机器上用本方法替代原版 FLUID eject。 */
	public void tick(TileEntityMekanism tile, @Nullable ConfigInfo fluidConfig, long gameTime) {
		if (fluidConfig == null || lastAttemptGameTime == gameTime) return;
		lastAttemptGameTime = gameTime;
		if (gameTime < backoffUntilTick) {
			long fingerprint = sampleContents(fluidConfig);
			if (sampledAmount <= 0L) {
				consecutiveIdleTicks = 0;
				clearBackoff();
				return;
			}
			if (fingerprint == backoffFingerprint) return;
		}

		List<Direction> sides = targets.outputSides(fluidConfig);
		sampledAmount = 0L;
		long moved;
		if (sides.isEmpty()) {
			sampleContents(fluidConfig);
			moved = 0L;
		} else {
			moved = pushConfiguredTanks(tile, fluidConfig, sides);
		}
		if (sampledAmount <= 0L) {
			consecutiveIdleTicks = 0;
			clearBackoff();
			return;
		}
		if (moved > 0L) {
			consecutiveIdleTicks = 0;
			clearBackoff();
			return;
		}
		if (++consecutiveIdleTicks >= IDLE_BACKOFF_THRESHOLD) {
			consecutiveIdleTicks = 0;
			backoffUntilTick = gameTime + IDLE_BACKOFF_TICKS;
			backoffFingerprint = sampleContents(fluidConfig);
		}
	}

	private long pushConfiguredTanks(TileEntityMekanism tile, ConfigInfo fluidConfig,
			List<Direction> sides) {
		long moved = 0L;
		for (DataType dataType : fluidConfig.getSupportedDataTypes()) {
			if (!dataType.canOutput()) continue;
			ISlotInfo slotInfo = fluidConfig.getSlotInfo(dataType);
			if (!(slotInfo instanceof FluidSlotInfo fluidSlotInfo) || slotInfo.isEmpty()) continue;
			List<IExtendedFluidTank> tanks = fluidSlotInfo.getTanks();
			int tankCount = tanks.size();
			int tankStart = RoundRobinSlotTraversal.normalize(tankCursor, tankCount);
			tankCursor = RoundRobinSlotTraversal.advance(tankStart, tankCount);
			for (int tankOffset = 0; tankOffset < tankCount; tankOffset++) {
				IExtendedFluidTank tank = tanks.get(
						RoundRobinSlotTraversal.index(tankStart, tankOffset, tankCount));
				if (tank == null || tank.isEmpty()) continue;
				sampledAmount += Math.max(0, tank.getFluidAmount());
				moved += pushTankToSides(tile, fluidConfig, dataType, tank, sides);
			}
		}
		return moved;
	}

	private long pushTankToSides(TileEntityMekanism tile, ConfigInfo fluidConfig, DataType dataType,
			IExtendedFluidTank tank, List<Direction> sides) {
		int sideCount = sides.size();
		int sideStart = RoundRobinSlotTraversal.normalize(sideCursor, sideCount);
		sideCursor = RoundRobinSlotTraversal.advance(sideStart, sideCount);
		long moved = 0L;
		for (int offset = 0; offset < sideCount && !tank.isEmpty(); offset++) {
			Direction side = sides.get(RoundRobinSlotTraversal.index(sideStart, offset, sideCount));
			RelativeSide relativeSide = RelativeSide.fromDirections(tile.getDirection(), side);
			if (fluidConfig.getDataType(relativeSide) != dataType) continue;
			IFluidHandler target = targets.handler(side);
			if (target != null) moved += transfer(tank, target);
		}
		return moved;
	}

	/** 先模拟目标接收量，再精确抽取；异常目标少收时把余量原样放回源槽。 */
	private static int transfer(IExtendedFluidTank source, IFluidHandler target) {
		FluidStack available = source.extract(Integer.MAX_VALUE, Action.SIMULATE, AutomationType.EXTERNAL);
		if (available.isEmpty()) return 0;
		int accepted;
		try {
			accepted = Math.max(0, Math.min(available.getAmount(),
					target.fill(available, IFluidHandler.FluidAction.SIMULATE)));
		} catch (Exception exception) {
			LogThrottle.warn("fast_fluid_eject_simulate",
					"相邻流体处理器模拟接收异常，已跳过本目标: {}", exception.toString());
			return 0;
		}
		if (accepted <= 0) return 0;

		FluidStack extracted = source.extract(accepted, Action.EXECUTE, AutomationType.EXTERNAL);
		if (extracted.isEmpty()) return 0;
		int inserted;
		try {
			inserted = Math.max(0, Math.min(extracted.getAmount(),
					target.fill(extracted, IFluidHandler.FluidAction.EXECUTE)));
		} catch (Exception exception) {
			returnToSource(source, extracted);
			LogThrottle.warn("fast_fluid_eject_execute",
					"相邻流体处理器实际接收异常，流体已退回源槽: {}", exception.toString());
			return 0;
		}
		if (inserted < extracted.getAmount()) {
			FluidStack rejected = extracted.copyWithAmount(extracted.getAmount() - inserted);
			returnToSource(source, rejected);
		}
		return inserted;
	}

	private static void returnToSource(IExtendedFluidTank source, FluidStack rejected) {
		FluidStack notReturned = source.insert(rejected, Action.EXECUTE, AutomationType.INTERNAL);
		if (!notReturned.isEmpty()) {
			LogThrottle.warn("fast_fluid_eject_return",
					"流体目标拒收后有 {} mB 无法放回源槽", notReturned.getAmount());
		}
	}

	/** 计算无分配内容指纹；退避期间仍能在产出变化的下一刻立即恢复。 */
	private long sampleContents(ConfigInfo fluidConfig) {
		long fingerprint = 1L;
		long total = 0L;
		for (DataType dataType : fluidConfig.getSupportedDataTypes()) {
			if (!dataType.canOutput()) continue;
			ISlotInfo slotInfo = fluidConfig.getSlotInfo(dataType);
			if (!(slotInfo instanceof FluidSlotInfo fluidSlotInfo) || slotInfo.isEmpty()) continue;
			List<IExtendedFluidTank> tanks = fluidSlotInfo.getTanks();
			for (int i = 0; i < tanks.size(); i++) {
				IExtendedFluidTank tank = tanks.get(i);
				if (tank == null || tank.isEmpty()) continue;
				FluidStack stack = tank.getFluid();
				int amount = Math.max(0, stack.getAmount());
				total += amount;
				fingerprint = 31L * fingerprint + System.identityHashCode(stack.getFluid());
				fingerprint = 31L * fingerprint + amount;
				if (!stack.isComponentsPatchEmpty()) {
					fingerprint = 31L * fingerprint + stack.getComponents().hashCode();
				}
			}
		}
		sampledAmount = total;
		return fingerprint;
	}

	private void clearBackoff() {
		backoffUntilTick = Long.MIN_VALUE;
		backoffFingerprint = Long.MIN_VALUE;
	}
}
