package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：批量设置直连条目的拉取数量或过滤器级默认库存保留量。 */
public record SetAllAeInputFilterAmountPayload(BlockPos pos, long amount, boolean reserve)
		 {
	public SetAllAeInputFilterAmountPayload(BlockPos pos, long amount) {
		this(pos, amount, false);
	}

    public static SetAllAeInputFilterAmountPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SetAllAeInputFilterAmountPayload(buf.readBlockPos(), buf.readVarLong(), buf.readBoolean());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeVarLong(amount);
        buf.writeBoolean(reserve);
    }
}
