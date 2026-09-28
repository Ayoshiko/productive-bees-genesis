package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 所有者近距离管理单个核心的 UUID 访问表；权限不由 OP 等级替代。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class CoreAccessCommands {
	@SubscribeEvent
	public static void register(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("pbgnetwork")
				.requires(source -> source.getEntity() instanceof ServerPlayer)
				.then(Commands.literal("access").then(Commands.argument("core", BlockPosArgument.blockPos())
						.then(Commands.literal("list").executes(CoreAccessCommands::list))
						.then(Commands.literal("grant").then(Commands.argument("player", UuidArgument.uuid())
								.executes(context -> change(context, true))))
						.then(Commands.literal("revoke").then(Commands.argument("player", UuidArgument.uuid())
								.executes(context -> change(context, false)))))));
	}

	private static NetworkCoreBlockEntity core(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		var source = context.getSource();
		var player = source.getPlayerOrException();
		var pos = BlockPosArgument.getLoadedBlockPos(context, "core");
		if (player.serverLevel().getBlockEntity(pos) instanceof NetworkCoreBlockEntity core && core.ownerAllowed(player)) return core;
		source.sendFailure(Component.translatable("productivebeesgenesis.network.access.denied")); return null;
	}

	private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		var core = core(context); if (core == null) return 0;
		var guests = core.guests(context.getSource().getPlayerOrException());
		if (guests == null) {
			context.getSource().sendFailure(Component.translatable("productivebeesgenesis.network.access.invalid")); return 0;
		}
		context.getSource().sendSuccess(() -> Component.translatable("productivebeesgenesis.network.access.list",
				guests.size(), CoreAccessState.MAX_GUESTS, String.join(", ", guests.stream().map(Object::toString).toList())), false);
		return 1;
	}

	private static int change(CommandContext<CommandSourceStack> context, boolean grant) throws CommandSyntaxException {
		var core = core(context); if (core == null) return 0;
		var target = UuidArgument.getUuid(context, "player");
		var result = core.changeGuest(context.getSource().getPlayerOrException(), target, grant);
		var key = "productivebeesgenesis.network.access." + switch (result) {
			case CHANGED -> grant ? "granted" : "revoked";
			case UNCHANGED -> "unchanged";
			case DENIED -> "denied";
			case FULL -> "full";
			case INVALID -> "invalid";
		};
		if (result == CoreAccessState.Change.CHANGED || result == CoreAccessState.Change.UNCHANGED) {
			context.getSource().sendSuccess(() -> Component.translatable(key, target.toString()), false); return 1;
		}
		context.getSource().sendFailure(Component.translatable(key)); return 0;
	}
	private CoreAccessCommands() { }
}
