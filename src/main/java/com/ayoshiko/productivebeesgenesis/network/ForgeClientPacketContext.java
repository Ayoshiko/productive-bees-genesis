package com.ayoshiko.productivebeesgenesis.network;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Loaded only when receiving a server packet on the physical client. */
final class ForgeClientPacketContext {
    static Player player() { return Minecraft.getInstance().player; }
}
