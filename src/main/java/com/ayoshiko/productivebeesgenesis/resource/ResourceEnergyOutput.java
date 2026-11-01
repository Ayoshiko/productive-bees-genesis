package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraftforge.energy.IEnergyStorage;

/** FE 产物的只出不入投影；long 余额仅在 NeoForge int 能力边界分段，不接触机器电池。 */
final class ResourceEnergyOutput implements IEnergyStorage {
	private final ResourceScalarOutput access;

	ResourceEnergyOutput(ResourceOutputAccess port) { access = new ResourceScalarOutput(port, ResourceTypes.FE); }

	void invalidate() { access.invalidate(); }

	@Override public int receiveEnergy(int maximum, boolean simulate) { return 0; }
	@Override public int extractEnergy(int maximum, boolean simulate) { return (int) access.extract(maximum, simulate); }
	@Override public int getEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, access.stored()); }
	@Override public int getMaxEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, access.capacity()); }
	@Override public boolean canExtract() { return access.canExtract(); }
	@Override public boolean canReceive() { return false; }
}
