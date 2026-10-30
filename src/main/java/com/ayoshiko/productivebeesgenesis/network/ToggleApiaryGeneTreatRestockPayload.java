package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 客户端请求切换当前机械蜂箱的基因小食补货；状态由服务端校验后同步。 */
public record ToggleApiaryGeneTreatRestockPayload(BlockPos pos)  {

	/** 数据包注册标识。 */
	/** 只传目标坐标，客户端不能提供物品或模板。 */

    public static ToggleApiaryGeneTreatRestockPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ToggleApiaryGeneTreatRestockPayload(buf.readBlockPos());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
    }
}
