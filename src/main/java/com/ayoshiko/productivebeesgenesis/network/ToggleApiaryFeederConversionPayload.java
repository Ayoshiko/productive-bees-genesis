package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端到服务端：切换单个机械蜂箱的喂食槽物品转化开关。 */
public record ToggleApiaryFeederConversionPayload(BlockPos pos) implements CustomPacketPayload {

	public static final Type<ToggleApiaryFeederConversionPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "toggle_apiary_feeder_conversion"));

	public static final StreamCodec<ByteBuf, ToggleApiaryFeederConversionPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ToggleApiaryFeederConversionPayload::pos,
					ToggleApiaryFeederConversionPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
