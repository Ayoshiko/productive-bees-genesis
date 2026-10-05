package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/** 只操作私有副本；材料、余料和输出的空间全部确定后才允许发布。 */
public final class TerminalCraftingPlan {
	public record Change(List<ItemStack> grid, List<ItemStack> inventory, int moved) { }
	public static ArrayList<ItemStack> copy(List<ItemStack> source) {
		var result = new ArrayList<ItemStack>(source.size()); source.forEach(stack -> result.add(stack.copy())); return result;
	}
	public static ItemStack insert(List<ItemStack> target, ItemStack source) {
		var rest = source.copy();
		for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) for (int i = 0; i < target.size() && !rest.isEmpty(); i++) {
			var old = target.get(i);
			if (pass == 0 ? old.isEmpty() || !ItemStack.isSameItemSameComponents(old, rest) : !old.isEmpty()) continue;
			int count = old.isEmpty() ? 0 : old.getCount();
			int moved = Math.min(rest.getCount(), Math.max(0, Math.min(64, rest.getMaxStackSize()) - count));
			if (moved > 0) { target.set(i, rest.copyWithCount(count + moved)); rest.shrink(moved); }
		}
		return rest;
	}
	public static Change exchange(List<ItemStack> source, List<ItemStack> inventory, int slot, int playerSlot, int amount, boolean deposit) {
		if (source.size() != 9 || inventory.size() != 36 || slot < 0 || slot >= 9 || amount < 1 || amount > 64
				|| deposit && (playerSlot < 0 || playerSlot >= 36)) throw new IllegalArgumentException("Invalid crafting exchange");
		var grid = copy(source); var player = copy(inventory); var old = grid.get(slot);
		if (deposit) {
			var held = player.get(playerSlot);
			if (held.isEmpty() || !old.isEmpty() && !ItemStack.isSameItemSameComponents(old, held)) return null;
			int count = old.isEmpty() ? 0 : old.getCount();
			int moved = Math.min(Math.min(amount, held.getCount()), Math.max(0, Math.min(64, held.getMaxStackSize()) - count));
			if (moved == 0) return null;
			grid.set(slot, held.copyWithCount(count + moved)); held.shrink(moved); return new Change(grid, player, moved);
		}
		if (old.isEmpty()) return null;
		int wanted = Math.min(amount, old.getCount()); var rest = insert(player, old.copyWithCount(wanted));
		int moved = wanted - rest.getCount(); if (moved == 0) return null;
		old.shrink(moved); return new Change(grid, player, moved);
	}
	public static Change clear(List<ItemStack> source, List<ItemStack> inventory) {
		var grid = copy(source); var player = copy(inventory); int moved = 0;
		for (int i = 0; i < grid.size(); i++) {
			var old = grid.get(i); var rest = insert(player, old); moved += old.getCount() - rest.getCount(); grid.set(i, rest);
		}
		return moved == 0 ? null : new Change(grid, player, moved);
	}
	public static Change craft(List<ItemStack> source, List<ItemStack> inventory, CraftingInput.Positioned input,
			List<ItemStack> remainders, ItemStack output) {
		if (source.size() != 9 || inventory.size() != 36 || remainders.size() != input.input().size() || output.isEmpty())
			throw new IllegalArgumentException("Invalid crafting result");
		var grid = copy(source); var player = copy(inventory);
		for (int y = 0; y < input.input().height(); y++) for (int x = 0; x < input.input().width(); x++) {
			int index = x + input.left() + (y + input.top()) * 3;
			var old = grid.get(index); if (!old.isEmpty()) old.shrink(1);
			var rest = remainders.get(x + y * input.input().width()).copy();
			if (rest.isEmpty()) continue;
			if (old.isEmpty() || ItemStack.isSameItemSameComponents(old, rest)) {
				int count = old.isEmpty() ? 0 : old.getCount();
				int moved = Math.min(rest.getCount(), Math.max(0, Math.min(64, rest.getMaxStackSize()) - count));
				if (moved > 0) { grid.set(index, rest.copyWithCount(count + moved)); rest.shrink(moved); }
			}
			if (!insert(player, rest).isEmpty()) return null;
		}
		// 输出先成为宿主内已付费结果；回调之后再交给玩家，但初始空间不足不能扣料。
		if (!insert(copy(player), output).isEmpty()) return null;
		return new Change(grid, player, output.getCount());
	}
	private TerminalCraftingPlan() { }
}
