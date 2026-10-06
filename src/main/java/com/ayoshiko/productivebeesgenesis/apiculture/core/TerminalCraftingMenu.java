package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameRules;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 菜单的有限合成投影与事务；原版槽仅同步副本，所有修改仍走有序命令。 */
public final class TerminalCraftingMenu {
	/** 两类菜单提供各自已经授权的账户，制作与余料提交只维护一份实现。 */
	public interface Host {
		net.minecraft.world.inventory.AbstractContainerMenu craftingMenu();
		UUID craftingSession();
		TerminalCraftingAccount craftingAccount(ServerPlayer player);
	}
	private final Host menu;
	private final SimpleContainer display = new SimpleContainer(10);
	private boolean visible, subscribed;
	private long generation, serial, nextCheck;
	private int flag;
	private TerminalCraftingAccount shownAccount;
	private TerminalCraftingAccount.State shown;
	private RecipeHolder<CraftingRecipe> recipe;
	private String failure;
	public TerminalCraftingMenu(Host menu) { this.menu = menu; }
	public Slot slot(int index, int x, int y) {
		return new Slot(display, index, x, y) {
			@Override public boolean mayPlace(ItemStack stack) { return false; }
			@Override public boolean mayPickup(Player player) { return false; }
			@Override public boolean isActive() { return visible; }
		};
	}
	public void visible(boolean value) { visible = value; }
	public long generation() { return generation; }
	public int flag() { return flag; }
	public ItemStack item(int index) { return display.getItem(index).copy(); }
	public void pause() { subscribed = false; shownAccount = null; shown = null; recipe = null; generation = 0; flag = 0; display.clearContent(); }
	public void refresh(ServerPlayer player, boolean force) {
		if (!subscribed) return;
		long now = player.server.overworld().getGameTime(); if (!force && now < nextCheck) return; nextCheck = now + 10;
		var account = menu.craftingAccount(player);
		if (account == null || account.busy()) { generation = 0; flag = 0; return; }
		var state = account.state();
		if (!force && account == shownAccount && state == shown && (generation != 0 || failure != null)
				&& (recipe == null || player.serverLevel().getRecipeManager().byKey(recipe.id()).orElse(null) == recipe)) return;
		if (!TerminalSubscriptionService.allowCrafting(player.server)) { generation = 0; return; }
		try {
			var grid = state.grid(); var positioned = CraftingInput.ofPositioned(3, 3, TerminalCraftingPlan.copy(grid));
			var found = find(player, positioned.input());
			var preview = state.pending().isEmpty() ? found == null ? ItemStack.EMPTY : found.value().assemble(positioned.input(), player.registryAccess()) : state.pending();
			if (!preview.isEmpty() && !preview.isItemEnabled(player.serverLevel().enabledFeatures())) preview = ItemStack.EMPTY;
			for (int i = 0; i < 9; i++) display.setItem(i, grid.get(i).copy()); display.setItem(9, preview.copy());
			shownAccount = account; shown = state; recipe = found; generation = Math.incrementExact(serial); serial = generation;
			flag = state.uncertain() ? 3 : state.pending().isEmpty() ? 1 : 2; failure = null;
		} catch (RuntimeException error) {
			if (failure == null) com.mojang.logging.LogUtils.getLogger().warn("Cannot preview terminal crafting for {}", player.getUUID(), error);
			failure = error.toString(); shownAccount = account; shown = state; recipe = null; display.setItem(9, ItemStack.EMPTY); generation = 0; flag = 0;
		}
	}
	public TerminalReply handle(ServerPlayer player, TerminalRequest request) {
		if (request.operation() == TerminalRequest.Operation.CRAFTING) {
			if (request.generation() != 0 || request.row() != -1 || request.targetSlot() != -1 || request.inventorySlot() != -1 || request.amount() != 0)
				return reply(request, INVALID, 0);
			subscribed = true; refresh(player, true);
			return reply(request, generation == 0 ? UNAVAILABLE : OK, 0);
		}
		var account = menu.craftingAccount(player);
		if (!subscribed || account == null || account.busy() || account != shownAccount || request.generation() != generation || generation == 0 || account.state() != shown)
			return reply(request, STALE, 0);
		if (request.targetSlot() != -1 || shown.uncertain()) return reply(request, shown.uncertain() ? UNAVAILABLE : INVALID, 0);
		account.busy(true); TerminalReply result;
		try { result = execute(player, request, account); }
		catch (RuntimeException error) {
			com.mojang.logging.LogUtils.getLogger().error("Terminal crafting command stopped without retry for {}", player.getUUID(), error);
			result = reply(request, UNAVAILABLE, 0);
		} finally { account.busy(false); }
		refresh(player, true); return result;
	}
	public TerminalReply fill(ServerPlayer player, TerminalRequest request, net.minecraft.resources.ResourceLocation id) {
		subscribed = true;
		var account = menu.craftingAccount(player);
		if (account == null || account.busy() || account.state().uncertain() || !account.state().pending().isEmpty()) return reply(request, UNAVAILABLE, 0);
		if (!TerminalSubscriptionService.allowCrafting(player.server)) return reply(request, UNAVAILABLE, 0);
		account.busy(true);
		try {
			var holder = player.serverLevel().getRecipeManager().byKey(id).orElse(null);
			if (holder == null || !(holder.value() instanceof CraftingRecipe target) || !TerminalRecipeFillPlan.supports(target)) return reply(request, INVALID, 0);
			if (!target.isSpecial() && player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) && !player.getRecipeBook().contains(holder)) return reply(request, INVALID, 0);
			var before = account.state(); var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
			var result = TerminalRecipeFillPlan.plan(target, before.grid(), inventory, request.amount() == 64);
			if (result.change() == null) return reply(request, result.failure() == TerminalRecipeFillPlan.Failure.NO_SPACE ? NO_SPACE
					: result.failure() == TerminalRecipeFillPlan.Failure.MISSING ? MISSING_INGREDIENTS : INVALID, 0);
			var change = result.change(); var input = CraftingInput.ofPositioned(3, 3, TerminalCraftingPlan.copy(change.grid())).input();
			if (!target.matches(input, player.serverLevel()) || !current(player, account, before, inventory)
					|| player.serverLevel().getRecipeManager().byKey(id).orElse(null) != holder) return reply(request, STALE, 0);
			if (ItemStack.listMatches(before.grid(), change.grid()) && ItemStack.listMatches(inventory, change.inventory())) return reply(request, OK, 0);
			account.publish(before, change.grid(), before.pending(), false); inventory(player, inventory, change.inventory());
			return reply(request, change.moved() > 0 ? MOVED : OK, change.moved());
		} catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Recipe fill rejected without retry for {}: {}", player.getUUID(), id, failure); return reply(request, UNAVAILABLE, 0);
		} finally { account.busy(false); refresh(player, true); }
	}
	private TerminalReply execute(ServerPlayer player, TerminalRequest request, TerminalCraftingAccount account) {
		var state = account.state(); var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
		var operation = request.operation(); TerminalCraftingPlan.Change change;
		if (operation == TerminalRequest.Operation.CRAFT_IN || operation == TerminalRequest.Operation.CRAFT_OUT) {
			if (operation == TerminalRequest.Operation.CRAFT_IN && request.inventorySlot() >= 0 && request.inventorySlot() < 36
					&& inventory.get(request.inventorySlot()).getItem() instanceof WirelessTerminalItem) return reply(request, INVALID, 0);
			if (request.row() < 0 || request.row() >= 9 || request.amount() < 1 || request.amount() > 64
					|| operation == TerminalRequest.Operation.CRAFT_OUT && request.inventorySlot() != -1) return reply(request, INVALID, 0);
			change = TerminalCraftingPlan.exchange(state.grid(), inventory, request.row(), request.inventorySlot(), request.amount(), operation == TerminalRequest.Operation.CRAFT_IN);
		} else if (operation == TerminalRequest.Operation.CRAFT_CLEAR) {
			if (request.row() != -1 || request.inventorySlot() != -1 || request.amount() != 0) return reply(request, INVALID, 0);
			change = TerminalCraftingPlan.clear(state.grid(), inventory);
		} else if (operation == TerminalRequest.Operation.CRAFT_TAKE) {
			if (request.row() != -1 || request.inventorySlot() != -1 || request.amount() < 1 || request.amount() > 8) return reply(request, INVALID, 0);
			if (!state.pending().isEmpty()) { int moved = drain(player, account); return reply(request, moved > 0 ? MOVED : NO_SPACE, moved); }
			int moved = 0, crafts = 0, blocked = -1;
			for (int i = 0; i < request.amount(); i++) {
				var outcome = craft(player, account); if (outcome < 0) { blocked = outcome; break; } crafts++; moved += outcome;
				if (!account.state().pending().isEmpty()) break;
			}
			return reply(request, moved > 0 ? MOVED : crafts > 0 ? OK : blocked == -2 ? UNAVAILABLE
					: display.getItem(9).isEmpty() ? EMPTY_OR_RESERVED : blocked == -3 ? STALE : NO_SPACE, moved);
		} else return reply(request, INVALID, 0);
		if (change == null) return reply(request, NO_SPACE, 0);
		if (!current(player, account, state, inventory)) return reply(request, STALE, 0);
		account.publish(state, change.grid(), state.pending(), false); inventory(player, inventory, change.inventory());
		return reply(request, MOVED, change.moved());
	}
	private int craft(ServerPlayer player, TerminalCraftingAccount account) {
		if (!TerminalSubscriptionService.allowCrafting(player.server)) return -2;
		var before = account.state(); var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
		var grid = before.grid(); var positioned = CraftingInput.ofPositioned(3, 3, TerminalCraftingPlan.copy(grid));
		var selected = find(player, positioned.input());
		if (selected == null || selected != recipe) return -3;
		var output = selected.value().assemble(positioned.input(), player.registryAccess());
		if (output.isEmpty() || !ItemStack.matches(output, display.getItem(9)) || !output.isItemEnabled(player.serverLevel().enabledFeatures())) return -3;
		List<ItemStack> remainders;
		net.neoforged.neoforge.common.CommonHooks.setCraftingPlayer(player);
		try { remainders = selected.value().getRemainingItems(positioned.input()); }
		finally { net.neoforged.neoforge.common.CommonHooks.setCraftingPlayer(null); }
		var plan = TerminalCraftingPlan.craft(grid, inventory, positioned, remainders, output);
		if (plan == null || !current(player, account, before, inventory)) return -1;
		// 在可能调用第三方代码之前发布已付费结果；任何回调失败都不重新扣料或制作。
		account.publish(before, plan.grid(), output, true); inventory(player, inventory, plan.inventory());
		var paid = account.state(); var processed = output.copy();
		try {
			processed.onCraftedBy(player.serverLevel(), player, output.getCount());
			var context = new TransientCraftingContainer(menu.craftingMenu(), 3, 3); for (int i = 0; i < 9; i++) context.setItem(i, grid.get(i).copy());
			net.neoforged.neoforge.event.EventHooks.firePlayerCraftingEvent(player, processed, context);
			player.triggerRecipeCrafted(selected, positioned.input().items());
			if (!selected.value().isSpecial()) player.awardRecipes(List.of(selected));
			if (processed.isEmpty()) throw new IllegalStateException("Crafting callback removed the paid result");
			account.publish(paid, paid.grid(), processed, false);
		} catch (RuntimeException error) {
			account.publish(paid, paid.grid(), processed.isEmpty() ? output : processed, true);
			com.mojang.logging.LogUtils.getLogger().error("Paid crafting result quarantined for {}", player.getUUID(), error); return 0;
		}
		return drain(player, account);
	}
	private int drain(ServerPlayer player, TerminalCraftingAccount account) {
		var before = account.state(); if (before.uncertain()) return 0;
		var inventory = TerminalCraftingPlan.copy(player.getInventory().items); var after = TerminalCraftingPlan.copy(inventory);
		var original = before.pending(); int requested = Math.min(64, original.getCount());
		var remaining = TerminalCraftingPlan.insert(after, original.copyWithCount(requested)); int moved = requested - remaining.getCount();
		var rest = original.copyWithCount(original.getCount() - moved);
		if (moved == 0 || !current(player, account, before, inventory)) return 0;
		account.publish(before, before.grid(), rest, false); inventory(player, inventory, after); return moved;
	}
	private boolean current(ServerPlayer player, TerminalCraftingAccount account, TerminalCraftingAccount.State before, List<ItemStack> inventory) {
		return menu.craftingAccount(player) == account && account.state() == before && ItemStack.listMatches(inventory, player.getInventory().items);
	}
	private static void inventory(ServerPlayer player, List<ItemStack> before, List<ItemStack> after) {
		for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) player.getInventory().items.set(i, after.get(i));
		player.getInventory().setChanged();
		for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) CoreInventorySync.committed(player, i, after.get(i));
	}
	private static RecipeHolder<CraftingRecipe> find(ServerPlayer player, CraftingInput input) {
		var found = player.serverLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, player.serverLevel()).orElse(null);
		return found != null && (found.value().isSpecial() || !player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
				|| player.getRecipeBook().contains(found)) ? found : null;
	}
	private TerminalReply reply(TerminalRequest request, TerminalReply.Status status, int moved) {
		return new TerminalReply(menu.craftingMenu().containerId, menu.craftingSession(), request.sequence(), status, moved, 0, null);
	}
}
