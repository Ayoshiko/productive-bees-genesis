package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 只声明磁力偏好与原设备；范围、实体选择和速度均由服务器决定。 */
public record WirelessMagnetRequest(long sequence, int slot, UUID device, UUID token, WirelessItemFilter filter) implements CustomPacketPayload {
	public static final Type<WirelessMagnetRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "wireless_magnet"));
	public static final StreamCodec<FriendlyByteBuf, WirelessMagnetRequest> CODEC = new StreamCodec<>() {
		public WirelessMagnetRequest decode(FriendlyByteBuf buffer) {
			if (buffer.readableBytes() < 9 || buffer.readableBytes() > 45 + WirelessItemFilter.MAX_WIRE_BYTES) throw new IllegalArgumentException("Invalid magnet intent size");
			long sequence = buffer.readLong();
			var result = buffer.readBoolean() ? new WirelessMagnetRequest(sequence, buffer.readInt(), buffer.readUUID(), buffer.readUUID(), WirelessItemFilter.read(buffer))
					: new WirelessMagnetRequest(sequence, -1, null, null, WirelessItemFilter.ALL);
			if (buffer.isReadable()) throw new IllegalArgumentException("Trailing magnet intent data");
			return result;
		}
		public void encode(FriendlyByteBuf buffer, WirelessMagnetRequest value) {
			buffer.writeLong(value.sequence()); buffer.writeBoolean(value.enabled());
			if (value.enabled()) { buffer.writeInt(value.slot()); buffer.writeUUID(value.device()); buffer.writeUUID(value.token()); value.filter().write(buffer); }
		}
	};
	public WirelessMagnetRequest {
		if (sequence < 1 || filter == null || (slot == -1 ? device != null || token != null || !filter.equals(WirelessItemFilter.ALL)
				: !WirelessPickRequest.validSlot(slot) || device == null || token == null)) throw new IllegalArgumentException("Invalid magnet intent");
	}
	public boolean enabled() { return slot != -1; }
	boolean sameIntent(WirelessMagnetRequest other) {
		return other != null && enabled() && other.enabled() && slot == other.slot && device.equals(other.device)
				&& token.equals(other.token) && filter.equals(other.filter);
	}
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
