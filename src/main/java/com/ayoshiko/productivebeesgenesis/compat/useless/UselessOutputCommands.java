package com.ayoshiko.productivebeesgenesis.compat.useless;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.sorrowmist.useless.content.blockentities.AdvancedAlloyFurnaceBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

/** 管理员核验实际接收量后确认；-1 是普通加工，其他编号是当前 AE 任务。 */
public final class UselessOutputCommands {
    private static final DynamicCommandExceptionType FAILURE = new DynamicCommandExceptionType(value -> Component.literal(String.valueOf(value)));
    private UselessOutputCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pbg-useless-output").requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("task", IntegerArgumentType.integer(-1)).executes(ctx -> run(ctx, false))
                .then(Commands.literal("resolve").then(Commands.argument("custody", UuidArgument.uuid())
                .then(Commands.argument("sequence", LongArgumentType.longArg(0))
                .then(Commands.argument("received", LongArgumentType.longArg(0)).executes(ctx -> run(ctx, true)))))))));
    }
    private static int run(CommandContext<CommandSourceStack> ctx, boolean resolve) throws CommandSyntaxException {
        var pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        if (!(ctx.getSource().getLevel().getBlockEntity(pos) instanceof AdvancedAlloyFurnaceBlockEntity host)) throw FAILURE.create("No alloy furnace at " + pos);
        int task = IntegerArgumentType.getInteger(ctx, "task");
        try {
            if (resolve) {
                long sequence = LongArgumentType.getLong(ctx, "sequence"), received = LongArgumentType.getLong(ctx, "received");
                host.getClass().getMethod("resolvePaidOutput", int.class, java.util.UUID.class, long.class, long.class)
                        .invoke(host, task, UuidArgument.getUuid(ctx, "custody"), sequence, received);
                com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.LOGGER.info("Paid output confirmation: actor={} pos={} task={} sequence={} received={}", ctx.getSource().getTextName(), pos, task, sequence, received);
            }
            var status = host.getClass().getMethod("paidOutputStatus", int.class).invoke(host, task);
            ctx.getSource().sendSuccess(() -> Component.literal(String.valueOf(status)), false);
            return 1;
        } catch (ReflectiveOperationException failure) {
            throw FAILURE.create(failure.getCause() == null ? failure.toString() : failure.getCause().toString());
        }
    }
}
