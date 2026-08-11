package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<AeInputOutputSlotPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "ae_input_output_slot"));

	public static final StreamCodec<ByteBuf, AeInputOutputSlotPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, AeInputOutputSlotPayload::pos,
					ByteBufCodecs.VAR_INT, AeInputOutputSlotPayload::slotIndex,
					ByteBufCodecs.BOOL, AeInputOutputSlotPayload::shift,
					ByteBufCodecs.BOOL, AeInputOutputSlotPayload::rightClick,
					AeInputOutputSlotPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
