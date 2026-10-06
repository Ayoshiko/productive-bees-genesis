package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 配方是查询身份，不是客户端提供的物品；实际材料只从当前服务端账户规划。 */
public record TerminalRecipeRequest(int containerId, UUID session, long sequence, ResourceLocation recipe, boolean maximum) implements CustomPacketPayload {
	public static final Type<TerminalRecipeRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "network_recipe_fill"));
	public static final StreamCodec<FriendlyByteBuf, TerminalRecipeRequest> STREAM_CODEC = new StreamCodec<>() {
		@Override public TerminalRecipeRequest decode(FriendlyByteBuf b) {
			if (b.readableBytes() > 512) throw new IllegalArgumentException("Oversized recipe fill request");
			var result = new TerminalRecipeRequest(b.readInt(), b.readUUID(), b.readLong(), ResourceLocation.parse(b.readUtf(256)), b.readBoolean());
			if (b.isReadable()) throw new IllegalArgumentException("Trailing recipe fill data"); return result;
		}
		@Override public void encode(FriendlyByteBuf b, TerminalRecipeRequest r) { b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeUtf(r.recipe.toString(), 256); b.writeBoolean(r.maximum); }
	};
	public TerminalRecipeRequest {
		Objects.requireNonNull(session); Objects.requireNonNull(recipe);
		if (containerId < 0 || sequence < 1 || recipe.toString().length() > 256) throw new IllegalArgumentException("Invalid recipe fill request");
	}
	public TerminalRequest command() { return new TerminalRequest(containerId, session, sequence, TerminalRequest.Operation.CRAFT_FILL, 0, -1, -1, -1, maximum ? 64 : 1); }
	@Override public Type<TerminalRecipeRequest> type() { return TYPE; }
}
