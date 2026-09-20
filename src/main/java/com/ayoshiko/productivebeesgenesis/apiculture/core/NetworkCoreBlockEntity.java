package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.topology.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 核心身份及只读结构视图；账户和转移意图由世界级权威域保存。 */
public final class NetworkCoreBlockEntity extends BlockEntity implements MenuProvider {
	private UUID owner;
	private UUID controller = UUID.randomUUID();
	private int closedFaces;
	private TopologyScan.View topology;
	private NetworkIdentity network;
	private boolean invalidNetwork;
	private CompoundTag invalidNetworkData;
	private CoreOwnershipController ownership = new CoreOwnershipController(this);
	private com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkCoreEnergyPort energyPort;
	public NetworkCoreBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.CORE_TILE.get(), pos, state); }
	public UUID owner() { return owner; }
	public UUID controller() { return controller; }
	public int closedFaces() { return closedFaces; }
	public NetworkIdentity network() { return network; }
	public boolean validNetworkReference() { return !invalidNetwork; }
	public CoreOwnershipController ownership() { return ownership; }
	public void bindNetwork(NetworkIdentity value) { if (network != null || invalidNetwork) throw new IllegalStateException("Core already bound"); network = value; energyPort = null; invalidateCapabilities(); setChanged(); }
	public com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkCoreEnergyPort energyPort() {
		if (energyPort == null) energyPort = new com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkCoreEnergyPort(this);
		return energyPort;
	}
	public void initializeOwner(UUID player) { if (owner == null) { owner = player; setChanged(); requestRebuild(); } }
	public boolean allowed(Player player) { return owner != null && owner.equals(player.getUUID()) && !isRemoved() && player.distanceToSqr(worldPosition.getCenter()) <= 64; }
	public void toggleFace(Direction face) { closedFaces ^= 1 << face.ordinal(); setChanged(); requestRebuild(); }
	public void requestRebuild() { if (level instanceof ServerLevel server) NetworkTopologyService.dirty(server, worldPosition); }
	public void serverTick() {
		if (owner == null || invalidNetwork) return;
		NetworkOwnershipService.watch(this);
		if (!ModConfig.SERVER.beeNetwork.enabled.get() && network == null) return;
		NetworkTopologyService.watch(this);
	}
	public TopologyScan.View topology() {
		if (!(level instanceof ServerLevel server) || topology == null || topology.epoch() != NetworkTopologyService.epoch(server, worldPosition)) return null;
		return topology;
	}
	public void publishTopology(TopologyScan.View view) { topology = view; }
	@Override public void setRemoved() {
		if (level instanceof ServerLevel server) NetworkTopologyService.remove(server, this);
		super.setRemoved();
	}
	@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries); tag.putUUID("controller", controller); tag.putInt("closedFaces", closedFaces); if (owner != null) tag.putUUID("owner", owner);
		if (network != null) tag.put("network", NetworkCheckpointCodec.identity(network));
		else if (invalidNetworkData != null) tag.put("network", invalidNetworkData.copy());
		if (invalidNetwork) tag.putBoolean("invalidNetwork", true);
	}
	@Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		controller = tag.hasUUID("controller") ? tag.getUUID("controller") : UUID.randomUUID(); owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
		closedFaces = tag.getInt("closedFaces") & 63; topology = null;
		invalidNetwork = tag.getBoolean("invalidNetwork"); network = null; invalidNetworkData = null;
		if (tag.contains("network")) {
			try { network = NetworkCheckpointCodec.readIdentity(tag.getCompound("network")); }
			catch (RuntimeException failure) { invalidNetwork = true; invalidNetworkData = tag.getCompound("network").copy(); }
		}
		ownership = new CoreOwnershipController(this);
		energyPort = null;
	}
	@Override public Component getDisplayName() { return Component.translatable("block.productivebeesgenesis.bee_network_core"); }
	@Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return allowed(player) ? new NetworkCoreMenu(id, inventory, this) : null; }
}
