package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 开启请求只声明原设备；物品、数量和实际接收空间始终由服务器读取。 */
public record WirelessRestockRequest(long sequence, int slot, UUID device, UUID token) implements CustomPacketPayload {
    public static final Type<WirelessRestockRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "wireless_restock"));
    public static final StreamCodec<FriendlyByteBuf, WirelessRestockRequest> CODEC = new StreamCodec<>() {
        public WirelessRestockRequest decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 9 && buffer.readableBytes() != 45) throw new IllegalArgumentException("Invalid restock packet size");
            long sequence = buffer.readLong();
            var result = buffer.readBoolean() ? new WirelessRestockRequest(sequence, buffer.readInt(), buffer.readUUID(), buffer.readUUID())
                    : new WirelessRestockRequest(sequence, -1, null, null);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing restock data");
            return result;
        }
        public void encode(FriendlyByteBuf buffer, WirelessRestockRequest value) {
            buffer.writeLong(value.sequence()); buffer.writeBoolean(value.enabled());
            if (value.enabled()) { buffer.writeInt(value.slot()); buffer.writeUUID(value.device()); buffer.writeUUID(value.token()); }
        }
    };
    public WirelessRestockRequest {
        if (sequence < 1 || (slot == -1 ? device != null || token != null
                : !WirelessPickRequest.validSlot(slot) || device == null || token == null)) throw new IllegalArgumentException("Invalid restock request");
    }
    public boolean enabled() { return slot != -1; }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
