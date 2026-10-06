package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record MeTerminalReply(int containerId, UUID session, long sequence, MeTerminalView view) implements CustomPacketPayload {
	public static final int MAX_BYTES = 32 * 1024;
	public static final Type<MeTerminalReply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "me_terminal_reply"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MeTerminalReply> CODEC = new StreamCodec<>() {
		public MeTerminalReply decode(RegistryFriendlyByteBuf b) {
			if (b.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("ME reply too large");
			int id = b.readInt(); var session = b.readUUID(); long sequence = b.readLong(), revision = b.readLong();
			var mode = b.readEnum(MeTerminalView.Mode.class); var status = b.readEnum(MeTerminalView.Status.class); int page = b.readVarInt(); boolean more = b.readBoolean();
			String title = b.readUtf(256); long bytes = b.readLong(); String cpu = b.readUtf(128); boolean confirm = b.readBoolean(); int count = b.readVarInt();
			if (count < 0 || count > 8) throw new IllegalArgumentException("ME row count");
			var rows = new ArrayList<MeTerminalView.Row>();
			for (int i=0;i<count;i++) rows.add(new MeTerminalView.Row(b.readEnum(MeTerminalView.Kind.class), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readUtf(128), b.readLong(), b.readLong(), b.readBoolean()));
			return new MeTerminalReply(id, session, sequence, new MeTerminalView(revision, mode, status, page, more, title, bytes, cpu, confirm, rows));
		}
		public void encode(RegistryFriendlyByteBuf b, MeTerminalReply r) {
			int start = b.writerIndex(); var v = r.view;
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeLong(v.revision()); b.writeEnum(v.mode()); b.writeEnum(v.status()); b.writeVarInt(v.page()); b.writeBoolean(v.more());
			b.writeUtf(v.title(), 256); b.writeLong(v.bytes()); b.writeUtf(v.cpu(), 128); b.writeBoolean(v.confirm()); b.writeVarInt(v.rows().size());
			for (var row : v.rows()) { b.writeEnum(row.kind()); ItemStack.OPTIONAL_STREAM_CODEC.encode(b, row.icon()); b.writeUtf(row.label(), 128); b.writeLong(row.amount()); b.writeLong(row.extra()); b.writeBoolean(row.enabled()); }
			if (b.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("ME reply too large");
		}
	};
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
