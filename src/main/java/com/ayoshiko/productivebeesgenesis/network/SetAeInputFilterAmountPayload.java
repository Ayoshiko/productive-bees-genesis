package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server update for one exact AE input entry's pull request amount. */
public record SetAeInputFilterAmountPayload(BlockPos pos, int slotIndex, long amount)
		implements CustomPacketPayload {

	public static final Type<SetAeInputFilterAmountPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "set_ae_input_filter_amount"));

	public static final StreamCodec<ByteBuf, SetAeInputFilterAmountPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetAeInputFilterAmountPayload::pos,
			ByteBufCodecs.VAR_INT, SetAeInputFilterAmountPayload::slotIndex,
			ByteBufCodecs.VAR_LONG, SetAeInputFilterAmountPayload::amount,
			SetAeInputFilterAmountPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
