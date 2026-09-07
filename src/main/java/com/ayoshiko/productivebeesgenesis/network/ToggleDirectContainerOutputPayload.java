package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：切换指定机器（蜂箱/离心机）的 per-tile 产物直通开关。
 * <br/>
 * 只携带 {@link BlockPos}：开关状态由服务端权威取反，客户端不发送目标值，
 * 避免并发点击导致状态覆盖（与 {@link ToggleSmeltingCompatPayload} 同一模式）。
 * <p>
 * 本开关与 AE2 无关（写的是相邻容器的 {@code IItemHandler} 能力），
 * 故注册与处理都不在 AE2 加载守卫之内。
 *
 * @param pos 方块坐标
 */
public record ToggleDirectContainerOutputPayload(
		BlockPos pos
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ToggleDirectContainerOutputPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "toggle_direct_container_output"));

	public static final StreamCodec<ByteBuf, ToggleDirectContainerOutputPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ToggleDirectContainerOutputPayload::pos,
					ToggleDirectContainerOutputPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
