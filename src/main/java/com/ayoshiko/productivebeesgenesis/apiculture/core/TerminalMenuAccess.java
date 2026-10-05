package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** 有线位置与无线设备共用菜单，来源负责自己的身份、距离和生命周期。 */
interface TerminalMenuAccess {
	TerminalScope scope();
	boolean combined();
	boolean valid(Player player);
	boolean switchMode(ServerPlayer player, TerminalScope requested);
	TerminalCraftingAccount crafting(ServerPlayer player);
	default int energy() { return -1; }
	default boolean charge(Player player, boolean command) { return valid(player); }
}
