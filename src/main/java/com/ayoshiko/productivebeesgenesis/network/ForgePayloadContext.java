package com.ayoshiko.productivebeesgenesis.network;

import net.minecraft.world.entity.player.Player;

/** Handler context after SimpleChannel has scheduled work on the game thread. */
public record ForgePayloadContext(Player player) {}
