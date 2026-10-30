package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端到服务端：在机械蜂箱 GUI 中给指定蜜蜂喂食基因小食。
 * <p>
 * 数据包仅携带蜂箱位置和槽位索引。服务端会重新校验玩家容器、距离、槽位内容和
 * 光标物品，客户端不能直接提交蜜蜂 NBT 或基因数据。
 *
 * @param pos       蜂箱方块坐标
 * @param slotIndex 蜜蜂槽位索引
 */
public record ApiaryFeedBeePayload(
		BlockPos pos,
		int slotIndex
)  {

	/** 数据包类型标识。 */

	/** 方块坐标和槽位索引的流编解码器。 */

    public static ApiaryFeedBeePayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ApiaryFeedBeePayload(buf.readBlockPos(), buf.readVarInt());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
    }
}
