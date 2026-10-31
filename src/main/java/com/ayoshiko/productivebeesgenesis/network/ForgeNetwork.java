package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.*;

/** Forge transport. Packet directions are enforced before handlers are dispatched. */
public final class ForgeNetwork {
    private static final String VERSION = "forge-1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ProductiveBeesGenesis.MOD_ID, "main"),
        () -> VERSION, VERSION::equals, VERSION::equals);
    private static int nextId;
    private ForgeNetwork() {}
    static <T> void register(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ForgePayloadContext> handler,
            NetworkDirection direction) {
        CHANNEL.messageBuilder(type, nextId++, direction).encoder(encoder).decoder(decoder)
            .consumerMainThread((packet, context) -> {
                var ctx = context.get();
                var player = ctx.getDirection().getReceptionSide().isServer()
                    ? ctx.getSender() : ForgeClientPacketContext.player();
                if (player != null) handler.accept(packet, new ForgePayloadContext(player));
                ctx.setPacketHandled(true);
            }).add();
    }
    public static void sendToServer(Object packet) {
        if (packet instanceof mekanism.common.network.IMekanismPacket) mekanism.common.Mekanism.packetHandler().sendToServer(packet);
        else CHANNEL.sendToServer(packet);
    }
    public static void sendToPlayer(ServerPlayer player, Object packet) {
        if (packet instanceof mekanism.common.network.IMekanismPacket) mekanism.common.Mekanism.packetHandler().sendTo(packet, player);
        else CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
    public static void sendToAllPlayers(Object packet) {
        if (packet instanceof mekanism.common.network.IMekanismPacket) mekanism.common.Mekanism.packetHandler().sendToAll(packet);
        else CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }
    public static void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos pos, Object packet) {
        CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunk(pos.x, pos.z)), packet);
    }
}
