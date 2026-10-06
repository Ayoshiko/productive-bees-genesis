package com.ayoshiko.productivebeesgenesis.apiculture.me;

import net.minecraft.server.level.ServerPlayer;

public interface MeTerminalHost {
	MeTerminalSession meTerminal();
	void meRequest(ServerPlayer player, MeTerminalRequest request);
}
