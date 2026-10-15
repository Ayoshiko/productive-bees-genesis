package com.ayoshiko.productivebeesgenesis.apiculture.port;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** 不上传资产数据；会话、单调序号和配置代际共同约束固定 52 字节请求。 */
public record PortMenuRequest(int container, UUID session, long sequence, long configuration, int action, int slot, int source, int amount) implements CustomPacketPayload {
    public static final Type<PortMenuRequest> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:network_port_request"));
    public static final StreamCodec<FriendlyByteBuf, PortMenuRequest> CODEC = new StreamCodec<>() {
        public PortMenuRequest decode(FriendlyByteBuf b) {
            if (b.readableBytes() != 52) throw new IllegalArgumentException("Invalid port request length");
            return new PortMenuRequest(b.readInt(), b.readUUID(), b.readLong(), b.readLong(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
        }
        public void encode(FriendlyByteBuf b, PortMenuRequest r) {
            b.writeInt(r.container); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeLong(r.configuration);
            b.writeInt(r.action); b.writeInt(r.slot); b.writeInt(r.source); b.writeInt(r.amount);
        }
    };
    public PortMenuRequest {
        if (container < 0 || session == null || sequence < 1 || configuration < 0 || action < 0 || action > 5
                || slot < 0 || slot > 9 || source < -1 || source >= 36 || amount < 1 || amount > 64)
            throw new IllegalArgumentException("Invalid port request");
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").executesOn(HandlerThread.MAIN).playToServer(TYPE, CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof NetworkPortMenu menu) menu.request(player, request);
        });
    }
    public Type<PortMenuRequest> type() { return TYPE; }
}
