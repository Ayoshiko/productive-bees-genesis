package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 每个已接纳请求只回复一次，包体最多 16 KiB，无分片队列或 NBT。 */
public record TerminalReply(int containerId, UUID session, long sequence, Status status,
		int moved, int interruptedTicks, TerminalView view) implements CustomPacketPayload {
	public enum Status { OK, MOVED, STALE, INVALID, UNAVAILABLE, NO_SPACE, EMPTY_OR_RESERVED, DRAIN_FIRST,
		OCCUPIED, EMPTY, UNSUPPORTED_CAGE, UNSUPPORTED_BEE, UNSUPPORTED_CONTAINER }
	public static final int MAX_BYTES = 16 * 1024;
	public static final Type<TerminalReply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "network_terminal_reply"));
	public static final StreamCodec<FriendlyByteBuf, TerminalReply> STREAM_CODEC = new StreamCodec<>() {
		@Override public TerminalReply decode(FriendlyByteBuf b) {
			if (b.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal reply");
			int container = b.readInt(); UUID session = b.readUUID(); long sequence = b.readLong();
			Status status = b.readEnum(Status.class); int moved = b.readInt(), interrupted = b.readInt();
			TerminalView view = b.readBoolean() ? readView(b) : null;
			if (b.isReadable()) throw new IllegalArgumentException("Trailing terminal reply bytes");
			return new TerminalReply(container, session, sequence, status, moved, interrupted, view);
		}
		@Override public void encode(FriendlyByteBuf b, TerminalReply r) {
			int start = b.writerIndex();
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeEnum(r.status);
			b.writeInt(r.moved); b.writeInt(r.interruptedTicks); b.writeBoolean(r.view != null);
			if (r.view != null) writeView(b, r.view);
			if (b.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal reply");
		}
	};
	public TerminalReply {
		Objects.requireNonNull(session); Objects.requireNonNull(status);
		if (containerId < 0 || sequence <= 0 || moved < 0 || moved > 1000 || interruptedTicks < 0) throw new IllegalArgumentException("Invalid terminal reply");
	}
	@Override public Type<TerminalReply> type() { return TYPE; }
	private static TerminalView readView(FriendlyByteBuf b) {
		var kind = b.readEnum(NetworkSelectionSession.Kind.class); long generation = b.readLong(); boolean next = b.readBoolean();
		int size = boundedSize(b, NetworkSelectionSession.PAGE_SIZE);
		var rows = new ArrayList<TerminalView.Row>(size);
		for (int i = 0; i < size; i++) {
			String label = b.readUtf(TerminalView.TEXT_LIMIT); boolean fluid = b.readBoolean();
			String detail = b.readUtf(TerminalView.TEXT_LIMIT);
			String owned = b.readUtf(TerminalView.AMOUNT_LIMIT), available = b.readUtf(TerminalView.AMOUNT_LIMIT); boolean exact = b.readBoolean();
			int count = boundedSize(b, 3); var bees = new ArrayList<TerminalView.Bee>(count);
			for (int j = 0; j < count; j++) bees.add(new TerminalView.Bee(b.readUnsignedByte(), b.readBoolean(),
					b.readUtf(TerminalView.TEXT_LIMIT), b.readInt(), b.readInt(), b.readBoolean()));
			rows.add(new TerminalView.Row(label, fluid, owned, available, exact, bees, detail));
		}
		return new TerminalView(kind, generation, next, rows);
	}
	private static int boundedSize(FriendlyByteBuf b, int max) {
		int size = b.readUnsignedByte(); if (size > max) throw new IllegalArgumentException("Oversized terminal list"); return size;
	}
	private static void writeView(FriendlyByteBuf b, TerminalView view) {
		b.writeEnum(view.kind()); b.writeLong(view.generation()); b.writeBoolean(view.hasNext()); b.writeByte(view.rows().size());
		for (var row : view.rows()) {
			b.writeUtf(row.label(), TerminalView.TEXT_LIMIT); b.writeBoolean(row.fluid());
			b.writeUtf(row.detail(), TerminalView.TEXT_LIMIT);
			b.writeUtf(row.owned(), TerminalView.AMOUNT_LIMIT); b.writeUtf(row.available(), TerminalView.AMOUNT_LIMIT);
			b.writeBoolean(row.exact()); b.writeByte(row.bees().size());
			for (var bee : row.bees()) {
				b.writeByte(bee.slot()); b.writeBoolean(bee.occupied()); b.writeUtf(bee.type(), TerminalView.TEXT_LIMIT);
				b.writeInt(bee.progress()); b.writeInt(bee.cycleTicks()); b.writeBoolean(bee.pending());
			}
		}
	}
}
