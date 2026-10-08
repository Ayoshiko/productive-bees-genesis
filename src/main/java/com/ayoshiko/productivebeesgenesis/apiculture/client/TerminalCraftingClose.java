package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalClientState;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/** 主动关菜单之前发送一次返还意图；切换子屏的 removed 不触发资产操作。 */
public final class TerminalCraftingClose {
	public static void request(AbstractContainerMenu menu, TerminalClientState state, long generation) {
		var player = Minecraft.getInstance().player;
		if (player == null || player.containerMenu != menu || generation == 0 || state.waiting()
				|| !ModConfig.CLIENT.terminalPreferences.returnCraftingOnClose.get()) return;
		var request = state.beginCrafting(TerminalRequest.Operation.CRAFT_RETURN_ON_CLOSE, generation, -1, -1, 0, Util.getMillis());
		if (request != null) PacketDistributor.sendToServer(request);
	}
	private TerminalCraftingClose() { }
}
