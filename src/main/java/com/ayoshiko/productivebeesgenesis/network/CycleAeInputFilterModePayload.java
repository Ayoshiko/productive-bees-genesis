package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
	 * 客户端 → 服务端：循环切换 per-tile AE2 输入过滤模式
	 * <br/>
	 * 携带 {@link BlockPos}，服务端校验玩家身份、8 格交互距离、
	 * 方块实体是否为 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost} 后，
	 * 通过 {@code productivebeesgenesis$getAeInputFilter()} 获取过滤器并切换模式：
	 * DISABLED → WHITELIST → BLACKLIST → DISABLED。
	 * <p>
	 * 设计原因：过滤器为 null 时（基础离心机未启用过滤）安全返回，不执行切换。
	 * 模式切换由服务端执行并持久化，避免客户端直接修改造成的状态不一致。
	 * IAe2InputHost 由 Mixin 运行时注入到 ME/EME 工厂类，运行时 instanceof 检查有效。
	 *
	 * @param pos 方块坐标
	 */
public record CycleAeInputFilterModePayload(
		BlockPos pos
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CycleAeInputFilterModePayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "cycle_ae_input_filter_mode"));

	public static final StreamCodec<ByteBuf, CycleAeInputFilterModePayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, CycleAeInputFilterModePayload::pos,
					CycleAeInputFilterModePayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
