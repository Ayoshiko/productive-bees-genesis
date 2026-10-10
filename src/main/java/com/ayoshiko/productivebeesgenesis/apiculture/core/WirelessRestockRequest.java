package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 只声明原设备和有界补货偏好；资产、候选槽及实际缺额由服务器读取。 */
public record WirelessRestockRequest(long sequence, int slot, UUID device, UUID token, int target, boolean offhand) implements CustomPacketPayload {
    public static final Type<WirelessRestockRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "wireless_restock"));
    public static final StreamCodec<FriendlyByteBuf, WirelessRestockRequest> CODEC = new StreamCodec<>() {
        public WirelessRestockRequest decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 9 && buffer.readableBytes() != 47) throw new IllegalArgumentException("Invalid restock packet size");
            long sequence = buffer.readLong();
            var result = buffer.readBoolean() ? new WirelessRestockRequest(sequence, buffer.readInt(), buffer.readUUID(), buffer.readUUID(), buffer.readUnsignedByte(), readOffhand(buffer))
                    : new WirelessRestockRequest(sequence, -1, null, null, 64, false);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing restock data");
            return result;
        }
        public void encode(FriendlyByteBuf buffer, WirelessRestockRequest value) {
            buffer.writeLong(value.sequence()); buffer.writeBoolean(value.enabled());
            if (value.enabled()) { buffer.writeInt(value.slot()); buffer.writeUUID(value.device()); buffer.writeUUID(value.token()); buffer.writeByte(value.target()); buffer.writeBoolean(value.offhand()); }
        }
    };
    public WirelessRestockRequest {
        if (sequence < 1 || !WirelessRestockSlots.validTarget(target) || (slot == -1 ? device != null || token != null || target != 64 || offhand
                : !WirelessPickRequest.validSlot(slot) || device == null || token == null)) throw new IllegalArgumentException("Invalid restock request");
    }
    public boolean enabled() { return slot != -1; }
    private static boolean readOffhand(FriendlyByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid restock offhand flag");
        return value == 1;
    }
    boolean sameIntent(WirelessRestockRequest other) {
        return other != null && enabled() && other.enabled() && slot == other.slot && device.equals(other.device)
                && token.equals(other.token) && target == other.target && offhand == other.offhand;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
