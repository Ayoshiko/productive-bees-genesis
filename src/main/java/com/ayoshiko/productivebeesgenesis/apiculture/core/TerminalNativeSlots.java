package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 标准容器点击的服务器提交边界；普通槽使用原版语义，制作结果仍走已付费事务。 */
public final class TerminalNativeSlots {
	private final TerminalCraftingMenu.Host host;
	private final TerminalCraftingMenu crafting;
	private boolean inside, closed;
	public TerminalNativeSlots(TerminalCraftingMenu.Host host, TerminalCraftingMenu crafting) { this.host = host; this.crafting = crafting; }
	public void open(Player player) {
		if (player instanceof ServerPlayer server) TerminalCursor.restore(server, host.craftingMenu());
	}
	public void click(int index, int button, ClickType type, Player player, Runnable vanilla) {
		var menu = host.craftingMenu();
		if (closed || index >= menu.slots.size() || type == ClickType.THROW || type == ClickType.PICKUP && index < 0
				|| type == ClickType.SWAP && !safeSwap(index, button, player)) {
			if (player instanceof ServerPlayer) menu.broadcastFullState(); return;
		}
		if (!(player instanceof ServerPlayer server)) { if (index != 45) vanilla.run(); return; }
		if (inside || !host.nativeAllowed(server) || !TerminalCursor.get(server).available() || TerminalCursor.get(server).fluidBusy) { menu.broadcastFullState(); return; }
		var account = host.craftingAccount(server);
		if (account != null && account.busy() || index >= 36 && (account == null || account.state().uncertain())) { menu.broadcastFullState(); return; }
		if (index == 45) {
			inside = true; host.nativeEditing(true);
			try { if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) crafting.nativeResult(server, type == ClickType.QUICK_MOVE); }
			finally { TerminalCursor.get(server).set(menu.getCarried()); inside = false; host.nativeEditing(false); }
			menu.broadcastFullState(); return;
		}
		var before = account == null ? null : account.state();
		if (before != null) { crafting.nativeGrid(before); account.busy(true); }
		inside = true; host.nativeEditing(true);
		try { vanilla.run(); }
		finally {
			// 原版点击已交付到真实背包或鼠标；异常也不能回滚并重放已移动的物品。
			try {
				TerminalCursor.get(server).set(menu.getCarried());
				TerminalCursorExchange.recover(server, menu, false);
				if (account != null) {
					var grid = crafting.nativeGrid();
					if (!ItemStack.listMatches(before.grid(), grid)) account.publish(before, grid, before.pending(), before.uncertain());
				}
			} finally { if (account != null) account.busy(false); inside = false; host.nativeEditing(false); }
			player.getInventory().setChanged();
			crafting.refresh(server, true);
		}
	}
	private boolean safeSwap(int index, int button, Player player) {
		if (index < 0 || button < 0 || button > 8 && button != 40) return false;
		var slot = host.craftingMenu().slots.get(index); var hotbar = player.getInventory().getItem(button);
		// 原版在超大堆叠挤出原槽且背包满时会丢弃余量；该分支整次拒绝，资产留在原位。
		return !slot.hasItem() || hotbar.isEmpty() || hotbar.getCount() <= slot.getMaxStackSize(hotbar);
	}
	public ItemStack quickMove(Player player, int index) {
		if (index < 0 || index >= host.craftingMenu().slots.size()) return ItemStack.EMPTY;
		if (inside || !(player instanceof ServerPlayer)) return move(player, index);
		var result = new ItemStack[] { ItemStack.EMPTY };
		click(index, 0, ClickType.QUICK_MOVE, player, () -> result[0] = move(player, index));
		return result[0];
	}
	private ItemStack move(Player player, int index) {
		var menu = host.craftingMenu();
		if (index == 45) return ItemStack.EMPTY;
		Slot source = menu.slots.get(index);
		if (!source.mayPickup(player) || !source.hasItem()) return ItemStack.EMPTY;
		var stack = source.getItem(); var original = stack.copy();
		boolean moved;
		if (index >= 36) moved = host.moveNativeStack(stack, 0, 36, false);
		else if (crafting.nativeMaterialsAvailable(player)) moved = host.moveNativeStack(stack, 36, 45, false);
		else moved = index < 27 ? host.moveNativeStack(stack, 27, 36, false) : host.moveNativeStack(stack, 0, 27, false);
		if (!moved) return ItemStack.EMPTY;
		if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY); else source.setChanged();
		source.onTake(player, stack);
		return original;
	}
	public void close(Player player) {
		if (closed) return;
		closed = true;
		if (player instanceof ServerPlayer server) TerminalCursor.close(server, host.craftingMenu());
	}
	public boolean active() { return inside; }
}
