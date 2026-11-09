package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Handles the centrifuge smelting-compatibility toggle without linking the AE2 API. */
final class SmeltingCompatPayloadHandler {

	private SmeltingCompatPayloadHandler() {
	}

	static void handle(ToggleSmeltingCompatPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)
				|| !MachineMenuAccess.canAccess(serverPlayer, payload.pos())) {
			return;
		}

		BlockEntity blockEntity = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(blockEntity instanceof IMekCentrifugeTile centrifuge)
				|| !(blockEntity instanceof IAe2OutputHostBase host)) return;
		if (ModConfig.SERVER == null || ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled == null
				|| !ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled.get()) return;

		host.productivebeesgenesis$getAe2StateHolder().toggleSmeltingCompatEnabled();
		centrifuge.productivebeesgenesis$onSmeltingCompatChanged();
		if (blockEntity instanceof TileEntityMekanism mekanismTile) mekanismTile.markForSave();
	}
}
