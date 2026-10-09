package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端只声明取样位置及真实设备引用，物品和数量由服务器决定。 */
public record WirelessPickRequest(long sequence, int slot, int selected, UUID device, UUID token, BlockPos target) implements CustomPacketPayload {
    public static final double MAX_REACH = 8;
    public static final Type<WirelessPickRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "wireless_pick"));
    public static final StreamCodec<FriendlyByteBuf, WirelessPickRequest> CODEC = new StreamCodec<>() {
        public WirelessPickRequest decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() > 80) throw new IllegalArgumentException("Wireless pick request too large");
            var result = new WirelessPickRequest(buffer.readLong(), buffer.readInt(), buffer.readInt(), buffer.readUUID(), buffer.readUUID(), buffer.readBlockPos());
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing wireless pick data");
            return result;
        }
        public void encode(FriendlyByteBuf buffer, WirelessPickRequest value) {
            buffer.writeLong(value.sequence()); buffer.writeInt(value.slot()); buffer.writeInt(value.selected());
            buffer.writeUUID(value.device()); buffer.writeUUID(value.token()); buffer.writeBlockPos(value.target());
        }
    };
    public WirelessPickRequest {
        if (sequence < 1 || !validSlot(slot) || selected < 0 || selected > 8 || device == null || token == null || target == null)
            throw new IllegalArgumentException("Invalid wireless pick request");
        target = target.immutable();
    }
    public static boolean validSlot(int slot) { return slot >= 0 && slot < 36 || slot == 40; }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
