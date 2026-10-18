package com.ayoshiko.productivebeesgenesis.resource.experience;

import com.ayoshiko.productivebeesgenesis.resource.ResourceScalarOutput;
import es.degrassi.experiencelib.api.capability.IExperienceHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** 单槽 XP 点数提取视图；ExperienceLib 的可写接口不授予账户写权限。 */
public final class ResourceExperienceOutput implements IExperienceHandler {
	private final ResourceScalarOutput access;
	public ResourceExperienceOutput(ResourceScalarOutput access) { this.access = java.util.Objects.requireNonNull(access); }
	@Override public int getTanks() { return 1; }
	@Override public boolean canAcceptExperience(int tank, long amount) { return false; }
	@Override public boolean canProvideExperience(int tank, long amount) {
		return tank == 0 && amount > 0 && access.stored() >= amount;
	}
	@Override public long getMaxExtract(int tank) { return tank == 0 && access.canExtract() ? Long.MAX_VALUE : 0; }
	@Override public long getMaxReceive(int tank) { return 0; }
	@Override public long receiveExperience(int tank, long amount, boolean simulate) { return 0; }
	@Override public long receiveExperienceRecipe(int tank, long amount, boolean simulate) { return 0; }
	@Override public long extractExperience(int tank, long amount, boolean simulate) {
		return tank == 0 ? access.extract(amount, simulate) : 0;
	}
	@Override public long extractExperienceRecipe(int tank, long amount, boolean simulate) {
		return extractExperience(tank, amount, simulate);
	}
	@Override public long getExperience() { return access.stored(); }
	@Override public long getExperienceCapacity() { return access.capacity(); }
	// 能力仅是账户视图：外部 setters 与 NBT 不能创建、清空或复制权威库存。
	@Override public void setExperience(int tank, long amount) {}
	@Override public void setCapacity(int tank, long amount) {}
	@Override public CompoundTag serializeNBT(HolderLookup.Provider registries) { return new CompoundTag(); }
	@Override public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {}
}
