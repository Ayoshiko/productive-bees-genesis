package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 只传已展示行和版本；不接受客户端资产、网格、CPU 或任务身份。 */
public record MeTerminalRequest(int containerId, UUID session, long sequence, Action action, long revision, int row, int page, long amount, String query) implements CustomPacketPayload {
	public enum Action { BROWSE, PLAN, POLL, PAGE, CPU_NEXT, CONFIRM, TASKS, CANCEL, CLOSE }
	public static final Type<MeTerminalRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "me_terminal_request"));
	public static final StreamCodec<FriendlyByteBuf, MeTerminalRequest> CODEC = new StreamCodec<>() {
		public MeTerminalRequest decode(FriendlyByteBuf b) {
			if (b.readableBytes() > 512) throw new IllegalArgumentException("ME request too large");
			return new MeTerminalRequest(b.readInt(), b.readUUID(), b.readLong(), b.readEnum(Action.class), b.readLong(), b.readInt(), b.readInt(), b.readLong(), b.readUtf(64));
		}
		public void encode(FriendlyByteBuf b, MeTerminalRequest r) {
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeEnum(r.action); b.writeLong(r.revision); b.writeInt(r.row); b.writeInt(r.page); b.writeLong(r.amount); b.writeUtf(r.query, 64);
		}
	};
	public MeTerminalRequest {
		if (containerId < 0 || session == null || sequence < 1 || action == null || revision < 0 || row < -1 || row >= 8 || page < 0 || page > Integer.MAX_VALUE / 8 || amount < 0 || query == null || query.length() > 64)
			throw new IllegalArgumentException("Invalid ME request");
	}
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
