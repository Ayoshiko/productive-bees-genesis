package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 恢复只接受当前已加载宿主和请求身份。管理员必须先核验两端，不授予自动补发。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class OutputRecoveryCommands {
    private OutputRecoveryCommands() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pbgoutput").requires(s -> s.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.literal("status").executes(c -> execute(c, "status")))
                        .then(Commands.literal("retry-data").executes(c -> execute(c, "retry")))
                        .then(Commands.literal("resolve-input").then(Commands.argument("request", UuidArgument.uuid())
                                .then(Commands.argument("transferred", LongArgumentType.longArg(0))
                                        .executes(c -> execute(c, "resolve-input")))))
                        .then(Commands.literal("resolve").then(Commands.argument("request", UuidArgument.uuid())
                                .then(Commands.argument("accepted", LongArgumentType.longArg(0))
                                        .executes(c -> execute(c, "resolve")))))
                        .then(Commands.literal("confirm-production-settled")
                                .then(Commands.argument("request", UuidArgument.uuid())
                                        .executes(c -> execute(c, "settled"))))));
    }

    private static int execute(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, String action) {
        try {
            var source = context.getSource();
            var pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
            var tile = source.getLevel().getBlockEntity(pos);
            var custody = OutputCustody.of(tile);
            if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                    && (player.isSpectator() || player.distanceToSqr(pos.getCenter()) > 64
                    || !mekanism.api.security.IBlockSecurityUtils.INSTANCE.canAccess(player, source.getLevel(), pos, tile))) {
                throw new IllegalStateException("Machine access denied");
            }
            if ("resolve-input".equals(action)) {
                if (!(tile instanceof IAe2InputHost host)) throw new IllegalStateException("Not an input host");
                var pending = host.productivebeesgenesis$getAe2StateHolder().getPendingItemBuffer();
                pending.transfer().resolve(UuidArgument.getUuid(context, "request"),
                        LongArgumentType.getLong(context, "transferred"), pending, source.getLevel().getGameTime());
                tile.setChanged();
            } else if ("resolve".equals(action)) custody.resolve(UuidArgument.getUuid(context, "request"), LongArgumentType.getLong(context, "accepted"));
            else if ("settled".equals(action)) {
                if (!(tile instanceof TileEntityMekApiary apiary)) throw new IllegalStateException("Not an apiary");
                apiary.productionState().acknowledgeSettled(UuidArgument.getUuid(context, "request"));
            } else if ("retry".equals(action)) {
                custody.retryDecode(source.registryAccess());
                if (tile instanceof TileEntityMekApiary apiary) apiary.retryOutputData();
                if (tile instanceof IAe2InputHost host) {
                    host.productivebeesgenesis$getAe2StateHolder().retryInputFilterData();
                    tile.setChanged();
                }
            }
            source.sendSuccess(() -> Component.translatable("command.productivebeesgenesis.output.status",
                    String.valueOf(custody.sequence()), custody.amount(), custody.destination(), custody.failure()), false);
            if (tile instanceof IAe2InputHost host) {
                var pending = host.productivebeesgenesis$getAe2StateHolder().getPendingItemBuffer();
                if (host.productivebeesgenesis$getAe2StateHolder().hasDeferredInputFilter()) {
                    source.sendSuccess(() -> Component.translatable("command.productivebeesgenesis.output.input_filter"), false);
                }
                if (pending.isBlocked()) {
                    var transfer = pending.transfer();
                    source.sendSuccess(() -> Component.translatable("command.productivebeesgenesis.output.input",
                            String.valueOf(transfer.request()), transfer.destination(), transfer.knownAmount(),
                            transfer.offeredAmount(), String.valueOf(transfer.fingerprint())), false);
                }
            }
            if (tile instanceof TileEntityMekApiary apiary && apiary.productionState().suspended())
                source.sendSuccess(() -> Component.translatable("command.productivebeesgenesis.output.production",
                        String.valueOf(apiary.productionState().request()), apiary.productionState().phase(), apiary.productionState().failure()), false);
            if (!"status".equals(action)) ProductiveBeesGenesis.LOGGER.warn("Output recovery actor={} pos={} action={}",
                    source.getTextName(), pos, action);
            return 1;
        } catch (Exception error) {
            context.getSource().sendFailure(Component.translatable("command.productivebeesgenesis.output.failed", error.getMessage()));
            return 0;
        }
    }
}
