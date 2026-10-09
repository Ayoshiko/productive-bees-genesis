package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import java.util.UUID;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;

/** 固定网络核心实例与权威域；设备检查由两类无线入口共用。 */
public final class WirelessTerminalAccess implements TerminalMenuAccess {
	private final WirelessDeviceSession device;
	private final NetworkCoreBlockEntity core;
	private final NetworkSavedData authority;
	private WirelessTerminalAccess(WirelessDeviceSession device, NetworkCoreBlockEntity core) {
		this.device = device; this.core = core; authority = core.ownership().readyAuthority();
	}
	public static boolean open(ServerPlayer player, InteractionHand hand) {
		return open(player, new WirelessDeviceSession(player, hand), net.minecraft.world.item.ItemStack.EMPTY);
	}
	public static boolean open(ServerPlayer player, WirelessDeviceSession device, net.minecraft.world.item.ItemStack pickTarget) {
		if (!device.valid(player)) return false;
		var binding = device.binding();
		if (binding.machine() != null) return com.ayoshiko.productivebeesgenesis.multiblock.world.WirelessMachineAccess.open(player, device, pickTarget);
		var chunk = player.serverLevel().getChunkSource().getChunkNow(binding.position().getX() >> 4, binding.position().getZ() >> 4);
		if (chunk == null || !(chunk.getBlockEntity(binding.position()) instanceof NetworkCoreBlockEntity core)) return false;
		var access = new WirelessTerminalAccess(device, core); if (!access.valid(player)) return false;
		var session = UUID.randomUUID();
		return player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> {
			if (!access.valid(viewer)) return null;
			var menu = new NetworkCoreMenu(id, inventory, core, session, access);
			if (!pickTarget.isEmpty()) menu.meTerminal().seedPick(player, pickTarget, com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget.resolve(core, player));
			return menu;
		}, device.stack().getHoverName()), buffer -> {
			buffer.writeBlockPos(core.getBlockPos()); buffer.writeUUID(session); buffer.writeBoolean(false);
			if (device.combined()) buffer.writeEnum(binding.mode()); buffer.writeBoolean(!pickTarget.isEmpty()); buffer.writeInt(device.inventorySlot(player));
		}).isPresent();
	}
	boolean locks(net.minecraft.world.item.ItemStack stack) { return stack == device.stack(); }
	@Override public TerminalScope scope() { return device.binding().mode(); }
	@Override public boolean combined() { return device.combined(); }
	@Override public int energy() { return device.energy(); }
	@Override public boolean valid(Player player) {
		if (!device.valid(player)) return false;
		var binding = device.binding();
		if (!(core.getLevel() instanceof ServerLevel level) || player.level() != level || core.isRemoved() || !core.permits(player)
				|| binding.network() == null || !core.validNetworkReference() || !binding.network().equals(core.network())
				|| !binding.network().controllerId().equals(core.controller()) || authority == null
				|| core.ownership().readyAuthority() != authority || !authority.identity().equals(binding.network())) { device.revoke(); return false; }
		var chunk = level.getChunkSource().getChunkNow(binding.position().getX() >> 4, binding.position().getZ() >> 4);
		if (chunk == null || chunk.getBlockEntity(binding.position()) != core) { device.revoke(); return false; }
		return true;
	}
	@Override public boolean charge(Player player, boolean command) { return valid(player) && device.charge(player, command); }
	@Override public boolean switchMode(ServerPlayer player, TerminalScope requested) {
		if (!combined() || requested == scope() || requested == TerminalScope.ALL || !valid(player)) return false;
		WirelessTerminalItem.mode(device.stack(), device.binding(), requested); device.revoke(); return open(player, device.renewed(player), net.minecraft.world.item.ItemStack.EMPTY);
	}
	@Override public TerminalCraftingAccount crafting(ServerPlayer player) {
		if (!valid(player)) return null;
		try { return TerminalCraftingAccount.wireless(player, device.binding().device(), core.owner(), false); }
		catch (RuntimeException error) { device.revoke(); com.mojang.logging.LogUtils.getLogger().error("Wireless crafting account unavailable: {}", device.binding().device(), error); return null; }
	}
}
