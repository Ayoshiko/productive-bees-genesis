package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
	 * Client to server: interact with one virtual output slot of the AE2 input
	 * config GUI (AE2LT overloaded-interface style third row).
	 * <br/>
	 * Left click with empty cursor extracts from the ME network into the cursor;
	 * right click extracts half. Left click with a carried stack inserts it into
	 * the network; right click inserts one. Shift-click extracts into the
	 * player inventory (quick move).
	 *
	 * @param pos       centrifuge block position
	 * @param slotIndex global filter slot index
	 * @param shift     shift held (extract into inventory)
	 * @param rightClick right mouse button (half extract / single insert)
	 */
public record AeInputOutputSlotPayload(
		BlockPos pos,
		int slotIndex,
		boolean shift,
		boolean rightClick
)  {

    public static AeInputOutputSlotPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new AeInputOutputSlotPayload(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        buf.writeBoolean(shift);
        buf.writeBoolean(rightClick);
    }
}
