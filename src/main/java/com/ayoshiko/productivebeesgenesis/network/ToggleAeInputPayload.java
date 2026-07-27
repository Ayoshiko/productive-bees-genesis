package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：切换指定方块实体的 per-tile AE2 输入拉取开关
 * <br/>
 * 携带 {@link BlockPos}，服务端校验玩家身份、8 格交互距离、
 * 方块实体是否为 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost} 后调用
 * {@code productivebeesgenesis$toggleAeItemInput()} 执行切换。
 * <p>
 * 设计原因：GUI 中点击 AE2 输入拉取按钮时发送此包，服务端执行实际的状态切换
 * 并持久化到 NBT，避免客户端直接修改造成的状态不一致。
 * IAe2InputHost 由 Mixin 运行时注入到 ME/EME 工厂类，编译时不可见，
 * 但 {@code be instanceof IAe2InputHost} 在运行时有效（基础离心机和
 * AbstractMekCentrifugeFactory 直接 implements）。
 *
 * @param pos 方块坐标
 */
public record ToggleAeInputPayload(
		BlockPos pos
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ToggleAeInputPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "toggle_ae_input"));

	public static final StreamCodec<ByteBuf, ToggleAeInputPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ToggleAeInputPayload::pos,
					ToggleAeInputPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
