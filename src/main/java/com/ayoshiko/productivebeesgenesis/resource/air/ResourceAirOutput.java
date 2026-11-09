package com.ayoshiko.productivebeesgenesis.resource.air;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.resource.*;
import me.desht.pneumaticcraft.api.PNCCapabilities;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** 单向主动供气；不暴露会吞掉管网回流的伪造 IAirHandler，也不持有第二份空气库存。 */
public final class ResourceAirOutput {
	private static final Direction[] SIDES = Direction.values();
	private ResourceOutputAccess port;
	private final ResourceScalarOutput access;
	private long nextAttempt;
	private int nextSide;
	private boolean reportedFailure;

	public ResourceAirOutput(ResourceOutputAccess port) {
		this.port = port;
		access = new ResourceScalarOutput(port, ResourceTypes.AIR);
	}

	public static boolean canReceive(ServerLevel level, net.minecraft.core.BlockPos pos, Direction side) {
		var receiver = level.getCapability(PNCCapabilities.AIR_HANDLER_MACHINE, pos, side);
		return receiver != null && Float.isFinite(receiver.getPressure())
				&& receiver.getPressure() < receiver.getDangerPressure()
				&& ResourceAirTransfer.room(receiver.getAir(), receiver.getVolume(),
						receiver.getDangerPressure(), receiver.getCriticalPressure()) > 0;
	}

	public void invalidate() {
		if (port != null && port.getLevel() instanceof ServerLevel level) {
			ResourceRuntime.releaseNativeOutput(level.getServer(), this);
		}
		port = null;
		access.invalidate();
	}

    public void changed() { nextAttempt = 0; }
    public static ResourceNativeOutput.Receiver receiver(ServerLevel level, net.minecraft.core.BlockPos pos, Direction side) {
        var receiver = level.getCapability(PNCCapabilities.AIR_HANDLER_MACHINE, pos, side);
        if (receiver == null) return null;
        return new ResourceNativeOutput.Receiver() {
            private int before;
            public long simulate(long maximum) {
                before = receiver.getAir();
                float pressure = receiver.getPressure();
                if (!Float.isFinite(pressure) || pressure >= receiver.getDangerPressure()) return 0;
                return Math.min(maximum, ResourceAirTransfer.room(before, receiver.getVolume(),
                        receiver.getDangerPressure(), receiver.getCriticalPressure()));
            }
            public long execute(long amount) {
                long result = ResourceAirTransfer.deliver(before, amount, receiver::getAir, receiver::addAir);
                var target = level.getBlockEntity(pos);
                if (target != null) target.setChanged();
                return result;
            }
            public void validate() {
                if (level.getCapability(PNCCapabilities.AIR_HANDLER_MACHINE, pos, side) != receiver) {
                    throw new IllegalStateException("Air receiver changed");
                }
            }
        };
    }

	/** 与 EMC 共用全服预算，每次只探测一面；同刻加速不能重复领预算或多次出账。 */
	public void tick() {
		var current = port;
		if (current == null || !(current.getLevel() instanceof ServerLevel level)
				|| !level.getServer().isSameThread()) return;
		long now = level.getGameTime();
		if (now < nextAttempt) return;
		var host = current.sourceHost();
        var state = host == null ? null : ResourceHiveHost.existing(host);
        if (state == null || !state.nativeConfig().eject(ResourceNativeType.AIR)
                || state.nativeConfig().mask(ResourceNativeType.AIR) == 0) {
            ResourceRuntime.releaseNativeOutput(level.getServer(), this);
            return;
        }
        long stored = access.stored();
		if (stored <= 0) {
			ResourceRuntime.releaseNativeOutput(level.getServer(), this);
			nextAttempt = now + 20;
			return;
		}
		if (!ResourceRuntime.claimNativeOutput(level.getServer(), this)) return;
		nextAttempt = now + 4;
		Direction side = SIDES[nextSide];
		for (int i = 0; i < SIDES.length && !state.nativeOutput(host, ResourceNativeType.AIR, side); i++) {
			nextSide = (nextSide + 1) % SIDES.length;
			side = SIDES[nextSide];
		}
		nextSide = (nextSide + 1) % SIDES.length;
		var pos = current.getBlockPos().relative(side);
		if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
		try {
            if (host == null) return;
            ResourceNativeOutput.transfer(host, ResourceTypes.AIR, side);
			reportedFailure = false;
		} catch (RuntimeException | LinkageError failure) {
			nextAttempt = now + 200;
			if (!reportedFailure) {
				reportedFailure = true;
				ProductiveBeesGenesis.LOGGER.error("空气产物口输出失败，位置 {}，接收位置 {}", current.getBlockPos(), pos, failure);
			}
		}
	}
}
