package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：批量设置直连条目的拉取数量或网络库存保留量。 */
public record SetAllAeInputFilterAmountPayload(BlockPos pos, long amount, boolean reserve)
		implements CustomPacketPayload {
	public SetAllAeInputFilterAmountPayload(BlockPos pos, long amount) {
		this(pos, amount, false);
	}

	public static final Type<SetAllAeInputFilterAmountPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "set_all_ae_input_filter_amount"));

	public static final StreamCodec<ByteBuf, SetAllAeInputFilterAmountPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, SetAllAeInputFilterAmountPayload::pos,
					ByteBufCodecs.VAR_LONG, SetAllAeInputFilterAmountPayload::amount,
					ByteBufCodecs.BOOL, SetAllAeInputFilterAmountPayload::reserve,
					SetAllAeInputFilterAmountPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
