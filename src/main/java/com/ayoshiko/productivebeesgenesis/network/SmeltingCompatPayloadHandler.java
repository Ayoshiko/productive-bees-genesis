package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Handles the centrifuge smelting-compatibility toggle without linking the AE2 API. */
final class SmeltingCompatPayloadHandler {

	private SmeltingCompatPayloadHandler() {
	}

	static void handle(ToggleSmeltingCompatPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		if (!(serverPlayer.containerMenu instanceof MekanismTileContainer<?> tileContainer)
				|| !tileContainer.getTileEntity().getBlockPos().equals(payload.pos())) return;

		BlockEntity blockEntity = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(blockEntity instanceof IMekCentrifugeTile centrifuge)
				|| !(blockEntity instanceof IAe2OutputHostBase host)) return;
		if (serverPlayer.distanceToSqr(payload.pos().getCenter())
				> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("smelting_compat_distance", "Player {} tried to toggle smelting compatibility from too far away",
					serverPlayer.getName().getString());
			return;
		}
		if (ModConfig.SERVER == null || ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled == null
				|| !ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled.get()) return;

		host.productivebeesgenesis$getAe2StateHolder().toggleSmeltingCompatEnabled();
		centrifuge.productivebeesgenesis$onSmeltingCompatChanged();
		if (blockEntity instanceof TileEntityMekanism mekanismTile) mekanismTile.markForSave();
	}
}
