package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 仅存活于本次关闭请求；合成格先移交玩家保管，再按 ME 实际接收量结算。 */
final class TerminalCraftingMeReturn {
	private final TerminalCraftingMenu.Host menu;
	private final ServerPlayer player;
	private final TerminalCraftingAccount account;
	private final TerminalCursor cursor;
	private TerminalCraftingAccount.State expected;
	private int stored;

	private TerminalCraftingMeReturn(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalCraftingAccount account, TerminalCursor cursor) {
		this.menu = menu; this.player = player; this.account = account; this.cursor = cursor; expected = account.state();
	}
	static TerminalReply toMe(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalRequest request, TerminalCraftingAccount account) {
		if (!player.server.isSameThread() || !account.busy()) throw new IllegalStateException("ME crafting return requires its server-thread account lock");
		var cursor = TerminalCursor.get(player);
		var transfer = new TerminalCraftingMeReturn(menu, player, account, cursor);
		if (cursor.containerBusy || TerminalCursorExchange.unknown(player) || !cursor.pending.isEmpty()) return transfer.reply(request, UNAVAILABLE, 0);
		cursor.containerBusy = true;
		try { return transfer.run(request); }
		catch (RuntimeException | LinkageError failure) {
			com.mojang.logging.LogUtils.getLogger().error("ME crafting return stopped for {}; known remainder or unknown request retained without retry", player.getUUID(), failure);
			return transfer.reply(request, UNAVAILABLE, transfer.stored);
		} finally { cursor.containerBusy = false; }
	}
	private TerminalReply run(TerminalRequest request) {
		if (!current()) return reply(request, STALE, 0);
		if (expected.grid().stream().allMatch(ItemStack::isEmpty)) return fallback(request);
		var bridge = menu.craftingBridge(player);
		var link = bridge == null ? null : bridge.link();
		var source = link == null ? null : link.materials(player, null);
		if (source == null || !source.valid() || !MeTerminalBudget.expensive(player.server)) return fallback(request);
		String location = source.description();
		for (int slot = 0; slot < 9; slot++) {
			if (!current()) return reply(request, UNAVAILABLE, stored);
			var wanted = expected.grid().get(slot); if (wanted.isEmpty()) continue;
			if (menu.craftingBridge(player) != bridge || bridge.link() != link || !source.valid()) break;
			var key = ProductKeyCodec.item(wanted, player.registryAccess());
			if (!ItemStack.matches(wanted, ProductKeyCodec.item(key, wanted.getCount(), player.registryAccess())))
				throw new IllegalArgumentException("Lossy ME crafting return");
			var record = new TerminalCursorExchange.Request(wanted, true, location);
			var grid = expected.grid(); var nextGrid = TerminalCraftingPlan.copy(grid); nextGrid.set(slot, ItemStack.EMPTY);
			var reserved = account.prepare(expected, nextGrid, expected.pending(), expected.uncertain());
			if (!current()) return reply(request, STALE, stored);
			// 发布之间没有外部回调；执行 ME 之前，原格已不可操作且完整请求已登记。
			account.publishPrepared(expected, reserved); expected = reserved; cursor.request = record;
			int target = slot;
			var result = TerminalCursorExchange.complete(player, menu.craftingMenu(), wanted, true, location,
					offered -> source.insert(offered), () -> restore(target));
			stored += result.amount();
			if (result.outcome() == TerminalCursorExchange.Outcome.UNKNOWN || result.outcome() == TerminalCursorExchange.Outcome.RETAINED)
				return reply(request, UNAVAILABLE, stored);
		}
		return fallback(request);
	}
	private boolean current() {
		return player.containerMenu == menu.craftingMenu() && menu.craftingMenu().stillValid(player)
				&& menu.craftingAccount(player) == account && account.state() == expected
				&& TerminalCraftingMenu.exclusiveOwner(player, account)
				&& ItemStack.matches(cursor.item(), menu.craftingMenu().getCarried());
	}
	private void restore(int slot) {
		if (cursor.pending.isEmpty() || !current()) return;
		var grid = expected.grid(); var nextGrid = TerminalCraftingPlan.copy(grid); nextGrid.set(slot, cursor.pending);
		var restored = account.prepare(expected, nextGrid, expected.pending(), expected.uncertain());
		// 已知余量从玩家保管移回已核对的空格；通知发生在两份权威状态都发布之后。
		account.publishPrepared(expected, restored); expected = restored; cursor.pending = ItemStack.EMPTY;
	}
	private TerminalReply fallback(TerminalRequest request) {
		if (!current()) return reply(request, UNAVAILABLE, stored);
		var result = TerminalCraftingReturn.toInventory(menu, player, request, account);
		int moved = stored + result.moved();
		return reply(request, moved > 0 && (result.status() == MOVED || result.status() == NO_SPACE) ? MOVED : result.status(), moved);
	}
	private TerminalReply reply(TerminalRequest request, TerminalReply.Status status, int moved) {
		return new TerminalReply(menu.craftingMenu().containerId, menu.craftingSession(), request.sequence(), status, moved, 0, null);
	}
}
