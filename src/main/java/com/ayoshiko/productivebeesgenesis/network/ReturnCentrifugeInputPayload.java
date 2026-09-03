package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：请求返还离心机输入槽中的待处理物品。
 * <br/>
 * 服务端优先返还到在线 AE2 网络；未连接时按 Mekanism 物品输出方向发送到相邻容器。
 * 服务端会重新校验打开的容器、玩家距离和离心机类型，客户端只提供方块坐标。
 *
 * @param pos 离心机方块坐标
 */
public record ReturnCentrifugeInputPayload(BlockPos pos) implements CustomPacketPayload {

	/** 数据包类型标识。 */
	public static final CustomPacketPayload.Type<ReturnCentrifugeInputPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "return_centrifuge_input"));

	/** 数据包编解码器。 */
	public static final StreamCodec<ByteBuf, ReturnCentrifugeInputPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ReturnCentrifugeInputPayload::pos,
					ReturnCentrifugeInputPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
