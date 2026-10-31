package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：一键切换全部 AE 输入过滤直连条目的无限拉取状态。 */
public record ToggleAllAeInputFilterUnlimitedPayload(BlockPos pos)
		 {

    public static ToggleAllAeInputFilterUnlimitedPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ToggleAllAeInputFilterUnlimitedPayload(buf.readBlockPos());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
    }
}
