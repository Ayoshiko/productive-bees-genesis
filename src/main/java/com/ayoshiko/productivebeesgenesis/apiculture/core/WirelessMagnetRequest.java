package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 只声明磁力偏好与原设备；范围、实体选择和速度均由服务器决定。 */
public record WirelessMagnetRequest(long sequence, int slot, UUID device, UUID token) implements CustomPacketPayload {
	public static final Type<WirelessMagnetRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "wireless_magnet"));
	public static final StreamCodec<FriendlyByteBuf, WirelessMagnetRequest> CODEC = new StreamCodec<>() {
		public WirelessMagnetRequest decode(FriendlyByteBuf buffer) {
			if (buffer.readableBytes() != 9 && buffer.readableBytes() != 45) throw new IllegalArgumentException("Invalid magnet intent size");
			long sequence = buffer.readLong();
			var result = buffer.readBoolean() ? new WirelessMagnetRequest(sequence, buffer.readInt(), buffer.readUUID(), buffer.readUUID())
					: new WirelessMagnetRequest(sequence, -1, null, null);
			if (buffer.isReadable()) throw new IllegalArgumentException("Trailing magnet intent data");
			return result;
		}
		public void encode(FriendlyByteBuf buffer, WirelessMagnetRequest value) {
			buffer.writeLong(value.sequence()); buffer.writeBoolean(value.enabled());
			if (value.enabled()) { buffer.writeInt(value.slot()); buffer.writeUUID(value.device()); buffer.writeUUID(value.token()); }
		}
	};
	public WirelessMagnetRequest {
		if (sequence < 1 || (slot == -1 ? device != null || token != null
				: !WirelessPickRequest.validSlot(slot) || device == null || token == null)) throw new IllegalArgumentException("Invalid magnet intent");
	}
	public boolean enabled() { return slot != -1; }
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
