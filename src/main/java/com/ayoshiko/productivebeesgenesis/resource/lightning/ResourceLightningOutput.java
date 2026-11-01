package com.ayoshiko.productivebeesgenesis.resource.lightning;

import com.ayoshiko.productivebeesgenesis.resource.ResourceScalarOutput;
import com.moakiee.ae2lt.api.lightning.ILightningEnergyHandler;
import com.moakiee.ae2lt.api.lightning.LightningTier;

/** 电压身份严格分离的只出不入视图；撤销底层视图同时撤销旧能力。 */
public final class ResourceLightningOutput implements ILightningEnergyHandler {
	private final ResourceScalarOutput high;
	private final ResourceScalarOutput extreme;
	public ResourceLightningOutput(ResourceScalarOutput high, ResourceScalarOutput extreme) {
		this.high = java.util.Objects.requireNonNull(high);
		this.extreme = java.util.Objects.requireNonNull(extreme);
	}
	private ResourceScalarOutput access(LightningTier tier) {
		if (tier == LightningTier.HIGH_VOLTAGE) return high;
		if (tier == LightningTier.EXTREME_HIGH_VOLTAGE) return extreme;
		return null;
	}
	@Override public long getStored(LightningTier tier) {
		var output = access(tier); return output == null ? 0 : output.stored();
	}
	@Override public long getCapacity(LightningTier tier) {
		var output = access(tier); return output == null ? 0 : output.capacity();
	}
	@Override public long insert(LightningTier tier, long amount, boolean simulate) { return 0; }
	@Override public boolean canInsert(LightningTier tier) { return false; }
	@Override public boolean canExtract(LightningTier tier) {
		var output = access(tier); return output != null && output.canExtract();
	}
	@Override public long extract(LightningTier tier, long amount, boolean simulate) {
		var output = access(tier); return output == null ? 0 : output.extract(amount, simulate);
	}
}
