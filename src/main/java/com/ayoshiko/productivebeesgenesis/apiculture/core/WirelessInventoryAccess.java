package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget;
import net.minecraft.server.level.ServerPlayer;

/** 世界库存操作在原宿主校验上追加固定 ME 桥和精确材料来源。 */
record WirelessInventoryAccess(WirelessHostAccess hostAccess, MeBridgeBlockEntity bridge, TerminalMaterialSource source) {
	static WirelessInventoryAccess capture(ServerPlayer player, WirelessDeviceSession device) {
		var host = WirelessHostAccess.capture(player, device);
		if (host == null) return null;
		var bridge = MeBridgeTarget.resolve(host.host(), player);
		var source = bridge == null ? null : bridge.link().materials(player, null);
		return source != null && source.valid() ? new WirelessInventoryAccess(host, bridge, source) : null;
	}
	boolean valid(ServerPlayer player) {
		return hostAccess.valid(player) && MeBridgeTarget.resolve(hostAccess.host(), player) == bridge && source.valid();
	}
}
