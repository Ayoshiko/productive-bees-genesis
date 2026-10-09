package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 只声明拾取入网偏好与原设备；实体、资产与数量只由服务器拾取事件提供。 */
public record WirelessPickupRequest(long sequence, int slot, UUID device, UUID token) implements CustomPacketPayload {
    public static final Type<WirelessPickupRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "wireless_pickup"));
    public static final StreamCodec<FriendlyByteBuf, WirelessPickupRequest> CODEC = new StreamCodec<>() {
        public WirelessPickupRequest decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 9 && buffer.readableBytes() != 45) throw new IllegalArgumentException("Invalid pickup intent size");
            long sequence = buffer.readLong();
            var result = buffer.readBoolean() ? new WirelessPickupRequest(sequence, buffer.readInt(), buffer.readUUID(), buffer.readUUID())
                    : new WirelessPickupRequest(sequence, -1, null, null);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing pickup intent data");
            return result;
        }
        public void encode(FriendlyByteBuf buffer, WirelessPickupRequest value) {
            buffer.writeLong(value.sequence()); buffer.writeBoolean(value.enabled());
            if (value.enabled()) { buffer.writeInt(value.slot()); buffer.writeUUID(value.device()); buffer.writeUUID(value.token()); }
        }
    };
    public WirelessPickupRequest {
        if (sequence < 1 || (slot == -1 ? device != null || token != null
                : !WirelessPickRequest.validSlot(slot) || device == null || token == null)) throw new IllegalArgumentException("Invalid pickup intent");
    }
    public boolean enabled() { return slot != -1; }
    boolean sameDevice(WirelessPickupRequest other) {
        return other != null && enabled() && other.enabled() && slot == other.slot && device.equals(other.device) && token.equals(other.token);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
