package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server update for one exact AE input entry's pull amount or stock reserve. */
public record SetAeInputFilterAmountPayload(BlockPos pos, int slotIndex, long amount, boolean reserve)
		 {
	public SetAeInputFilterAmountPayload(BlockPos pos, int slotIndex, long amount) {
		this(pos, slotIndex, amount, false);
	}

    public static SetAeInputFilterAmountPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SetAeInputFilterAmountPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarLong(), buf.readBoolean());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        buf.writeVarLong(amount);
        buf.writeBoolean(reserve);
    }
}
