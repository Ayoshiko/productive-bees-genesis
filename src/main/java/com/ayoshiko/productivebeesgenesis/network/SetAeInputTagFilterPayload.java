package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：保存 per-tile smelt 输入的标签白/黑名单表达式。
 * <br/>
 * 独立于 {@link SetAeInputFilterEntryPayload}：标签过滤是文本表达式配置，
 * 与位置固定的蜜脾槽位模型无关；也不能挂到
 * {@link SyncAeInputFilterEntriesPayload} 上——该包的 {@code StreamCodec.composite}
 * 已用满 6 个字段上限。
 * <p>
 * 长度上限与 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2TagFilter#MAX_EXPRESSION_LENGTH}
 * 一致，编解码层即拒绝超长字符串，避免恶意包在服务端触发大字符串分配。
 *
 * @param pos       方块坐标
 * @param whitelist 白名单标签表达式（空串表示不限制）
 * @param blacklist 黑名单标签表达式（空串表示不排除）
 */
public record SetAeInputTagFilterPayload(
		BlockPos pos,
		String whitelist,
		String blacklist
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SetAeInputTagFilterPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "set_ae_input_tag_filter"));

	public static final StreamCodec<ByteBuf, SetAeInputTagFilterPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, SetAeInputTagFilterPayload::pos,
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH),
					SetAeInputTagFilterPayload::whitelist,
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH),
					SetAeInputTagFilterPayload::blacklist,
					SetAeInputTagFilterPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
