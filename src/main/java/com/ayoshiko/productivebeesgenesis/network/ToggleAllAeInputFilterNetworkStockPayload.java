package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server toggle for network-stock mode on all exact entries. */
public record ToggleAllAeInputFilterNetworkStockPayload(BlockPos pos) implements CustomPacketPayload {

	public static final Type<ToggleAllAeInputFilterNetworkStockPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID,
					"toggle_all_ae_input_filter_network_stock"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, ToggleAllAeInputFilterNetworkStockPayload> STREAM_CODEC =
			StreamCodec.composite(BlockPos.STREAM_CODEC,
					ToggleAllAeInputFilterNetworkStockPayload::pos,
					ToggleAllAeInputFilterNetworkStockPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
