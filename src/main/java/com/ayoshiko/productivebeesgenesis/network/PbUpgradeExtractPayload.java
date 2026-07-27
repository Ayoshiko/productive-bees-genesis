package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：卸载指定类型的PB升级到输出槽
 * <br/>
 * 携带 {@link BlockPos} 和升级类型，服务端收到后从 pbUpgradeHandler
 * 查找对应类型的第一个升级物品，移到升级输出槽。
 * <p>
 * 设计原因：GUI选择升级类型后点击卸载按钮，通过网络包通知服务端执行。
 * 相比旧版按槽位索引提取，按类型提取更符合MEK升级窗口的交互模式。
 *
 * @param pos          蜂箱方块坐标
 * @param upgradeTypeId 升级类型ID（对应PbUpgradeType.id）
 */
public record PbUpgradeExtractPayload(
		BlockPos pos,
		String upgradeTypeId,
		boolean removeAll
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<PbUpgradeExtractPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "pb_upgrade_extract"));

	public static final StreamCodec<ByteBuf, PbUpgradeExtractPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, PbUpgradeExtractPayload::pos,
					// 限制升级类型ID最大64字符，防止恶意客户端发送超大字符串导致OOM
					ByteBufCodecs.stringUtf8(64), PbUpgradeExtractPayload::upgradeTypeId,
					ByteBufCodecs.BOOL, PbUpgradeExtractPayload::removeAll,
					PbUpgradeExtractPayload::new
			);

	public PbUpgradeExtractPayload(BlockPos pos, String upgradeTypeId) {
		this(pos, upgradeTypeId, false);
	}

	public PbUpgradeType getUpgradeType() {
		return PbUpgradeType.byId(upgradeTypeId);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
