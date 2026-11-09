package com.ayoshiko.productivebeesgenesis.resource.ars;

import com.ayoshiko.productivebeesgenesis.resource.ResourceScalarOutput;
import com.hollingsworth.arsnouveau.api.source.ISourceCap;

/** Ars 原生魔源只出不入能力；setter 不得改写账户余额或容量。 */
public final class ResourceSourceOutput implements ISourceCap {
	private final ResourceScalarOutput access;

	/** 由有 Ars 加载守卫的产物口创建，所有权限与生命周期检查由核心视图完成。 */
	public ResourceSourceOutput(ResourceScalarOutput access) { this.access = access; }

	@Override public boolean canAcceptSource(int amount) { return false; }
	@Override public boolean canProvideSource(int amount) { return amount > 0 && access.stored() > 0; }
	@Override public int getMaxExtract() { return access.canExtract() ? Integer.MAX_VALUE : 0; }
	@Override public int getMaxReceive() { return 0; }
	@Override public int getSource() { return (int) Math.min(Integer.MAX_VALUE, access.stored()); }
	@Override public int getSourceCapacity() { return (int) Math.min(Integer.MAX_VALUE, access.capacity()); }
	@Override public void setSource(int amount) {}
	@Override public void setMaxSource(int amount) {}
	@Override public int receiveSource(int amount, boolean simulate) { return 0; }
	@Override public int extractSource(int amount, boolean simulate) { return (int) access.extract(amount, simulate); }
}
