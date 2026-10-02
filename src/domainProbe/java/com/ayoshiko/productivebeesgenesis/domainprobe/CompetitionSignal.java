package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation;
import java.util.function.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** 仅开发源集的阶段／回执屏障，绝不携带资产键或修改权限；资产请求仍走正式 TCP 协议。 */
@EventBusSubscriber(modid = "productivebeesgenesis", bus = EventBusSubscriber.Bus.MOD)
public record CompetitionSignal(int stage, int moved, int status) implements CustomPacketPayload {
    static Consumer<CompetitionSignal> client;
    static BiConsumer<ServerPlayer, CompetitionSignal> server;
    static final Type<CompetitionSignal> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:competition_probe"));
    static final StreamCodec<FriendlyByteBuf, CompetitionSignal> CODEC = new StreamCodec<>() {
        public CompetitionSignal decode(FriendlyByteBuf buffer) { return new CompetitionSignal(buffer.readInt(), buffer.readInt(), buffer.readInt()); }
        public void encode(FriendlyByteBuf buffer, CompetitionSignal value) { buffer.writeInt(value.stage); buffer.writeInt(value.moved); buffer.writeInt(value.status); }
    };
    public CompetitionSignal {
        if (stage < 0 || stage > 130 || moved < 0 || moved > 1000 || status < -3 || status > 30)
            throw new IllegalArgumentException("Invalid probe signal");
    }
    @Override public Type<CompetitionSignal> type() { return TYPE; }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").executesOn(HandlerThread.MAIN).playBidirectional(TYPE, CODEC, (signal, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                if (Boolean.getBoolean("pbg.concurrent.server") && server != null) server.accept(player, signal);
            } else if (Boolean.getBoolean("pbg.concurrent.client") && client != null) client.accept(signal);
        });
    }
    enum Case {
        SINGLE("minecraft:diamond", "", Operation.TAKE_PRODUCT, 64, 1, 1),
        PARTIAL("minecraft:iron_ingot", "__plain__", Operation.TAKE_PRODUCT, 64, 2, 2),
        FULL("minecraft:emerald", "", Operation.TAKE_PRODUCT, 64, 7, 0),
        FLUID("minecraft:water", "", Operation.TAKE_PRODUCT, 1000, 1000, 1000),
        VARIANT("minecraft:iron_ingot", "competition-red", Operation.TAKE_PRODUCT, 64, 1, 1),
        FOOD("", "", Operation.FEED_OUT, 1, 1, 1),
        BEE("", "", Operation.CAGE_OUT, 1, 1, 1),
        REGRANTED("minecraft:iron_ingot", "competition-blue", Operation.TAKE_PRODUCT, 1, 5, 2),
        RECONNECTED("minecraft:gold_ingot", "", Operation.TAKE_PRODUCT, 1, 5, 1);
        final String id, variant;
        final Operation operation;
        final int amount, available, moved;
        Case(String id, String variant, Operation operation, int amount, int available, int moved) {
            this.id = id; this.variant = variant; this.operation = operation; this.amount = amount; this.available = available; this.moved = moved;
        }
    }
}
