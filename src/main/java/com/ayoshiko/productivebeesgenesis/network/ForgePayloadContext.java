package com.ayoshiko.productivebeesgenesis.network;

import net.minecraft.world.entity.player.Player;

/** Handler context after SimpleChannel has scheduled work on the game thread. */
public record ForgePayloadContext(Player player) {
    public net.minecraft.network.Connection connection(){return player instanceof net.minecraft.server.level.ServerPlayer server ? server.connection.connection : ForgeClientPacketContext.connection();}
    public void enqueueWork(Runnable task) { task.run(); }
}
