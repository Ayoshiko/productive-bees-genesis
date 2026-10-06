package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 合并功能和设备引用；材料留在原账户，避免跨账户转存带来第二份资产所有权。 */
public final class WirelessTerminalMerge {
	public enum Status { MERGED, INVALID, CONFLICT, MATERIALS, ENERGY_CAPACITY, NOT_OWNER, UNAVAILABLE }

	public static Status merge(ServerPlayer player, boolean simulate) {
		if (!valid(player)) return Status.UNAVAILABLE;
		if (!simulate && !TerminalPayloads.allow(player)) return Status.UNAVAILABLE;
		try { return execute(player, simulate); }
		catch (RuntimeException error) {
			com.mojang.logging.LogUtils.getLogger().warn("Wireless terminal merge stopped for {}", player.getUUID(), error);
			return Status.UNAVAILABLE;
		}
	}
	private static boolean valid(ServerPlayer player) {
		return player.server.isSameThread() && ModConfig.SERVER.beeNetwork.enabled.get() && player.isAlive() && !player.isRemoved() && !player.isSpectator()
				&& player.containerMenu == player.inventoryMenu && player.getInventory().selected >= 0 && player.getInventory().selected < 9;
	}
	private static Status execute(ServerPlayer player, boolean simulate) {
		var main = player.getMainHandItem(); var off = player.getOffhandItem();
		if (main.getCount() != 1 || off.getCount() != 1 || !(main.is(NetworkContent.WIRELESS_BEE.get()) && off.is(NetworkContent.WIRELESS_CENTRIFUGE.get())
				|| main.is(NetworkContent.WIRELESS_CENTRIFUGE.get()) && off.is(NetworkContent.WIRELESS_BEE.get()))) return Status.INVALID;
		var beforeMain = main.copy(); var beforeOff = off.copy();
		int firstEnergy = WirelessTerminalItem.energy(main), secondEnergy = WirelessTerminalItem.energy(off);
		if (firstEnergy < 0 || secondEnergy < 0) return Status.INVALID;
		int energy = firstEnergy + secondEnergy; if (energy > WirelessTerminalItem.CAPACITY) return Status.ENERGY_CAPACITY;
		var first = WirelessTerminalItem.binding(main); var second = WirelessTerminalItem.binding(off);
		if (!validBinding(main, first) || !validBinding(off, second)) return Status.INVALID;
		if (!sameConfiguration(main, off) || first != null && second != null && !sameTarget(first, second)) return Status.CONFLICT;
		if (!owns(player, first) || !owns(player, second)) return Status.NOT_OWNER;
		var firstAccount = account(player, first); var secondAccount = account(player, second);
		if (first != null && firstAccount == null || second != null && secondAccount == null
				|| firstAccount != null && firstAccount.busy() || secondAccount != null && secondAccount.busy()) return Status.UNAVAILABLE;
		var firstState = firstAccount == null ? null : firstAccount.state();
		var secondState = secondAccount == null ? null : secondAccount.state();
		if (firstAccount != secondAccount && occupied(firstState) && occupied(secondState)) return Status.MATERIALS;
		boolean keepOff = occupied(secondState) && !occupied(firstState) || first == null && second != null;
		var retained = keepOff ? second : first;
		var result = NetworkContent.WIRELESS_COMBINED.get().getDefaultInstance();
		result.applyComponents((keepOff ? beforeOff : beforeMain).getComponentsPatch());
		var data = WirelessTerminalItem.data(result); data.putInt("energy", energy);
		if (!simulate && retained != null) data.getCompound("binding").putUUID("token", UUID.randomUUID());
		WirelessTerminalItem.data(result, data);
		if (!current(player, main, off, beforeMain, beforeOff, firstAccount, firstState, secondAccount, secondState)) return Status.UNAVAILABLE;
		if (simulate) return Status.MERGED;
		if (firstAccount != null) firstAccount.busy(true);
		if (secondAccount != null && secondAccount != firstAccount) secondAccount.busy(true);
		try {
			// 两个原版列表槽连续提交，其间不调用库存能力、事件或外部接收方。
			int selected = player.getInventory().selected;
			player.getInventory().items.set(selected, result);
			player.getInventory().offhand.set(0, ItemStack.EMPTY);
			player.getInventory().setChanged();
			CoreInventorySync.committed(player, selected, result);
			CoreInventorySync.committed(player, 40, ItemStack.EMPTY);
			return Status.MERGED;
		} finally {
			if (secondAccount != null && secondAccount != firstAccount) secondAccount.busy(false);
			if (firstAccount != null) firstAccount.busy(false);
		}
	}
	private static boolean validBinding(ItemStack stack, WirelessTerminalItem.Binding binding) {
		var data = WirelessTerminalItem.data(stack);
		if (!data.contains("binding")) return true;
		if (binding == null || !((WirelessTerminalItem) stack.getItem()).supports(binding.mode())) return false;
		var tag = data.getCompound("binding");
		return binding.network() != null ? tag.getCompound("network").equals(
				com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointCodec.identity(binding.network()))
				: tag.getCompound("machine").getAllKeys().equals(Set.of("id", "owner", "generation"));
	}
	private static boolean sameConfiguration(ItemStack first, ItemStack second) {
		if (!first.getComponentsPatch().forget(type -> type == DataComponents.CUSTOM_DATA)
				.equals(second.getComponentsPatch().forget(type -> type == DataComponents.CUSTOM_DATA))) return false;
		var rootA = root(first); var rootB = root(second);
		rootA.remove(WirelessTerminalItem.KEY); rootB.remove(WirelessTerminalItem.KEY);
		var configA = WirelessTerminalItem.data(first); var configB = WirelessTerminalItem.data(second);
		for (var config : new CompoundTag[]{configA, configB}) { config.remove("energy"); config.remove("binding"); }
		return rootA.equals(rootB) && configA.equals(configB);
	}
	private static CompoundTag root(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
	private static boolean sameTarget(WirelessTerminalItem.Binding first, WirelessTerminalItem.Binding second) {
		return first.dimension().equals(second.dimension()) && first.position().equals(second.position())
				&& Objects.equals(first.network(), second.network()) && Objects.equals(first.machine(), second.machine());
	}
	private static boolean owns(ServerPlayer player, WirelessTerminalItem.Binding binding) {
		return binding == null || player.getUUID().equals(binding.network() != null ? binding.network().ownerId() : binding.machine().owner());
	}
	private static TerminalCraftingAccount account(ServerPlayer player, WirelessTerminalItem.Binding binding) {
		return binding == null ? null : TerminalCraftingAccount.wireless(player, binding.device(), player.getUUID(), false);
	}
	private static boolean occupied(TerminalCraftingAccount.State state) {
		return state != null && (!state.pending().isEmpty() || state.grid().stream().anyMatch(stack -> !stack.isEmpty()));
	}
	private static boolean current(ServerPlayer player, ItemStack main, ItemStack off, ItemStack beforeMain, ItemStack beforeOff,
			TerminalCraftingAccount first, TerminalCraftingAccount.State firstState, TerminalCraftingAccount second, TerminalCraftingAccount.State secondState) {
		return valid(player) && player.getMainHandItem() == main && player.getOffhandItem() == off
				&& ItemStack.matches(main, beforeMain) && ItemStack.matches(off, beforeOff)
				&& (first == null || first.available() && !first.busy() && first.state() == firstState)
				&& (second == null || second.available() && !second.busy() && second.state() == secondState);
	}
	private WirelessTerminalMerge() { }
}
