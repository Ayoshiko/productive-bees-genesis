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
	private final TileEntityMekanism owner;
	private int tankCursor;
	private int sideCursor;
	private int consecutiveIdleTicks;
	private long lastAttemptGameTime = Long.MIN_VALUE;
	private long backoffUntilTick = Long.MIN_VALUE;
	private long backoffFingerprint = Long.MIN_VALUE;
	private long sampledAmount;

	public FastFluidEjector(TileEntityMekanism tile) {
		this.targets = new NeighborFluidTargets(tile);
		this.owner = tile;
	}

	public void onConfigChanged() {
		targets.invalidate();
		clearBackoff();
	}

	/** 批量弹出所有可提取流体；调用方应在本模组机器上用本方法替代原版 FLUID eject。 */
	public void tick(TileEntityMekanism tile, @Nullable ConfigInfo fluidConfig, long gameTime) {
		if (OutputCustody.of(owner).externalOutputBlocked() || fluidConfig == null || lastAttemptGameTime == gameTime) return;
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
				OutputCustody custody = OutputCustody.of(owner);
				if (custody.externalOutputBlocked()) break;
				int tankIndex = RoundRobinSlotTraversal.index(tankStart, tankOffset, tankCount);
				IExtendedFluidTank tank = tanks.get(tankIndex);
				try {
					if (custody.localFluidBlocked(tankIndex) || tank == null || tank.isEmpty()) continue;
					sampledAmount += Math.max(0, tank.getFluidAmount());
				} catch (RuntimeException | LinkageError error) {
					LogThrottle.error("neighbor_fluid_source_prepare", "流体弹出源罐读取失败，pos={} tank={}，其它罐继续输出",
							owner.getBlockPos(), tankIndex, error);
					continue;
				}
				moved += pushTankToSides(tile, fluidConfig, dataType, tank, tankIndex, sides);
			}
		}
		return moved;
	}

	private long pushTankToSides(TileEntityMekanism tile, ConfigInfo fluidConfig, DataType dataType,
			IExtendedFluidTank tank, int tankIndex, List<Direction> sides) {
		int sideCount = sides.size();
		int sideStart = RoundRobinSlotTraversal.normalize(sideCursor, sideCount);
		sideCursor = RoundRobinSlotTraversal.advance(sideStart, sideCount);
		long moved = 0L;
		for (int offset = 0; offset < sideCount; offset++) {
			OutputCustody custody = OutputCustody.of(owner);
			if (custody.externalOutputBlocked() || custody.localFluidBlocked(tankIndex)) break;
			try {
				if (tank.isEmpty()) break;
			} catch (RuntimeException | LinkageError error) {
				LogThrottle.error("neighbor_fluid_source_prepare", "流体弹出源罐读取失败，pos={} tank={}，其它罐继续输出",
						owner.getBlockPos(), tankIndex, error);
				break;
			}
			Direction side = sides.get(RoundRobinSlotTraversal.index(sideStart, offset, sideCount));
			RelativeSide relativeSide = RelativeSide.fromDirections(tile.getDirection(), side);
			if (fluidConfig.getDataType(relativeSide) != dataType) continue;
			IFluidHandler target;
			try {
				target = targets.handler(side);
			} catch (RuntimeException | LinkageError error) {
				LogThrottle.error("neighbor_fluid_capability", "相邻流体输出能力查询失败，pos={} side={}",
						owner.getBlockPos(), side, error);
				continue;
			}
			if (target != null) moved += transfer(tank, target, custody, tankIndex);
		}
		return moved;
	}

	/** 先模拟目标接收量，再精确抽取；异常目标少收时把余量原样放回源槽。 */
	static int transfer(IExtendedFluidTank source, IFluidHandler target, OutputCustody custody, int tankIndex) {
		if (custody.externalOutputBlocked() || custody.localFluidBlocked(tankIndex)) return 0;
		FluidStack available;
		try {
			available = source.extract(Integer.MAX_VALUE, Action.SIMULATE, AutomationType.EXTERNAL);
		} catch (RuntimeException | LinkageError error) {
			LogThrottle.error("neighbor_fluid_source_prepare", "流体弹出源罐模拟抽取失败，tank={}，未扣源，待下次重试", tankIndex, error);
			return 0;
		}
		if (available.isEmpty()) return 0;
		int accepted;
		try {
			accepted = Math.max(0, Math.min(available.getAmount(), target.fill(available.copy(), IFluidHandler.FluidAction.SIMULATE)));
		} catch (RuntimeException | LinkageError error) {
			LogThrottle.error("neighbor_fluid_simulation", "相邻流体输出模拟失败，tank={}，未扣源，待下次重试", tankIndex, error);
			return 0;
		}
		if (accepted == 0) return 0;
		FluidStack extracted = source.extract(accepted, Action.EXECUTE, AutomationType.EXTERNAL);
		if (extracted.isEmpty()) return 0;
		int handed;
		try {
			handed = (int) custody.handoffFluid(extracted, extracted.getAmount(), "neighbor-fluid",
					() -> target.fill(extracted.copy(), IFluidHandler.FluidAction.EXECUTE));
		} catch (RuntimeException | LinkageError failure) {
			handed = 0;
			LogThrottle.error("neighbor_fluid_handoff_preparation", "流体外送登记失败，尚未调用接收方；已取流体原样回填，tank={} amount={}",
					tankIndex, extracted.getAmount(), failure);
		}
		int remaining = extracted.getAmount() - handed;
		if (remaining > 0) {
			FluidStack rest = extracted.copyWithAmount(remaining);
			int returned = 0;
			try {
				FluidStack before = source.getFluid();
				returned = (int) custody.localFluid(tankIndex).handoffLocalFluid(rest, remaining, before,
						"return-fluid:" + tankIndex, source::getFluid, () -> {
					FluidStack rejected = source.insert(rest.copy(), Action.EXECUTE, AutomationType.INTERNAL);
					if (!rejected.isEmpty() && !FluidStack.isSameFluidSameComponents(rest, rejected))
						throw new IllegalStateException("Invalid fluid return");
					return rest.getAmount() - rejected.getAmount();
				});
			} catch (RuntimeException | LinkageError error) {
				LogThrottle.error("neighbor_fluid_return_prepare", "流体余量回填准备失败，tank={}，已抽取余量继续保管", tankIndex, error);
			}
			if (returned < remaining) custody.retainFluid(rest, remaining - returned, "return-fluid-full");
		}
		return handed;
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
				try {
					if (OutputCustody.of(owner).localFluidBlocked(i) || tank == null || tank.isEmpty()) continue;
					FluidStack stack = tank.getFluid();
					int amount = Math.max(0, stack.getAmount());
					total += amount;
					fingerprint = 31L * fingerprint + System.identityHashCode(stack.getFluid());
					fingerprint = 31L * fingerprint + amount;
					if (!stack.isComponentsPatchEmpty()) {
						fingerprint = 31L * fingerprint + stack.getComponents().hashCode();
					}
				} catch (RuntimeException | LinkageError error) {
					LogThrottle.error("neighbor_fluid_source_prepare", "流体输出库存采样失败，pos={} tank={}，其它罐继续采样",
							owner.getBlockPos(), i, error);
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
