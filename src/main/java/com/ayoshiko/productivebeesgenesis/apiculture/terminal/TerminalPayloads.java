package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

@EventBusSubscriber(modid = "productivebeesgenesis")
public final class TerminalPayloads {
	private static final ConcurrentHashMap<UUID, TerminalRateBudget> BUDGETS = new ConcurrentHashMap<>();
	@SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
		var registrar = event.registrar("21").executesOn(HandlerThread.MAIN);
		registrar.playToServer(TerminalRecipeRequest.TYPE, TerminalRecipeRequest.STREAM_CODEC, (request, context) -> {
			if (context.player() instanceof ServerPlayer player) {
				var reply = player.containerMenu instanceof NetworkCoreMenu menu ? menu.terminalRecipe(player, request)
						: player.containerMenu instanceof com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu menu ? menu.terminalCrafting(player, request.command(), request.recipe()) : null;
				if (reply != null) PacketDistributor.sendToPlayer(player, reply);
			}
		});
		registrar.playToServer(TerminalRequest.TYPE, TerminalRequest.STREAM_CODEC, (request, context) -> {
			if (context.player() instanceof ServerPlayer player) {
				var reply = handle(player, request);
				if (reply != null) PacketDistributor.sendToPlayer(player, reply);
			}
		});
		registrar.playToClient(TerminalReply.TYPE, TerminalReply.STREAM_CODEC, (reply, context) -> {
			if (context.player().containerMenu instanceof NetworkCoreMenu menu) menu.acceptTerminalReply(reply);
			else if (context.player().containerMenu instanceof com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu menu) menu.acceptTerminalReply(reply);
		});
		registrar.playToClient(TerminalLiveUpdate.TYPE, TerminalLiveUpdate.STREAM_CODEC, (update, context) -> {
			if (context.player().containerMenu instanceof NetworkCoreMenu menu) menu.acceptTerminalUpdate(update);
		});
		registrar.playToServer(TerminalSearchRequest.TYPE, TerminalSearchRequest.STREAM_CODEC, (request, context) -> {
			if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof NetworkCoreMenu menu) {
				var reply = menu.terminalSearch(player, request);
				if (reply != null) PacketDistributor.sendToPlayer(player, reply);
			}
		});
	}
	/** 探针与注册处理器共用此入口；所有访问和预算均在服务器线程。 */
	public static TerminalReply handle(ServerPlayer player, TerminalRequest request) {
		if (!player.serverLevel().getServer().isSameThread()) return null;
		return player.containerMenu instanceof NetworkCoreMenu menu ? menu.terminalRequest(player, request)
				: player.containerMenu instanceof com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu menu ? menu.terminalCrafting(player, request, null) : null;
	}
	/** 序号先消费，限流拒绝不回复也不排队；同一请求以后不能重新执行。 */
	public static boolean allow(ServerPlayer player) {
		if (!player.serverLevel().getServer().isSameThread()) return false;
		return BUDGETS.computeIfAbsent(player.getUUID(), ignored -> new TerminalRateBudget())
				.accept(player.serverLevel().getServer().overworld().getGameTime());
	}
	@SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { BUDGETS.remove(event.getEntity().getUUID()); }
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { BUDGETS.clear(); }
	private TerminalPayloads() { }
}
