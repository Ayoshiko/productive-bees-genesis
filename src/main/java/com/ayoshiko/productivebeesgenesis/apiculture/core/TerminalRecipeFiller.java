package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameRules;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 内部材料先完整规划；外部材料逐格实际接收，未知请求持久保管且不自动退款或重试。 */
final class TerminalRecipeFiller {
	static TerminalReply fill(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalRequest request, ResourceLocation id, TerminalCraftingAccount account) {
		var holder = player.serverLevel().getRecipeManager().byKey(id).orElse(null);
		if (holder == null || !(holder.value() instanceof CraftingRecipe target) || !TerminalRecipeFillPlan.supports(target)) return reply(menu, request, INVALID, 0);
		if (!target.isSpecial() && player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) && !player.getRecipeBook().contains(holder)) return reply(menu, request, INVALID, 0);
		var before = account.state(); var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
		boolean maximum = request.amount() == 64;
		var result = TerminalRecipeFillPlan.plan(target, before.grid(), inventory, maximum);
		var ledger = menu.craftingLedger(player); var checkpoint = ledger == null ? null : ledger.checkpoint();
		List<ItemStack> internal = List.of();
		if (ledger != null && (maximum || result.failure() == TerminalRecipeFillPlan.Failure.MISSING)) {
			internal = TerminalRecipeLedger.candidates(target, checkpoint.ledger(), player.registryAccess());
			if (internal == null) return reply(menu, request, UNAVAILABLE, 0);
			result = TerminalRecipeFillPlan.plan(target, before.grid(), inventory, internal, maximum);
		}
		var bridge = menu.craftingBridge(player);
		TerminalMaterialSource source = null;
		if (bridge != null && (maximum || result.failure() == TerminalRecipeFillPlan.Failure.MISSING)) {
			source = bridge.link().materials(player, ledger);
			if (source != null) {
				var external = source.candidates(target, 128 - internal.size());
				if (external == null) return reply(menu, request, UNAVAILABLE, 0);
				var supplies = new ArrayList<>(internal); supplies.addAll(external);
				result = TerminalRecipeFillPlan.plan(target, before.grid(), inventory, supplies, maximum);
			}
		}
		if (result.change() == null) return reply(menu, request, result.failure() == TerminalRecipeFillPlan.Failure.NO_SPACE ? NO_SPACE
				: result.failure() == TerminalRecipeFillPlan.Failure.MISSING ? MISSING_INGREDIENTS : INVALID, 0);
		var change = result.change(); var input = CraftingInput.ofPositioned(3, 3, TerminalCraftingPlan.copy(change.grid())).input();
		for (int slot = 0; slot < 36; slot++) if (menu.lockedNativeStack(player.getInventory().getItem(slot)) && !ItemStack.matches(inventory.get(slot), change.inventory().get(slot)))
			return reply(menu, request, UNAVAILABLE, 0);
		if (!target.matches(input, player.serverLevel()) || !current(menu, player, account, before, inventory, id, holder)
				|| ledger != null && (menu.craftingLedger(player) != ledger || ledger.checkpoint() != checkpoint)
				|| source != null && (!source.valid() || menu.craftingBridge(player) != bridge)) return reply(menu, request, STALE, 0);
		if (ItemStack.listMatches(before.grid(), change.grid()) && ItemStack.listMatches(inventory, change.inventory())) return reply(menu, request, OK, 0);

		var debits = new ArrayList<ItemStack>(); var externalNeeded = new ArrayList<ItemStack>();
		for (var wanted : result.withdrawals()) {
			int local = 0;
			for (var stack : internal) if (ItemStack.isSameItemSameComponents(wanted, stack)) local += stack.getCount();
			local = Math.min(local, wanted.getCount());
			if (local > 0) debits.add(wanted.copyWithCount(local));
			if (local < wanted.getCount()) externalNeeded.add(wanted.copyWithCount(wanted.getCount() - local));
		}
		if (!externalNeeded.isEmpty() && source == null) return reply(menu, request, STALE, 0);
		var initial = TerminalCraftingPlan.copy(change.grid());
		int outstanding = 0;
		for (var needed : externalNeeded) {
			int remaining = needed.getCount(); outstanding += remaining;
			for (int slot = 8; slot >= 0 && remaining > 0; slot--) {
				var stack = initial.get(slot);
				if (ItemStack.isSameItemSameComponents(stack, needed)) { int take = Math.min(stack.getCount(), remaining); stack.shrink(take); remaining -= take; }
			}
			if (remaining != 0) throw new IllegalStateException("Unallocated material deficit");
		}
		var prepared = account.prepare(before, initial, before.pending(), false);
		if (debits.isEmpty()) { account.publishPrepared(before, prepared); inventory(player, inventory, change.inventory()); }
		else {
			var next = checkpoint.withdrawCraftingProducts(checkpoint.ledger().revision(), TerminalRecipeLedger.debit(debits, player.registryAccess()));
			if (next == checkpoint || menu.craftingLedger(player) != ledger || ledger.checkpoint() != checkpoint
					|| !current(menu, player, account, before, inventory, id, holder)) return reply(menu, request, STALE, 0);
			// 发布后即使展示索引抛错，已扣的内部材料也必须交付到准备好的接收状态。
			try { ledger.publish(next); }
			finally { if (ledger.checkpoint() == next) { account.publishPrepared(before, prepared); inventory(player, inventory, change.inventory()); } }
		}
		int moved = Math.max(0, change.moved() - outstanding);
		// 九个有限材料槽本身就是接收区；没有凭空占位物，也没有外部回存事务。
		for (int slot = 0; slot < 9; slot++) {
			int missing = change.grid().get(slot).getCount() - initial.get(slot).getCount();
			if (missing <= 0) continue;
			if (menu.craftingAccount(player) != account || menu.craftingBridge(player) != bridge || !source.valid()
					|| player.serverLevel().getRecipeManager().byKey(id).orElse(null) != holder) return reply(menu, request, UNAVAILABLE, moved);
			var wanted = change.grid().get(slot).copyWithCount(missing);
			var awaiting = account.requestMaterial(account.state(), slot, wanted, source.description());
			try {
				int received = source.extract(wanted);
				account.receiveMaterial(awaiting, received); moved += received;
				if (received < missing) return reply(menu, request, MISSING_INGREDIENTS, moved);
			} catch (RuntimeException | LinkageError failure) {
				com.mojang.logging.LogUtils.getLogger().error("ME recipe material outcome unknown for {} at {}: {}; request retained, no retry", player.getUUID(), source.description(), wanted, failure);
				return reply(menu, request, UNAVAILABLE, moved);
			}
		}
		return reply(menu, request, moved > 0 ? MOVED : OK, moved);
	}
	private static boolean current(TerminalCraftingMenu.Host menu, ServerPlayer player, TerminalCraftingAccount account, TerminalCraftingAccount.State state,
			List<ItemStack> inventory, ResourceLocation id, RecipeHolder<?> recipe) {
		return menu.craftingAccount(player) == account && account.state() == state && ItemStack.listMatches(inventory, player.getInventory().items)
				&& player.serverLevel().getRecipeManager().byKey(id).orElse(null) == recipe;
	}
	private static void inventory(ServerPlayer player, List<ItemStack> before, List<ItemStack> after) {
		for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) player.getInventory().items.set(i, after.get(i));
		player.getInventory().setChanged();
		for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) CoreInventorySync.committed(player, i, after.get(i));
	}
	private static TerminalReply reply(TerminalCraftingMenu.Host menu, TerminalRequest request, TerminalReply.Status status, int moved) {
		return new TerminalReply(menu.craftingMenu().containerId, menu.craftingSession(), request.sequence(), status, moved, 0, null);
	}
	private TerminalRecipeFiller() { }
}
