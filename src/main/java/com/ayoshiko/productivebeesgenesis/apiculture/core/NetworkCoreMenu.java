package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** 使用原版菜单按钮和只读计数同步；所有命令再次校验当前菜单、距离和所有者。 */
public final class NetworkCoreMenu extends AbstractContainerMenu {
	private final NetworkCoreBlockEntity core;
	private final ContainerData data;
	private final com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity exchangeNetwork;
	private boolean exchanging;
	public NetworkCoreMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
		super(NetworkContent.CORE_MENU.get(), id); buffer.readBlockPos(); core = null; exchangeNetwork = null; data = new SimpleContainerData(28); addDataSlots(data);
	}
	NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core) {
		super(NetworkContent.CORE_MENU.get(), id); this.core = core; exchangeNetwork = core.network();
		data = new ContainerData() {
			@Override public int get(int index) {
				if (index == 26) return core.productionRunning() ? 1 : 0;
				if (index == 27) return core.hasProductionSession() ? core.runtime().status().ordinal() : 0;
				if (index >= 18) {
					var authority = core.ownership().readyAuthority(); if (authority == null) return 0;
					var energy = authority.checkpoint().energy();
					long amount = index < 22 ? energy.stored() : energy.capacity();
					return (int) (amount >>> (((index - 18) % 4) * 16)) & 65535;
				}
				if (index == 17) return core.validNetworkReference() ? core.ownership().status().ordinal() : com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController.Status.RECOVERY.ordinal();
				var view = core.topology();
				if (index == 0) return !ModConfig.SERVER.beeNetwork.enabled.get() ? 0 : view == null ? 1 : !view.valid() ? 3 : 2;
				if (view == null) return 0;
				long count = switch ((index - 1) / 4 + 1) { case 1 -> view.members().size(); case 2 -> view.beeSlots(); case 3 -> view.lanes(); case 4 -> view.denied(); default -> 0; };
				return (int) (count >>> (((index - 1) % 4) * 16)) & 65535;
			}
			@Override public void set(int index, int value) { }
			@Override public int getCount() { return 28; }
		}; addDataSlots(data);
	}
	public long value(int index) {
		if (index == 0) return data.get(0);
		long result = 0; for (int part = 0; part < 4; part++) result |= (data.get(1 + (index - 1) * 4 + part) & 65535L) << (part * 16);
		return result;
	}
	public int ownershipStatus() { return data.get(17); }
	public boolean productionRunning() { return data.get(26) != 0; }
	public int runtimeStatus() { return data.get(27); }
	public long energy(boolean capacity) {
		long result = 0; int start = capacity ? 22 : 18;
		for (int part = 0; part < 4; part++) result |= (data.get(start + part) & 65535L) << (part * 16);
		return result;
	}
	@Override public boolean stillValid(Player player) { return core == null || core.allowed(player) && player.level().getBlockEntity(core.getBlockPos()) == core; }
	@Override public boolean clickMenuButton(Player player, int id) {
		if (core == null || player.containerMenu != this || !stillValid(player)) return false;
		if (id == 0) { core.requestRebuild(); return true; }
		if (id == 1 || id == 2) return core.ownership().command(id == 1);
		if (id == 3) return core.setProductionRunning(!core.productionRunning());
		return false;
	}
	@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
	NetworkCoreBlockEntity exchangeCore(net.minecraft.server.level.ServerPlayer player) {
		if (!player.serverLevel().getServer().isSameThread() || core == null || exchangeNetwork == null
				|| !exchangeNetwork.equals(core.network()) || !core.validNetworkReference() || !player.isAlive() || player.isSpectator()
				|| player.containerMenu != this || player.level() != core.getLevel()
				|| !player.serverLevel().hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4) || !stillValid(player)) return null;
		return core;
	}
	public CoreFeedingExchange.Result exchangeFeeding(net.minecraft.server.level.ServerPlayer player, java.util.UUID member,
			int feedingSlot, long expectedRevision, int inventorySlot, int requested, CoreFeedingExchange.Action action, boolean simulate) {
		if (exchangeCore(player) == null || exchanging) return new CoreFeedingExchange.Result(CoreFeedingExchange.Status.UNAVAILABLE, 0);
		exchanging = true;
		try { return CoreFeedingExchange.exchange(this, player, member, feedingSlot, expectedRevision, inventorySlot, requested, action, simulate); }
		finally { exchanging = false; }
	}
	public CoreProductWithdrawal.Result withdrawProduct(net.minecraft.server.level.ServerPlayer player,
			com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey key, long expectedLedgerRevision,
			int inventorySlot, int requested, boolean simulate) {
		if (exchangeCore(player) == null || exchanging) return new CoreProductWithdrawal.Result(CoreProductWithdrawal.Status.UNAVAILABLE, 0);
		exchanging = true;
		try { return CoreProductWithdrawal.withdraw(this, player, key, expectedLedgerRevision, inventorySlot, requested, simulate); }
		finally { exchanging = false; }
	}
	/** 单个蜂笼交换；与喂食和产物取回共用会话与重入保护。 */
	public CoreBeeCageExchange.Result exchangeBee(net.minecraft.server.level.ServerPlayer player, java.util.UUID member,
			int beeSlot, long expectedRevision, java.util.UUID expectedBee, int inventorySlot,
			CoreBeeCageExchange.Action action, boolean simulate) {
		if (exchangeCore(player) == null || exchanging) return CoreBeeCageExchange.result(CoreBeeCageExchange.Status.UNAVAILABLE);
		exchanging = true;
		try {
			return CoreBeeCageExchange.exchange(this, player, member, beeSlot, expectedRevision,
					expectedBee, inventorySlot, action, simulate);
		} finally { exchanging = false; }
	}
}
