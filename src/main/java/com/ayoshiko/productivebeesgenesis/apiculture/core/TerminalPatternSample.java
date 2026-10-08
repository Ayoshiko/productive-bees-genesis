package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 替换样本属于真实材料账户；只提供副本，不消耗物品或容器内容。 */
public final class TerminalPatternSample {
	private final TerminalCraftingAccount account;
	private final TerminalCraftingAccount.State state;
	private final ItemStack item;
	private TerminalPatternSample(TerminalCraftingAccount account, TerminalCraftingAccount.State state, ItemStack item) {
		this.account = account; this.state = state; this.item = item.copyWithCount(1);
	}
	public static TerminalPatternSample capture(ServerPlayer player, AbstractContainerMenu menu) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Pattern samples belong to the server thread");
		if (player.containerMenu != menu || !(menu instanceof TerminalCraftingMenu.Host host)) return null;
		var account = host.craftingAccount(player);
		if (account == null || !account.available() || account.busy()) return null;
		var state = account.state(); var item = state.grid().getFirst();
		if (item.isEmpty() || state.uncertain() || state.materialRequest() != null || !state.pending().isEmpty()) return null;
		return new TerminalPatternSample(account, state, item);
	}
	public ItemStack item() { return item.copy(); }
	public boolean current(ServerPlayer player, AbstractContainerMenu menu) {
		return !account.busy() && player.containerMenu == menu && menu instanceof TerminalCraftingMenu.Host host
				&& host.craftingAccount(player) == account && account.available() && account.state() == state;
	}
	public TerminalCursorExchange.Result commit(ServerPlayer player, AbstractContainerMenu menu, Supplier<TerminalCursorExchange.Result> apply) {
		if (!current(player, menu)) return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
		account.busy(true);
		try { return apply.get(); } finally { account.busy(false); }
	}
}
