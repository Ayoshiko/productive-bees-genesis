package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端到服务端：切换单个机械蜂箱的相邻离心机快速直连通道。 */
public record ToggleApiaryDirectEjectPayload(BlockPos pos) implements CustomPacketPayload {

	public static final Type<ToggleApiaryDirectEjectPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "toggle_apiary_direct_eject"));

	public static final StreamCodec<ByteBuf, ToggleApiaryDirectEjectPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ToggleApiaryDirectEjectPayload::pos,
			ToggleApiaryDirectEjectPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
