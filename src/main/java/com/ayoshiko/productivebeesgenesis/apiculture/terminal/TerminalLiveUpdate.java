package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 查询订阅与命令序号分别校验；空 READY 帧仅续租已有页面，不含新的可执行选择。 */
public record TerminalLiveUpdate(int containerId, UUID session, long querySequence, long acknowledgedSequence,
		long revision, Status status, boolean hasPrevious, TerminalView view, long sortAgeTicks) implements CustomPacketPayload {
	public TerminalLiveUpdate(int containerId, UUID session, long querySequence, long acknowledgedSequence, long revision, Status status, boolean previous, TerminalView view) {
		this(containerId, session, querySequence, acknowledgedSequence, revision, status, previous, view, -1);
	}
	public enum Status { READY, SEARCHING, UNAVAILABLE }
	public static final int MAX_BYTES = TerminalReply.MAX_BYTES;
	public static final Type<TerminalLiveUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "network_terminal_live"));
	public TerminalLiveUpdate {
		Objects.requireNonNull(session); Objects.requireNonNull(status);
		if (sortAgeTicks < -1 || containerId < 0 || querySequence <= 0 || acknowledgedSequence < querySequence || revision <= 0
				|| status != Status.READY && view != null) throw new IllegalArgumentException("Invalid terminal subscription frame");
	}
	public static final StreamCodec<FriendlyByteBuf, TerminalLiveUpdate> STREAM_CODEC = new StreamCodec<>() {
		@Override public TerminalLiveUpdate decode(FriendlyByteBuf buffer) {
			if (buffer.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal update");
			var result = new TerminalLiveUpdate(buffer.readInt(), buffer.readUUID(), buffer.readLong(), buffer.readLong(), buffer.readLong(),
					buffer.readEnum(Status.class), buffer.readBoolean(), buffer.readBoolean() ? TerminalReply.readView(buffer) : null, buffer.readLong());
			if (buffer.isReadable()) throw new IllegalArgumentException("Trailing terminal update bytes"); return result;
		}
		@Override public void encode(FriendlyByteBuf buffer, TerminalLiveUpdate update) {
			int start = buffer.writerIndex();
			buffer.writeInt(update.containerId); buffer.writeUUID(update.session); buffer.writeLong(update.querySequence);
			buffer.writeLong(update.acknowledgedSequence); buffer.writeLong(update.revision); buffer.writeEnum(update.status);
			buffer.writeBoolean(update.hasPrevious); buffer.writeBoolean(update.view != null);
			if (update.view != null) TerminalReply.writeView(buffer, update.view);
			buffer.writeLong(update.sortAgeTicks);
			if (buffer.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal update");
		}
	};
	public int encodedBytes() {
		var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		try { STREAM_CODEC.encode(buffer, this); return buffer.readableBytes(); } finally { buffer.release(); }
	}
	@Override public Type<TerminalLiveUpdate> type() { return TYPE; }
}
