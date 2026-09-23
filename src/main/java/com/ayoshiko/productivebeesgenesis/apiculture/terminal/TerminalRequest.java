package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 固定长度命令；没有客户端提供的资产键、蜜蜂 NBT 或成员身份。 */
public record TerminalRequest(int containerId, UUID session, long sequence, Operation operation,
		long generation, int row, int targetSlot, int inventorySlot, int amount) implements CustomPacketPayload {
	public enum Operation { MEMBERS, PRODUCTS, NEXT, CANCEL, FEED_IN, FEED_OUT, CAGE_IN, CAGE_OUT, TAKE_PRODUCT }
	public static final int BYTES = 53;
	public static final Type<TerminalRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "network_terminal_request"));
	public static final StreamCodec<FriendlyByteBuf, TerminalRequest> STREAM_CODEC = new StreamCodec<>() {
		@Override public TerminalRequest decode(FriendlyByteBuf b) {
			if (b.readableBytes() != BYTES) throw new IllegalArgumentException("Invalid terminal request length");
			return new TerminalRequest(b.readInt(), b.readUUID(), b.readLong(), b.readEnum(Operation.class),
					b.readLong(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
		}
		@Override public void encode(FriendlyByteBuf b, TerminalRequest r) {
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeEnum(r.operation);
			b.writeLong(r.generation); b.writeInt(r.row); b.writeInt(r.targetSlot); b.writeInt(r.inventorySlot); b.writeInt(r.amount);
		}
	};
	public TerminalRequest {
		Objects.requireNonNull(session); Objects.requireNonNull(operation);
		if (containerId < 0 || sequence <= 0 || generation < 0 || row < -1 || row >= NetworkSelectionSession.PAGE_SIZE
				|| targetSlot < -1 || targetSlot >= 3 || inventorySlot < -1 || inventorySlot >= 36 || amount < 0 || amount > 1000) {
			throw new IllegalArgumentException("Invalid terminal request");
		}
	}
	@Override public Type<TerminalRequest> type() { return TYPE; }
}
