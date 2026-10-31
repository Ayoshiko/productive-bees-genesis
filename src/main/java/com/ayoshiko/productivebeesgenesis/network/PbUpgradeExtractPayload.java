package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import net.minecraft.core.BlockPos;
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
)  {

	public PbUpgradeExtractPayload(BlockPos pos, String upgradeTypeId) {
		this(pos, upgradeTypeId, false);
	}

	public PbUpgradeType getUpgradeType() {
		return PbUpgradeType.byId(upgradeTypeId);
	}

    public static PbUpgradeExtractPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new PbUpgradeExtractPayload(buf.readBlockPos(), buf.readUtf(64), buf.readBoolean());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeUtf(upgradeTypeId, 64);
        buf.writeBoolean(removeAll);
    }
}
