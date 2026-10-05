package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 有界只读检索；与资产命令共用菜单序号，客户端不提供成员或资产身份。 */
public record TerminalSearchRequest(int containerId, UUID session, long sequence, NetworkSelectionSession.Kind kind,
		String query, Navigation navigation, Sort sort, TerminalNameMatches names) implements CustomPacketPayload {
	public TerminalSearchRequest(int containerId, UUID session, long sequence, NetworkSelectionSession.Kind kind, String query, Navigation navigation, Sort sort) {
		this(containerId, session, sequence, kind, query, navigation, sort, TerminalNameMatches.EMPTY);
	}
	public enum Navigation { FIRST, NEXT, PREVIOUS, REFRESH }
	public enum Sort { POSITION, CAPACITY, QUANTITY_DESC, QUANTITY_ASC }
	public boolean quantity() { return sort == Sort.QUANTITY_DESC || sort == Sort.QUANTITY_ASC; }
	public TerminalSearchRequest(int containerId, UUID session, long sequence, NetworkSelectionSession.Kind kind, String query, Navigation navigation) {
		this(containerId, session, sequence, kind, query, navigation, Sort.POSITION);
	}
	public TerminalSearchRequest(int containerId, UUID session, long sequence, NetworkSelectionSession.Kind kind, String query) {
		this(containerId, session, sequence, kind, query, Navigation.FIRST);
	}
	public static final int MAX_BYTES = 48 * 1024;
	public static final Type<TerminalSearchRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "network_terminal_search"));
	public static final StreamCodec<FriendlyByteBuf, TerminalSearchRequest> STREAM_CODEC = new StreamCodec<>() {
		@Override public TerminalSearchRequest decode(FriendlyByteBuf b) {
			if (b.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal query");
			var request = new TerminalSearchRequest(b.readInt(), b.readUUID(), b.readLong(),
					b.readEnum(NetworkSelectionSession.Kind.class), b.readUtf(64), b.readEnum(Navigation.class), b.readEnum(Sort.class), TerminalNameMatches.read(b));
			if (b.isReadable()) throw new IllegalArgumentException("Trailing terminal query bytes");
			return request;
		}
		@Override public void encode(FriendlyByteBuf b, TerminalSearchRequest request) {
			b.writeInt(request.containerId); b.writeUUID(request.session); b.writeLong(request.sequence);
			b.writeEnum(request.kind); b.writeUtf(request.query, 64); b.writeEnum(request.navigation); b.writeEnum(request.sort); request.names.write(b);
		}
	};
	public TerminalSearchRequest {
		Objects.requireNonNull(session); Objects.requireNonNull(kind); Objects.requireNonNull(query); Objects.requireNonNull(navigation); Objects.requireNonNull(sort); Objects.requireNonNull(names);
		if (!new TerminalFilter(query).nameTerms().containsAll(names.terms().keySet())) throw new IllegalArgumentException("Unrelated localized name terms");
		if ((sort == Sort.QUANTITY_DESC || sort == Sort.QUANTITY_ASC) && kind != NetworkSelectionSession.Kind.PRODUCTS) throw new IllegalArgumentException("Only products have quantity order");
		if (sort == Sort.CAPACITY && kind == NetworkSelectionSession.Kind.PRODUCTS) throw new IllegalArgumentException("Products have no apiary capacity");
		if (containerId < 0 || sequence <= 0 || query.length() > 64 || query.chars().anyMatch(Character::isISOControl))
			throw new IllegalArgumentException("Invalid terminal query");
	}
	@Override public Type<TerminalSearchRequest> type() { return TYPE; }
}
