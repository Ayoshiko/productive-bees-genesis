package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import com.ayoshiko.productivebeesgenesis.multiblock.world.WirelessMachineAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 单次世界操作封存原宿主与权威代际；不要求存在 ME 桥或可选模组。 */
record WirelessHostAccess(WirelessDeviceSession device, BlockEntity host, Object authority) {
	static WirelessHostAccess capture(ServerPlayer player, WirelessDeviceSession device) {
		if (!device.valid(player)) return null;
		var position = device.binding().position();
		var chunk = player.serverLevel().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
		var host = chunk == null ? null : chunk.getBlockEntity(position);
		Object authority = host instanceof NetworkCoreBlockEntity core ? core.ownership().readyAuthority() : null;
		var access = new WirelessHostAccess(device, host, authority);
		return access.valid(player) ? access : null;
	}
	boolean valid(ServerPlayer player) {
		if (!device.valid(player) || host == null) return false;
		if (host instanceof NetworkCoreBlockEntity core) {
			var network = device.binding().network();
			return network != null && authority != null && MeBridgeTarget.live(core) && core.permits(player) && core.validNetworkReference()
					&& network.equals(core.network()) && network.controllerId().equals(core.controller())
					&& core.ownership().readyAuthority() == authority && core.ownership().readyAuthority().identity().equals(network);
		}
		return host instanceof MachineControllerEntity core && WirelessMachineAccess.valid(core, device, player);
	}
}
