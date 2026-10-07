package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.AECapabilities;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.*;
import appeng.api.storage.IStorageProvider;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import java.util.EnumSet;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 此类只在确认 AE2 已安装后加载；创建和销毁均由服务器生命周期驱动。 */
public final class MeBridgeNode implements MeBridgeLink, IInWorldGridNodeHost {
	private final MeBridgeBlockEntity bridge;
	private final IManagedGridNode node;
	private boolean closed, mounted;
	private final MeBridgeStorage storage;
	private com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerCheckpoint observed;
	private IGrid observedGrid;
	public MeBridgeNode(MeBridgeBlockEntity bridge, CompoundTag saved) {
		this.bridge = bridge; storage = new MeBridgeStorage(this, bridge);
		node = GridHelper.createManagedNode(bridge, (host, ignored) -> host.setChanged())
				.setTagName("node").setInWorldNode(true).setFlags(GridFlags.REQUIRE_CHANNEL)
				.addService(IStorageProvider.class, mounts -> { if (storage.authority() != null) mounts.mount(storage, 0); })
				.setIdlePowerUsage(1).setExposedOnSides(EnumSet.allOf(Direction.class)).setVisualRepresentation(NetworkContent.ME_BRIDGE.get());
		node.loadFromNBT(saved);
		node.setOwningPlayerId(IPlayerRegistry.getMapping((ServerLevel) bridge.getLevel()).getPlayerId(bridge.owner()));
	}
	public static void register(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, NetworkContent.ME_BRIDGE_TILE.get(),
				(bridge, context) -> bridge.link() instanceof MeBridgeNode node ? node : null);
	}
	@Override public com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBackend terminal(net.minecraft.server.level.ServerPlayer player) {
		return status() == MeBridgeStatus.ONLINE ? new AeMeTerminal(this, bridge, player) : null;
	}
	@Override public com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalMaterialSource materials(net.minecraft.server.level.ServerPlayer player,
			com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData excluded) {
		return status() == MeBridgeStatus.ONLINE ? new AeRecipeMaterials(this, bridge, player, excluded) : null;
	}
	@Override public void connect() { if (!closed && bridge.currentLink(this)) node.create(bridge.getLevel(), bridge.getBlockPos()); }
	@Override public IGridNode getGridNode(Direction side) { return !closed && bridge.currentLink(this) ? node.getNode() : null; }
	@Override public MeBridgeStatus status() {
		if (closed || !bridge.currentLink(this)) return MeBridgeStatus.HOST_UNAVAILABLE;
		return !node.isPowered() ? MeBridgeStatus.OFFLINE : !node.hasGridBooted() ? MeBridgeStatus.BOOTING : !node.isActive() ? MeBridgeStatus.NO_CHANNEL : MeBridgeStatus.ONLINE;
	}
	@Override public CompoundTag save() { var saved = new CompoundTag(); node.saveToNBT(saved); return saved; }
	public IGrid grid() { var value = node.getNode(); return value == null ? null : value.getGrid(); }
	boolean safeAggregation() {
		var grid = grid(); return grid != null && grid.getStorageService().getInventory() instanceof SafeStorageAggregation safe && safe.pbgSafeAggregationAvailable();
	}
	boolean aggregationFaulted() {
		var grid = grid(); return grid != null && grid.getStorageService().getInventory() instanceof SafeStorageAggregation safe && !safe.pbgSafeAggregationAvailable();
	}
	public MeBridgeStorage storage() { return storage; }
	@Override public boolean storageAvailable() { return storage.authority() != null; }
	/** 显式转移立即失效；通常生产变更在每真实 tick 的这个入口合并。 */
	public void invalidate() {
		try { var grid = grid(); if (grid != null) grid.getStorageService().invalidateCache(); }
		catch (RuntimeException | LinkageError error) { if (!closed) bridge.isolate(error); }
	}
	@Override public void tick() {
		var grid = grid(); var data = storage.authority(); boolean active = data != null;
		if (grid != observedGrid || active != mounted) {
			if (observedGrid != null) observedGrid.getStorageService().invalidateCache();
			observedGrid = grid; mounted = active; observed = null;
			if (!active) storage.clear();
			if (grid != null) { IStorageProvider.requestUpdate(node); invalidate(); }
		}
		var current = data == null ? null : data.checkpoint().ledger();
		if (current != observed) { observed = current; invalidate(); }
	}
	@Override public void storageStep() {
		try { storage.step(); } catch (RuntimeException | LinkageError error) { bridge.isolate(error); }
	}
	@Override public void close() {
		if (!closed) { closed = true; invalidate(); storage.clear(); node.destroy(); if (observedGrid != null) observedGrid.getStorageService().invalidateCache(); observedGrid = null; observed = null; }
	}
}
