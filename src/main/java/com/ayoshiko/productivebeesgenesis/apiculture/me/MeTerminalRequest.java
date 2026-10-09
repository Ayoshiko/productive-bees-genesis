package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 只传已展示行和版本；不接受客户端资产、网格、CPU 或任务身份。 */
public record MeTerminalRequest(int containerId, UUID session, long sequence, Action action, long revision, int row, int page, long amount, String query, MeStorageFilter filter, boolean pinCompleted) implements CustomPacketPayload {
	public enum Action { BROWSE, PLAN, POLL, PAGE, CPU_NEXT, CONFIRM, TASKS, CANCEL, CLOSE, STORAGE, TAKE, TAKE_INVENTORY, DEPOSIT, FILL_CONTAINER, EMPTY_CONTAINER, RECOVER_FLUID, CHARGE_ITEM, DISCHARGE_ITEM, RECOVER_ENERGY, FILL_CHEMICAL, EMPTY_CHEMICAL, RECOVER_CHEMICAL, PATTERN_READ, PATTERN_MULTIPLY, PATTERN_DIVIDE, PATTERN_APPLY, PATTERN_ENCODE_CRAFTING, PATTERN_REPLACE, PATTERN_ENCODE_PROCESSING, PATTERN_ADD_INPUT, PATTERN_ADD_OUTPUT, PATTERN_SET_AMOUNT, PATTERN_REMOVE, PATTERN_CLEAR, PATTERN_BATCH_REPLACE, PATTERN_BATCH_TOGGLE, PATTERN_BATCH_SELECT, PATTERN_BATCH_DETAILS, PATTERN_BATCH_LIST, PATTERN_BUFFER, PATTERN_BUFFER_STORE, PATTERN_BUFFER_TAKE, PATTERN_BUFFER_TAKE_INVENTORY, PATTERN_BUFFER_RETURN, PROVIDERS, PROVIDER_OPEN, PROVIDER_REFRESH, PROVIDER_STORE, PROVIDER_TAKE, PROVIDER_TAKE_INVENTORY, PATTERN_BUFFER_BATCH_REPLACE, PROVIDER_UPLOAD_PREVIEW, PROVIDER_RETURN_PREVIEW, PROVIDER_BATCH_TOGGLE, PROVIDER_BATCH_SELECT, PROVIDER_BATCH_APPLY, PROVIDER_BATCH_CANCEL, PROVIDER_SETTINGS, PROVIDER_PRIORITY, PROVIDER_BLOCKING, PROVIDER_LOCK_MODE, PROVIDER_HIDE, PICK_PLAN, PROVIDER_UNLOCK, PROVIDER_PATTERN_OPEN, PROVIDER_PATTERN_READ, PROVIDER_PATTERN_REPLACE, PROVIDER_PATTERN_APPLY }
	public MeTerminalRequest(int containerId, UUID session, long sequence, Action action, long revision, int row, int page, long amount, String query, MeStorageFilter filter) {
		this(containerId, session, sequence, action, revision, row, page, amount, query, filter, false);
	}
	public MeTerminalRequest(int containerId, UUID session, long sequence, Action action, long revision, int row, int page, long amount, String query) {
		this(containerId, session, sequence, action, revision, row, page, amount, query, MeStorageFilter.DEFAULT);
	}
	public static final Type<MeTerminalRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "me_terminal_request"));
	public static final StreamCodec<FriendlyByteBuf, MeTerminalRequest> CODEC = new StreamCodec<>() {
		public MeTerminalRequest decode(FriendlyByteBuf b) {
			if (b.readableBytes() > 512) throw new IllegalArgumentException("ME request too large");
			var result = new MeTerminalRequest(b.readInt(), b.readUUID(), b.readLong(), b.readEnum(Action.class), b.readLong(), b.readInt(), b.readInt(), b.readLong(), b.readUtf(64), new MeStorageFilter(b.readEnum(MeStorageFilter.Sort.class), b.readBoolean(), b.readEnum(MeStorageFilter.Content.class), b.readEnum(MeStorageFilter.Type.class)), b.readBoolean());
			if (b.isReadable()) throw new IllegalArgumentException("Trailing ME request data"); return result;
		}
		public void encode(FriendlyByteBuf b, MeTerminalRequest r) {
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeEnum(r.action); b.writeLong(r.revision); b.writeInt(r.row); b.writeInt(r.page); b.writeLong(r.amount); b.writeUtf(r.query, 64); b.writeEnum(r.filter.sort()); b.writeBoolean(r.filter.descending()); b.writeEnum(r.filter.content()); b.writeEnum(r.filter.type()); b.writeBoolean(r.pinCompleted);
		}
	};
	public MeTerminalRequest {
		if (containerId < 0 || session == null || sequence < 1 || action == null || revision < 0 || row < -1 || row >= MeTerminalView.STORAGE_ROWS || page < 0 || page > Integer.MAX_VALUE / MeTerminalView.STORAGE_ROWS || amount < 0 || query == null || query.length() > 64 || filter == null)
			throw new IllegalArgumentException("Invalid ME request");
	}
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
