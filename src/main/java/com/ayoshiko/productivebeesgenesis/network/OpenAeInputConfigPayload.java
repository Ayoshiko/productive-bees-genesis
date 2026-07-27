package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：请求打开 per-tile AE2 输入拉取配置窗口
 * <br/>
 * 携带 {@link BlockPos}，服务端校验玩家身份、8 格交互距离、
 * 方块实体是否为 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost} 后，
 * 调用 {@link Ae2PayloadHandlers#syncFilterToClients} 推送当前过滤器状态到客户端。
 * <p>
 * <b>设计原因</b>：NeoForge 中服务端无法直接打开客户端 Screen，故客户端点击按钮时
 * 立即在客户端打开 {@link com.ayoshiko.productivebeesgenesis.client.screen.GuiAeInputConfig}，
 * 同时发送此包请求服务端推送最新过滤器状态。客户端 Screen 通过
 * {@link SyncAeInputFilterEntriesPayload} 接收最新条目并刷新本地副本。
 * <p>
 * <b>不携带数据</b>：仅作为打开请求信号，服务端不需要任何额外参数即可定位方块实体并推送状态。
 * IAe2InputHost 由 Mixin 运行时注入到 ME/EME 工厂类，编译时不可见，
 * 但 {@code be instanceof IAe2InputHost} 在运行时有效（基础离心机和
 * AbstractMekCentrifugeFactory 直接 implements）。
 *
 * @param pos 方块坐标
 */
public record OpenAeInputConfigPayload(
		BlockPos pos
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OpenAeInputConfigPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "open_ae_input_config"));

	public static final StreamCodec<ByteBuf, OpenAeInputConfigPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, OpenAeInputConfigPayload::pos,
					OpenAeInputConfigPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
