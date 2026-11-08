package com.ayoshiko.productivebeesgenesis.network;

import mekanism.api.security.IBlockSecurityUtils;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** 在每次机器请求提交前复核当前菜单和权限，不依赖可选物流模组。 */
final class MachineMenuAccess {
	private MachineMenuAccess() {
	}

	static boolean canAccess(Player player, BlockPos pos) {
		if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator()
				|| serverPlayer.level() == null
				|| !(serverPlayer.containerMenu instanceof MekanismTileContainer<?> container)) {
			return false;
		}
		var tile = container.getTileEntity();
		return tile != null && !tile.isRemoved() && tile.getLevel() == serverPlayer.level()
				&& tile.getBlockPos().equals(pos) && serverPlayer.level().hasChunkAt(pos)
				&& serverPlayer.level().getBlockEntity(pos) == tile && container.stillValid(serverPlayer)
				&& serverPlayer.distanceToSqr(pos.getCenter()) <= NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ
				&& IBlockSecurityUtils.INSTANCE.canAccess(serverPlayer, serverPlayer.level(), pos, tile);
	}
}
