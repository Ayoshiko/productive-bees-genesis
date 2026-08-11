package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
	 * 客户端 → 服务端：切换指定离心机的 per-tile 电力熔炼炉配方兼容开关
	 * <br/>
	 * 携带 {@link BlockPos}，服务端校验玩家身份、8 格交互距离与全局总开关后，
	 * 调用状态持有者的 {@code toggleSmeltingCompatEnabled()} 执行切换并持久化。
	 *
	 * @param pos 方块坐标
	 */
public record ToggleSmeltingCompatPayload(
		BlockPos pos
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ToggleSmeltingCompatPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "toggle_smelting_compat"));

	public static final StreamCodec<ByteBuf, ToggleSmeltingCompatPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ToggleSmeltingCompatPayload::pos,
					ToggleSmeltingCompatPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
