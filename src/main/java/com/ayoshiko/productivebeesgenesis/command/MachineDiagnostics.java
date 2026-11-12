package com.ayoshiko.productivebeesgenesis.command;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

final class MachineDiagnostics {

	private MachineDiagnostics() {
	}

	static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("machine")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(context -> report(context.getSource(),
								BlockPosArgument.getLoadedBlockPos(context, "pos"), ""))
						.then(Commands.argument("requestId", StringArgumentType.word())
								.executes(context -> report(context.getSource(),
										BlockPosArgument.getLoadedBlockPos(context, "pos"),
										StringArgumentType.getString(context, "requestId")))));
	}

	private static int report(CommandSourceStack source, BlockPos pos, String requestId) {
		if (!requestId.isEmpty() && !requestId.matches("[0-9a-fA-F]{32}")) {
			source.sendFailure(Component.literal("requestId must contain 32 hexadecimal characters"));
			return 0;
		}
		BlockEntity blockEntity = source.getLevel().getBlockEntity(pos);
		JsonObject result = new JsonObject();
		result.addProperty("dimension", source.getLevel().dimension().location().toString());
		result.addProperty("x", pos.getX());
		result.addProperty("y", pos.getY());
		result.addProperty("z", pos.getZ());
		result.addProperty("gameTick", Long.toString(source.getLevel().getGameTime()));
		result.addProperty("block", BuiltInRegistries.BLOCK.getKey(source.getLevel().getBlockState(pos).getBlock()).toString());
		result.addProperty("supported", blockEntity instanceof IAe2OutputHostBase);
		if (blockEntity instanceof IAe2OutputHostBase host) {
			result.add("ae2", Ae2IntegrationLoader.machineDiagnostics(host));
		} else {
			result.addProperty("error", "unsupported_machine");
		}
		String marker = requestId.isEmpty() ? "PBG_MACHINE" : "PBG_MACHINE_" + requestId;
		source.sendSuccess(() -> Component.literal(marker + " " + result), false);
		return 1;
	}
}
