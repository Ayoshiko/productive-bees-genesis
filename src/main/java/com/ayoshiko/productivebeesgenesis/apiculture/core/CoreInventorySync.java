package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 只同步已提交的绝对内容；发送失败不改变实物所有权。 */
final class CoreInventorySync {
	static void committed(ServerPlayer player, int slot, ItemStack stack) {
		try {
			player.connection.send(new ClientboundContainerSetSlotPacket(ClientboundContainerSetSlotPacket.PLAYER_INVENTORY, 0, slot, stack));
		} catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Committed bee network exchange could not sync player {}", player.getUUID(), failure);
		}
	}
	private CoreInventorySync() { }
}
