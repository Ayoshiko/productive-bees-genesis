package com.ayoshiko.productivebeesgenesis.apiculture.me;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

public final class MeTerminalPayloads {
	public static void register(RegisterPayloadHandlersEvent event) {
		var r = event.registrar("20").executesOn(HandlerThread.MAIN);
		r.playToServer(MeTerminalRequest.TYPE, MeTerminalRequest.CODEC, (request, context) -> {
			if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof MeTerminalHost host) host.meRequest(player, request);
		});
		r.playToClient(MeTerminalReply.TYPE, MeTerminalReply.CODEC, (reply, context) -> {
			if (context.player().containerMenu instanceof MeTerminalHost host) host.meTerminal().accept(reply);
		});
	}
	private MeTerminalPayloads() { }
}
