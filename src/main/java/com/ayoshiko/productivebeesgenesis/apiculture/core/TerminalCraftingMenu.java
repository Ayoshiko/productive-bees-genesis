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

/** 菜单的合成槽与已付费制作事务；原生点击和 JEI 命令共用同一账户。 */
public final class TerminalCraftingMenu {
	/** 两类菜单提供各自已经授权的账户，制作与余料提交只维护一份实现。 */
	public interface Host {
		net.minecraft.world.inventory.AbstractContainerMenu craftingMenu();
		UUID craftingSession();
		Player craftingPlayer();
		boolean nativeAllowed(ServerPlayer player);
		boolean lockedNativeStack(ItemStack stack);
		boolean moveNativeStack(ItemStack stack, int start, int end, boolean reverse);
		void nativeEditing(boolean value);
		TerminalCraftingAccount craftingAccount(ServerPlayer player);
		default com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity craftingBridge(ServerPlayer player) { return null; }
		default com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData craftingLedger(ServerPlayer player) { return null; }
	}
	private final Host menu;
	private final SimpleContainer result = new SimpleContainer(1);
	private final SimpleContainer display = new SimpleContainer(9) {
		@Override public ItemStack getItem(int slot) { readCurrent(); return super.getItem(slot); }
		@Override public ItemStack removeItem(int slot, int amount) { readCurrent(); return super.removeItem(slot, amount); }
		@Override public ItemStack removeItemNoUpdate(int slot) { readCurrent(); return super.removeItemNoUpdate(slot); }
		@Override public void setItem(int slot, ItemStack stack) { readCurrent(); super.setItem(slot, stack); }
	};
	private boolean synchronizing;
	private boolean visible, subscribed;
	private long generation, serial, nextCheck;
	private int flag;
	private TerminalCraftingAccount shownAccount;
	private TerminalCraftingAccount.State shown;
	private RecipeHolder<CraftingRecipe> recipe;
	private String failure;
	public TerminalCraftingMenu(Host menu) { this.menu = menu; display.addListener(ignored -> changedExternally()); }
	private void readCurrent() {
		if (synchronizing || menu == null || !(menu.craftingPlayer() instanceof ServerPlayer player)) return;
		var account = menu.craftingAccount(player);
		if (account == null || account.busy() || account == shownAccount && account.state() == shown) return;
		var state = account.state(); nativeGrid(state); shownAccount = account; shown = state; generation = 0;
	}
	private void changedExternally() {
		if (synchronizing || !(menu.craftingPlayer() instanceof ServerPlayer player)) return;
		var account = menu.craftingAccount(player);
		if (account == null || account.busy() || account.state().uncertain()) return;
		var before = account.state();
		// 同容器排序／槽 API 的 setChanged 也必须发布，不能只改一份显示副本。
		synchronizing = true;
		try {
			var grid = nativeGrid();
			if (!ItemStack.listMatches(before.grid(), grid)) account.publish(before, grid, before.pending(), before.uncertain());
			shownAccount = account; shown = account.state(); generation = 0;
		} finally { synchronizing = false; }
	}
	public Slot slot(int materialIndex, int x, int y) {
		return new Slot(materialIndex < 9 ? display : result, materialIndex < 9 ? materialIndex : 0, x, y) {
			@Override public boolean mayPlace(ItemStack stack) { return materialIndex < 9 && !(stack.getItem() instanceof WirelessTerminalItem) && nativeMaterialsAvailable(menu.craftingPlayer()); }
			@Override public boolean mayPickup(Player player) { return materialIndex < 9 && nativeMaterialsAvailable(player); }
			@Override public boolean isActive() { return menu.craftingPlayer() instanceof ServerPlayer ? nativeMaterialsAvailable(menu.craftingPlayer()) : visible; }
		};
	}
	public void visible(boolean value) { visible = value; }
	public boolean nativeMaterialsAvailable(Player player) {
		if (!(player instanceof ServerPlayer server)) return visible;
		var account = menu.craftingAccount(server);
		return account != null && !account.state().uncertain();
	}
	void nativeGrid(TerminalCraftingAccount.State state) {
		boolean previous = synchronizing; synchronizing = true;
		try { var grid = state.grid(); for (int i = 0; i < 9; i++) display.setItem(i, grid.get(i).copy()); }
		finally { synchronizing = previous; }
	}
	List<ItemStack> nativeGrid() {
		var grid = new ArrayList<ItemStack>(9); for (int i = 0; i < 9; i++) grid.add(display.getItem(i).copy()); return grid;
	}
	public void nativeResult(ServerPlayer player, boolean quick) {
		subscribed = true; refresh(player, true);
		var account = menu.craftingAccount(player);
		if (account == null || account.busy() || account.state().uncertain() || generation == 0) return;
		account.busy(true);
		try {
			for (int i = 0; i < (quick ? 64 : 1); i++) {
				if (!quick) {
					var state = account.state(); var output = state.pending().isEmpty() ? result.getItem(0) : state.pending();
					if (!cursorFits(player, output)) break;
					var held = menu.craftingMenu().getCarried(); int count = held.isEmpty() ? 0 : held.getCount();
					if (state.pending().isEmpty() && output.getCount() > output.getMaxStackSize() - count) break;
				}
				int moved = account.state().pending().isEmpty() ? craft(player, account, quick) : drain(player, account, quick);
				if (moved <= 0 || !account.state().pending().isEmpty()) break;
			}
		} finally { account.busy(false); refresh(player, true); }
	}
	private boolean cursorFits(ServerPlayer player, ItemStack output) {
		if (output.isEmpty()) return false;
		var held = menu.craftingMenu().getCarried();
		return held.isEmpty() || ItemStack.isSameItemSameComponents(held, output) && held.getCount() < held.getMaxStackSize();
	}
	public long generation() { return generation; }
	public int flag() { return flag; }
	public ItemStack item(int index) { return (index < 9 ? display.getItem(index) : result.getItem(0)).copy(); }
	public void pause() { subscribed = false; shownAccount = null; shown = null; recipe = null; generation = 0; flag = 0;
		synchronizing = true; try { display.clearContent(); result.clearContent(); } finally { synchronizing = false; }
	}
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
			synchronizing = true;
			try { for (int i = 0; i < 9; i++) display.setItem(i, grid.get(i).copy()); result.setItem(0, preview.copy()); }
			finally { synchronizing = false; }
			shownAccount = account; shown = state; recipe = found; generation = Math.incrementExact(serial); serial = generation;
			flag = state.uncertain() ? 3 : state.materialRequest() != null ? 4 : state.pending().isEmpty() ? 1 : 2; failure = null;
		} catch (RuntimeException error) {
			if (failure == null) com.mojang.logging.LogUtils.getLogger().warn("Cannot preview terminal crafting for {}", player.getUUID(), error);
			failure = error.toString(); shownAccount = account; shown = state; recipe = null;
			synchronizing = true; try { result.setItem(0, ItemStack.EMPTY); } finally { synchronizing = false; }
			generation = 0; flag = 0;
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
		if (account == null || account.busy() || account.state().uncertain() || account.state().materialRequest() != null || !account.state().pending().isEmpty()) return reply(request, UNAVAILABLE, 0);
		if (!TerminalSubscriptionService.allowCrafting(player.server)) return reply(request, UNAVAILABLE, 0);
		account.busy(true);
		try {
			return TerminalRecipeFiller.fill(menu, player, request, id, account);
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
		} else if (operation == TerminalRequest.Operation.CRAFT_CLEAR || operation == TerminalRequest.Operation.CRAFT_RETURN_ON_CLOSE) {
			if (request.row() != -1 || request.inventorySlot() != -1 || request.amount() != 0) return reply(request, INVALID, 0);
			if (operation == TerminalRequest.Operation.CRAFT_RETURN_ON_CLOSE && !exclusiveOwner(player, account)) return reply(request, UNAVAILABLE, 0);
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
					: result.getItem(0).isEmpty() ? EMPTY_OR_RESERVED : blocked == -3 ? STALE : NO_SPACE, moved);
		} else return reply(request, INVALID, 0);
		if (change == null) return reply(request, NO_SPACE, 0);
		if (!current(player, account, state, inventory)) return reply(request, STALE, 0);
		account.publish(state, change.grid(), state.pending(), false); inventory(player, inventory, change.inventory());
		return reply(request, MOVED, change.moved());
	}
	private boolean exclusiveOwner(ServerPlayer player, TerminalCraftingAccount account) {
		if (!account.ownedBy(player.getUUID())) return false;
		// 只在关闭请求时检查在线菜单；共享材料仍有人使用就保留，不维护逐 tick 观察表。
		for (var other : player.server.getPlayerList().getPlayers()) {
			if (other != player && other.containerMenu instanceof Host host && host.craftingAccount(other) == account) return false;
		}
		return true;
	}
	private int craft(ServerPlayer player, TerminalCraftingAccount account) { return craft(player, account, true); }
	private int craft(ServerPlayer player, TerminalCraftingAccount account, boolean inventoryOutput) {
		if (!TerminalSubscriptionService.allowCrafting(player.server)) return -2;
		var before = account.state(); var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
		var grid = before.grid(); var positioned = CraftingInput.ofPositioned(3, 3, TerminalCraftingPlan.copy(grid));
		var selected = find(player, positioned.input());
		if (selected == null || selected != recipe) return -3;
		var output = selected.value().assemble(positioned.input(), player.registryAccess());
		if (output.isEmpty() || !ItemStack.matches(output, result.getItem(0)) || !output.isItemEnabled(player.serverLevel().enabledFeatures())) return -3;
		List<ItemStack> remainders;
		net.neoforged.neoforge.common.CommonHooks.setCraftingPlayer(player);
		try { remainders = selected.value().getRemainingItems(positioned.input()); }
		finally { net.neoforged.neoforge.common.CommonHooks.setCraftingPlayer(null); }
		var plan = TerminalCraftingPlan.craft(grid, inventory, positioned, remainders, output, inventoryOutput);
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
		return drain(player, account, inventoryOutput);
	}
	private int drain(ServerPlayer player, TerminalCraftingAccount account) { return drain(player, account, true); }
	private int drain(ServerPlayer player, TerminalCraftingAccount account, boolean inventoryOutput) {
		var before = account.state(); if (before.uncertain()) return 0;
		if (!inventoryOutput) {
			var output = before.pending(); if (!cursorFits(player, output)) return 0;
			var held = menu.craftingMenu().getCarried();
			int count = held.isEmpty() ? 0 : held.getCount();
			int moved = Math.min(output.getCount(), Math.max(0, output.getMaxStackSize() - count));
			if (moved == 0) return 0;
			var received = output.copyWithCount(count + moved);
			account.publish(before, before.grid(), output.copyWithCount(output.getCount() - moved), false);
			menu.craftingMenu().setCarried(received); TerminalCursor.get(player).set(received); return moved;
		}
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
