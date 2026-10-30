package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 客户端到服务端：切换单个机械蜂箱的相邻离心机快速直连通道。 */
public record ToggleApiaryDirectEjectPayload(BlockPos pos)  {

    public static ToggleApiaryDirectEjectPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ToggleApiaryDirectEjectPayload(buf.readBlockPos());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
    }
}
