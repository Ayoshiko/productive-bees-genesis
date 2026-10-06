package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** 仅展示连接状态，不携带网格或资产权限。 */
public enum MeBridgeStatus {
	ABSENT, DISABLED, NO_BRIDGE, OWNER_ONLY, HOST_UNAVAILABLE, CONFLICT, OFFLINE, BOOTING, NO_CHANNEL, ONLINE, FAILED;
	public Component message() { return Component.translatable("screen.productivebeesgenesis.me_bridge." + name().toLowerCase(Locale.ROOT)); }
	public static MeBridgeStatus decode(int value) { return value >= 0 && value < values().length ? values()[value] : FAILED; }
}
