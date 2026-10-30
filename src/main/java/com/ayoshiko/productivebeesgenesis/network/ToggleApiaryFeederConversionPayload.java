package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 客户端到服务端：切换单个机械蜂箱的喂食槽物品转化开关。 */
public record ToggleApiaryFeederConversionPayload(BlockPos pos)  {

    public static ToggleApiaryFeederConversionPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ToggleApiaryFeederConversionPayload(buf.readBlockPos());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
    }
}
