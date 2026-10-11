package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 关闭回存只处理已知九格材料；账本与接收区全部准备完才发布，不调用外部库存。 */
final class TerminalCraftingReturn {
	static TerminalReply toNetwork(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalRequest request, TerminalCraftingAccount account) {
		return transfer(menu, player, request, account, true);
	}
	static TerminalReply toInventory(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalRequest request, TerminalCraftingAccount account) {
		return transfer(menu, player, request, account, false);
	}
	private static TerminalReply transfer(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalRequest request, TerminalCraftingAccount account, boolean network) {
		if (!player.server.isSameThread() || !account.busy()) throw new IllegalStateException("Crafting return requires its server-thread account lock");
		var before = account.state(); var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
		var grid = TerminalCraftingPlan.copy(before.grid());
		var ledger = network ? menu.craftingLedger(player) : null; var checkpoint = ledger == null ? null : ledger.checkpoint();
		var policy = ledger == null ? null : RuntimeProductPolicies.peek(player.serverLevel());
		var next = checkpoint; int stored = 0;
		if (policy != null) for (int slot = 0; slot < 9; slot++) {
			var stack = grid.get(slot); if (stack.isEmpty()) continue;
			var key = ProductKeyCodec.item(stack, player.registryAccess());
			if (!ItemStack.matches(stack, ProductKeyCodec.item(key, stack.getCount(), player.registryAccess())))
				throw new IllegalArgumentException("Lossy crafting return");
			var candidate = next.insertProduct(next.ledger().revision(), key, stack.getCount(), policy);
			if (candidate != next) { next = candidate; stored += stack.getCount(); grid.set(slot, ItemStack.EMPTY); }
		}
		var fallback = TerminalCraftingPlan.clear(grid, inventory);
		var remaining = fallback == null ? grid : fallback.grid();
		var received = fallback == null ? inventory : fallback.inventory();
		int moved = stored + (fallback == null ? 0 : fallback.moved());
		if (moved == 0) return reply(menu, request, NO_SPACE, 0);
		for (int slot = 0; slot < 36; slot++)
			if (menu.lockedNativeStack(player.getInventory().getItem(slot)) && !ItemStack.matches(inventory.get(slot), received.get(slot)))
				return reply(menu, request, UNAVAILABLE, 0);
		var prepared = account.prepare(before, remaining, before.pending(), before.uncertain());
		if (player.containerMenu != menu.craftingMenu() || !menu.craftingMenu().stillValid(player)
				|| menu.craftingAccount(player) != account || account.state() != before || !ItemStack.listMatches(inventory, player.getInventory().items)
				|| network && menu.craftingLedger(player) != ledger || ledger != null && (ledger.checkpoint() != checkpoint || RuntimeProductPolicies.peek(player.serverLevel()) != policy))
			return reply(menu, request, STALE, 0);
		if (next == checkpoint) {
			account.publishPrepared(before, prepared); TerminalCraftingMenu.inventory(player, inventory, received);
		} else {
			// 展示索引可在权威根发布后失败；已接收的材料必须同步扣除，旧代际不能再提交。
			try { ledger.publish(next); }
			finally {
				if (ledger.checkpoint() == next) { account.publishPrepared(before, prepared); TerminalCraftingMenu.inventory(player, inventory, received); }
			}
		}
		return reply(menu, request, MOVED, moved);
	}
	private static TerminalReply reply(TerminalCraftingMenu.Host menu, TerminalRequest request, TerminalReply.Status status, int moved) {
		return new TerminalReply(menu.craftingMenu().containerId, menu.craftingSession(), request.sequence(), status, moved, 0, null);
	}
	private TerminalCraftingReturn() { }
}
