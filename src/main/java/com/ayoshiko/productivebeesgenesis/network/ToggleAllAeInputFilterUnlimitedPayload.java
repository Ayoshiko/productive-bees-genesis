package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：一键切换全部 AE 输入过滤直连条目的无限拉取状态。 */
public record ToggleAllAeInputFilterUnlimitedPayload(BlockPos pos)
		implements CustomPacketPayload {

	public static final Type<ToggleAllAeInputFilterUnlimitedPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "toggle_all_ae_input_filter_unlimited"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, ToggleAllAeInputFilterUnlimitedPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ToggleAllAeInputFilterUnlimitedPayload::pos,
					ToggleAllAeInputFilterUnlimitedPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
