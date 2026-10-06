package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.AECapabilities;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.*;
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
	private boolean closed;
	public MeBridgeNode(MeBridgeBlockEntity bridge, CompoundTag saved) {
		this.bridge = bridge;
		node = GridHelper.createManagedNode(bridge, (host, ignored) -> host.setChanged())
				.setTagName("node").setInWorldNode(true).setFlags(GridFlags.REQUIRE_CHANNEL)
				.setIdlePowerUsage(1).setExposedOnSides(EnumSet.allOf(Direction.class)).setVisualRepresentation(NetworkContent.ME_BRIDGE.get());
		node.loadFromNBT(saved);
		node.setOwningPlayerId(IPlayerRegistry.getMapping((ServerLevel) bridge.getLevel()).getPlayerId(bridge.owner()));
	}
	public static void register(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, NetworkContent.ME_BRIDGE_TILE.get(),
				(bridge, context) -> bridge.link() instanceof MeBridgeNode node ? node : null);
	}
	@Override public void connect() { if (!closed && bridge.currentLink(this)) node.create(bridge.getLevel(), bridge.getBlockPos()); }
	@Override public IGridNode getGridNode(Direction side) { return !closed && bridge.currentLink(this) ? node.getNode() : null; }
	@Override public MeBridgeStatus status() {
		if (closed || !bridge.currentLink(this)) return MeBridgeStatus.HOST_UNAVAILABLE;
		return !node.isPowered() ? MeBridgeStatus.OFFLINE : !node.hasGridBooted() ? MeBridgeStatus.BOOTING : !node.isActive() ? MeBridgeStatus.NO_CHANNEL : MeBridgeStatus.ONLINE;
	}
	@Override public CompoundTag save() { var saved = new CompoundTag(); node.saveToNBT(saved); return saved; }
	@Override public void close() { if (!closed) { closed = true; node.destroy(); } }
}
