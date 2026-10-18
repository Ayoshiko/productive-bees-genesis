package com.ayoshiko.productivebeesgenesis.resource.emc;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.resource.ResourceOutputAccess;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.resource.ResourceScalarOutput;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import moze_intel.projecte.api.capabilities.block_entity.IEmcStorage;
import net.minecraft.core.Direction;
import java.math.BigInteger;
import net.minecraft.server.level.ServerLevel;

/** 独立 EMC 产物能力；只出不入，并向邻接 ProjectE 接收者提交账户交接。 */
public final class ResourceEmcOutput implements IEmcStorage {
	private static final Direction[] SIDES = Direction.values();
	private ResourceOutputAccess port;
	private final ResourceScalarOutput access;
	private long nextAttempt;
	private int nextSide;
	private boolean reportedFailure;
    private IEmcStorage capability;

	public ResourceEmcOutput(ResourceOutputAccess port, ResourceScalarOutput access) {
		this.port = port;
		this.access = access;
	}

	public void invalidate() {
		if (port != null && port.getLevel() instanceof ServerLevel level) {
			ResourceRuntime.releaseNativeOutput(level.getServer(), this);
		}
		port = null;
		access.invalidate();
	}
	public BigInteger storedExact() { return access.storedExact(); }
    public BigInteger extractExact(BigInteger amount, EmcAction action) { return access.extractExact(amount, action.simulate()); }
    public IEmcStorage capability() {
        if (capability == null) capability = ResourceEmcBigIntegerBridge.capability(this);
        return capability;
    }
    @Override public long getStoredEmc() { return access.stored(); }
	@Override public long getMaximumEmc() { return Long.MAX_VALUE; }
	@Override public long getNeededEmc() { return 0; }
	@Override public long insertEmc(long amount, EmcAction action) { return 0; }
	@Override public long extractEmc(long amount, EmcAction action) {
		return access.extract(amount, java.util.Objects.requireNonNull(action).simulate());
	}

	/** 每次只查询一面；所有接口共享服务器预算，第三方接收异常由账户保留未决量。 */
	public void tick() {
		var current = port;
		if (current == null || !(current.getLevel() instanceof ServerLevel level)
				|| !level.getServer().isSameThread()) return;
		long now = level.getGameTime();
		if (now < nextAttempt) return;
		BigInteger stored = access.storedExact();
		if (stored.signum() <= 0) {
			ResourceRuntime.releaseNativeOutput(level.getServer(), this);
			nextAttempt = now + 20;
			return;
		}
		if (!ResourceRuntime.claimNativeOutput(level.getServer(), this)) return;
		nextAttempt = now + 4;
		try {
			var host = current.sourceHost();
			if (host == null) return;
			if (ResourceEmcPlayerOutput.canReceive(host)) {
				ResourceEmcPlayerOutput.transfer(current, stored);
				reportedFailure = false;
				return;
			}
			if (ResourceEmcPlayerOutput.hasRecipient(host)) return;
			Direction side = SIDES[nextSide++ % SIDES.length];
			if (nextSide == SIDES.length) nextSide = 0;
			var pos = current.getBlockPos().relative(side);
			if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
			var receiver = ResourceEmcBigIntegerBridge.receiver(level, pos, side.getOpposite());
			if (receiver == null || receiver == this || receiver == capability) return;
			if (port != current || host == null) return;
			BigInteger offered = ResourceEmcBigIntegerBridge.accepts(receiver) ? stored : stored.min(BigInteger.valueOf(Long.MAX_VALUE));
            ResourceHostApi.transferExact(host, ResourceTypes.EMC, offered, offer -> {
				if (port != current || current.sourceHost() != host) {
					throw new IllegalStateException("EMC output interface changed before transfer");
				}
				BigInteger accepted = ResourceEmcBigIntegerBridge.insert(receiver, offer.amount(), EmcAction.EXECUTE);
				if (port != current || current.sourceHost() != host) {
					throw new IllegalStateException("EMC output interface changed during transfer");
				}
				return accepted;
			});
			reportedFailure = false;
		} catch (RuntimeException | LinkageError failure) {
			nextAttempt = now + 200;
			if (!reportedFailure) {
				reportedFailure = true;
				ProductiveBeesGenesis.LOGGER.error("EMC 产物输出失败，位置 {}，保留未决结果", current.getBlockPos(), failure);
			}
		}
	}
}
