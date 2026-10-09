package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;

/** 两类权威宿主共用设备持有、绑定、范围和收费；读取校验不改变 FE。 */
public final class WirelessDeviceSession {
	private final InteractionHand hand;
	private final int slot;
	private final ItemStack stack;
	private final WirelessTerminalItem.Binding binding;
	private boolean revoked;
	private long lastTick = Long.MIN_VALUE;
	public WirelessDeviceSession(Player player, InteractionHand hand) { this.hand = hand; slot = -1; stack = player.getItemInHand(hand); binding = WirelessTerminalItem.binding(stack); }
	/** 世界选块固定原背包／副手位置；不改变既有持手菜单绑定。 */
	public WirelessDeviceSession(Player player, int slot) {
		if (!WirelessPickRequest.validSlot(slot)) throw new IllegalArgumentException("Invalid wireless inventory slot");
		hand = null; this.slot = slot; stack = player.getInventory().getItem(slot); binding = WirelessTerminalItem.binding(stack);
	}
	public WirelessTerminalItem.Binding binding() { return binding; }
	public ItemStack stack() { return stack; }
	public int energy() { return WirelessTerminalItem.energy(stack); }
	public boolean combined() { return stack.getItem() instanceof WirelessTerminalItem item && item.combined(); }
	public void revoke() { revoked = true; }
	public boolean valid(Player player) {
		if (revoked) return false;
		int range = ModConfig.SERVER.beeNetwork.wirelessRange.get();
		if (!(player.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !ModConfig.SERVER.beeNetwork.enabled.get()
				|| binding == null || !(stack.getItem() instanceof WirelessTerminalItem item) || !item.supports(binding.mode())
				|| (hand == null ? player.getInventory().getItem(slot) : player.getItemInHand(hand)) != stack || stack.getCount() != 1 || !binding.equals(WirelessTerminalItem.binding(stack))
				|| player.isRemoved() || !player.isAlive() || player.isSpectator() || energy() <= 0
				|| !player.level().dimension().location().equals(binding.dimension())
				|| player.distanceToSqr(binding.position().getCenter()) > (double) range * range) { revoke(); return false; }
		return true;
	}
	public boolean charge(Player player, boolean command) {
		if (!valid(player)) return false;
		long tick = ((ServerLevel) player.level()).getServer().overworld().getGameTime();
		if (!command && lastTick == tick) return true;
		int cost = command ? ModConfig.SERVER.beeNetwork.wirelessCommandFe.get() : ModConfig.SERVER.beeNetwork.wirelessTickFe.get();
		if (energy() <= cost || !WirelessTerminalItem.spend(stack, cost)) { revoke(); return false; }
		if (!command) lastTick = tick;
		player.getInventory().setChanged(); return true;
	}
}
