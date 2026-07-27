package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：通用机械蜂箱工厂切换排序开关数据包
 * <br/>
 * 仅携带 {@link BlockPos}，服务端收到后定位 {@link com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory}
 * 并调用 {@code toggleSorting()}。
 * <p>
 * 设计原因：MEK 原版 {@code PacketGuiInteract.AUTO_SORT_BUTTON} 检查
 * {@code tile instanceof TileEntityFactory<?>}，蜂箱工厂不继承该类无法复用。
 * <p>
 * 安全性：服务端处理时校验方块实体类型与玩家打开的容器一致性。
 *
 * @param pos 蜂箱工厂方块坐标
 */
public record ApiaryToggleSortingPayload(
		BlockPos pos
) implements CustomPacketPayload {

	/** 数据包类型标识 */
	public static final CustomPacketPayload.Type<ApiaryToggleSortingPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "apiary_toggle_sorting"));

	/** 流编解码器 — 仅编解码 BlockPos */
	public static final StreamCodec<ByteBuf, ApiaryToggleSortingPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ApiaryToggleSortingPayload::pos,
					ApiaryToggleSortingPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
