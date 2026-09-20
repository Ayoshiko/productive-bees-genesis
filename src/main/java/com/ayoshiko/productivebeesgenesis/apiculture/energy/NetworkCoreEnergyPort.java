package com.ayoshiko.productivebeesgenesis.apiculture.energy;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** 稳定的只入 FE 视图；每次使用验证宿主与绑定，缓存的旧能力不能写入另一个核心。 */
public final class NetworkCoreEnergyPort implements IEnergyStorage {
	private final NetworkCoreBlockEntity core;
	private final NetworkIdentity identity;
	private final UUID controller;
	public NetworkCoreEnergyPort(NetworkCoreBlockEntity core) { this.core = core; identity = core.network(); controller = core.controller(); }
	private NetworkSavedData authority() {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || core.isRemoved()
				|| !level.hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4) || level.getBlockEntity(core.getBlockPos()) != core
				|| identity == null || !identity.equals(core.network()) || !controller.equals(core.controller())
				|| !Objects.equals(identity.ownerId(), core.owner()) || !core.validNetworkReference() || !ModConfig.SERVER.beeNetwork.enabled.get()) return null;
		var origin = identity.origin(); var pos = core.getBlockPos();
		if (!origin.dimension().equals(level.dimension().location().toString()) || origin.x() != pos.getX() || origin.y() != pos.getY() || origin.z() != pos.getZ()) return null;
		var topology = core.topology();
		return topology != null && topology.valid() ? core.ownership().readyAuthority() : null;
	}
	@Override public int receiveEnergy(int offered, boolean simulate) {
		if (offered <= 0) return 0;
		var data = authority(); if (data == null) return 0;
		var current = data.checkpoint(); int accepted = (int) current.energy().accept(offered);
		if (accepted > 0 && !simulate) {
			data.publish(current.receiveEnergy(accepted)); NetworkPersistence.directory(((ServerLevel) core.getLevel()).getServer()).requestSave(data);
		}
		return accepted;
	}
	@Override public int extractEnergy(int requested, boolean simulate) { return 0; }
	@Override public int getEnergyStored() { var data = authority(); return data == null ? 0 : (int) Math.min(Integer.MAX_VALUE, data.checkpoint().energy().stored()); }
	@Override public int getMaxEnergyStored() { var data = authority(); return data == null ? 0 : (int) Math.min(Integer.MAX_VALUE, data.checkpoint().energy().capacity()); }
	@Override public boolean canExtract() { return false; }
	@Override public boolean canReceive() { return authority() != null; }
}
