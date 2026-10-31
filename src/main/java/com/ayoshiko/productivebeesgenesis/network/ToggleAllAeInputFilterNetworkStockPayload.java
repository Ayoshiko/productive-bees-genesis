package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server toggle for network-stock mode on all exact entries. */
public record ToggleAllAeInputFilterNetworkStockPayload(BlockPos pos)  {

    public static ToggleAllAeInputFilterNetworkStockPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ToggleAllAeInputFilterNetworkStockPayload(buf.readBlockPos());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
    }
}
