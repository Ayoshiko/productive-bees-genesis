package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步 per-tile smelt 输入标签过滤表达式与其校验结果。
 * <br/>
 * errorKey 为空串表示无语法错误；非空时客户端据此显示本地化错误提示
 * （翻译键 {@code productivebeesgenesis.gui.tag_filter.error.<errorKey>}）。
 *
 * @param pos                方块坐标
 * @param whitelist          白名单标签表达式
 * @param blacklist          黑名单标签表达式
 * @param whitelistErrorKey  白名单语法错误键（空串=无错误）
 * @param blacklistErrorKey  黑名单语法错误键（空串=无错误）
 */
public record SyncAeInputTagFilterPayload(
		BlockPos pos,
		String whitelist,
		String blacklist,
		String whitelistErrorKey,
		String blacklistErrorKey
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SyncAeInputTagFilterPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "sync_ae_input_tag_filter"));

	public static final StreamCodec<ByteBuf, SyncAeInputTagFilterPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, SyncAeInputTagFilterPayload::pos,
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH),
					SyncAeInputTagFilterPayload::whitelist,
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH),
					SyncAeInputTagFilterPayload::blacklist,
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_TAG_FILTER_ERROR_KEY_LENGTH),
					SyncAeInputTagFilterPayload::whitelistErrorKey,
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_TAG_FILTER_ERROR_KEY_LENGTH),
					SyncAeInputTagFilterPayload::blacklistErrorKey,
					SyncAeInputTagFilterPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
