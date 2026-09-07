package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：切换机械蜂箱内单个喂食槽的禁用状态
 * <br/>
 * 禁用后该格物品不再作为花朵/转化原料参与蜜蜂产出。服务端为唯一权威：
 * 空格子、越界索引、跨方块操作与超距操作均由 handler 拒绝，客户端不擅自改状态，
 * 等容器 tracker 回传位掩码后才更新渲染（与项目其他 per-tile 开关一致）。
 *
 * @param pos       蜂箱方块坐标
 * @param slotIndex 喂食槽索引（0~feederSlotCount-1）
 */
public record ToggleFeederSlotDisabledPayload(
		BlockPos pos,
		int slotIndex
) implements CustomPacketPayload {

	/** 数据包类型标识 */
	public static final CustomPacketPayload.Type<ToggleFeederSlotDisabledPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "toggle_feeder_slot_disabled"));

	/** 流编解码器 — 编解码 BlockPos + int */
	public static final StreamCodec<ByteBuf, ToggleFeederSlotDisabledPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ToggleFeederSlotDisabledPayload::pos,
					ByteBufCodecs.INT, ToggleFeederSlotDisabledPayload::slotIndex,
					ToggleFeederSlotDisabledPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
