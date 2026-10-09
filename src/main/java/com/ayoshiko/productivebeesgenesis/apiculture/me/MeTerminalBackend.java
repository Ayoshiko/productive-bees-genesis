package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;

/** AE2 类型只存在于实现中，菜单仅持有生命周期与有界展示接口。 */
public interface MeTerminalBackend {
	boolean valid(MeBridgeBlockEntity bridge);
	MeTerminalView request(MeTerminalRequest request);
	MeTerminalView planPicked(net.minecraft.world.item.ItemStack target);
	void close();
}
