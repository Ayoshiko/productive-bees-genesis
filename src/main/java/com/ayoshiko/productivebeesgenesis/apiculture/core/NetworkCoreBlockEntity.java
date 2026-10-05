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
	private CoreAccessState access = new CoreAccessState();
	private UUID controller = UUID.randomUUID();
	private int closedFaces;
	private TopologyScan.View topology;
	private NetworkIdentity network;
	private boolean invalidNetwork;
	private CompoundTag invalidNetworkData;
	private CoreOwnershipController ownership = new CoreOwnershipController(this);
	private com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkCoreEnergyPort energyPort;
	private int productionMode;
	private final CoreApiaryIndex apiaryIndex = new CoreApiaryIndex();
	CoreApiaryIndex apiaryIndex() { return apiaryIndex; }
	private final com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex quantityIndex = new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex();
	com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex quantityIndex() { return quantityIndex; }
	private com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntime runtime;
	public NetworkCoreBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.CORE_TILE.get(), pos, state); }
	public UUID owner() { return owner; }
	public UUID controller() { return controller; }
	public int closedFaces() { return closedFaces; }
	public NetworkIdentity network() { return network; }
	public boolean validNetworkReference() { return !invalidNetwork; }
	public CoreOwnershipController ownership() { return ownership; }
	public boolean productionRunning() { return productionMode == 1; }
	public boolean hasProductionSession() { return productionMode != 0; }
	public com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntime runtime() {
		if (runtime == null) runtime = new com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntime();
		return runtime;
	}
	public boolean setProductionRunning(boolean running) {
		if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || isRemoved()
				|| !server.hasChunk(worldPosition.getX() >> 4, worldPosition.getZ() >> 4) || server.getBlockEntity(worldPosition) != this || network == null || invalidNetwork) return false;
		if (running && (!ModConfig.SERVER.beeNetwork.enabled.get() || ownership.readyAuthority() == null || topology() == null || !topology().valid())) return false;
		productionMode = running ? 1 : 2; setChanged();
		com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntimeService.watch(this, true); return true;
	}
	public void bindNetwork(NetworkIdentity value) { if (network != null || invalidNetwork) throw new IllegalStateException("Core already bound"); network = value; energyPort = null; invalidateCapabilities(); setChanged(); }
	public com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkCoreEnergyPort energyPort() {
		if (energyPort == null) energyPort = new com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkCoreEnergyPort(this);
		return energyPort;
	}
	public void initializeOwner(UUID player) { if (owner == null) { owner = player; setChanged(); requestRebuild(); } }
	private boolean usableBy(Player player) {
		return level instanceof ServerLevel server && server.getServer().isSameThread()
				&& !isRemoved() && player.level() == level && player.isAlive() && !player.isSpectator()
				&& player.distanceToSqr(worldPosition.getCenter()) <= 64
				&& server.hasChunk(worldPosition.getX() >> 4, worldPosition.getZ() >> 4)
				&& server.getBlockEntity(worldPosition) == this;
	}
	boolean permits(Player player) { return owner != null && (owner.equals(player.getUUID()) || access.allows(player.getUUID())); }
	boolean permitsUpgrades(Player player) { return owner != null && (owner.equals(player.getUUID()) || access.allowsUpgrades(player.getUUID())); }
	public boolean allowed(Player player) { return usableBy(player) && permits(player); }
	public boolean ownerAllowed(Player player) { return usableBy(player) && owner != null && owner.equals(player.getUUID()); }
	Object accessToken() { return access.sessionToken(); }
	public CoreAccessState.Change changeGuest(net.minecraft.server.level.ServerPlayer player, UUID target, boolean grant) {
		if (!ownerAllowed(player)) return CoreAccessState.Change.DENIED;
		var result = access.change(owner, target, grant);
		if (result == CoreAccessState.Change.CHANGED) setChanged();
		return result;
	}
	public CoreAccessState.Change changeUpgradeGuest(net.minecraft.server.level.ServerPlayer player, UUID target, boolean grant) {
		if (!ownerAllowed(player)) return CoreAccessState.Change.DENIED;
		var result = access.changeUpgrades(owner, target, grant);
		if (result == CoreAccessState.Change.CHANGED) setChanged();
		return result;
	}
	public java.util.List<UUID> upgradeGuests(net.minecraft.server.level.ServerPlayer player) {
		return ownerAllowed(player) && access.valid() ? access.upgradeGuests() : null;
	}
	public java.util.List<UUID> guests(net.minecraft.server.level.ServerPlayer player) {
		return ownerAllowed(player) && access.valid() ? access.guests() : null;
	}
	public void toggleFace(Direction face) { closedFaces ^= 1 << face.ordinal(); setChanged(); requestRebuild(); }
	public void requestRebuild() { if (level instanceof ServerLevel server) NetworkTopologyService.dirty(server, worldPosition); }
	public void serverTick() {
		if (owner == null || invalidNetwork) return;
		NetworkOwnershipService.watch(this);
		com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntimeService.watch(this, false);
		if (!ModConfig.SERVER.beeNetwork.enabled.get() && network == null) return;
		NetworkTopologyService.watch(this);
	}
	public TopologyScan.View topology() {
		if (!(level instanceof ServerLevel server) || topology == null || topology.epoch() != NetworkTopologyService.epoch(server, worldPosition)) return null;
		return topology;
	}
	public void publishTopology(TopologyScan.View view) { topology = view; }
	@Override public void setRemoved() {
		apiaryIndex.clear(); quantityIndex.clear();
		if (level instanceof ServerLevel server) {
			NetworkTopologyService.remove(server, this);
			com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntimeService.remove(server, this);
		}
		super.setRemoved();
	}
	@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries); tag.putUUID("controller", controller); tag.putInt("closedFaces", closedFaces); if (owner != null) tag.putUUID("owner", owner);
		tag.putInt("productionMode", productionMode);
		var accessTag = access.save(owner, controller); if (accessTag != null) tag.put("access", accessTag);
		if (network != null) tag.put("network", NetworkCheckpointCodec.identity(network));
		else if (invalidNetworkData != null) tag.put("network", invalidNetworkData.copy());
		if (invalidNetwork) tag.putBoolean("invalidNetwork", true);
	}
	@Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		controller = tag.hasUUID("controller") ? tag.getUUID("controller") : UUID.randomUUID(); owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
		access = CoreAccessState.read(tag.get("access"), owner, controller);
		if (!access.valid()) com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.LOGGER.error(
				"Invalid network core access at {}: {}; original data retained", worldPosition, access.failure());
		closedFaces = tag.getInt("closedFaces") & 63; topology = null;
		invalidNetwork = tag.getBoolean("invalidNetwork"); network = null; invalidNetworkData = null;
		if (tag.contains("network")) {
			try { network = NetworkCheckpointCodec.readIdentity(tag.getCompound("network")); }
			catch (RuntimeException failure) { invalidNetwork = true; invalidNetworkData = tag.getCompound("network").copy(); }
		}
		ownership = new CoreOwnershipController(this);
		apiaryIndex.clear(); quantityIndex.clear();
		energyPort = null;
		int mode = tag.getInt("productionMode"); productionMode = mode >= 0 && mode <= 2 ? mode : 2; runtime = null;
	}
	@Override public Component getDisplayName() { return Component.translatable("block.productivebeesgenesis.bee_network_core"); }
	@Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return allowed(player) ? new NetworkCoreMenu(id, inventory, this) : null; }
	/** 会话 UUID 随原版开菜单数据传输，旧 containerId 的迟到回复不能绑定新菜单。 */
	public void openTerminal(net.minecraft.server.level.ServerPlayer player) {
		if (!allowed(player)) return;
		var session = java.util.UUID.randomUUID();
		player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, viewer) ->
				allowed(viewer) ? new NetworkCoreMenu(id, inventory, this, session) : null, getDisplayName()),
				buffer -> { buffer.writeBlockPos(worldPosition); buffer.writeUUID(session); buffer.writeBoolean(false); });
	}
}
