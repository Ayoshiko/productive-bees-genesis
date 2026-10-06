package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

/** 最多 45 个完整组件组和 9 个材料位置；容量匹配避免宽泛标签抢走其它位置的唯一材料。 */
public final class TerminalRecipeFillPlan {
	public enum Failure { UNSUPPORTED, MISSING, NO_SPACE }
	public record Result(TerminalCraftingPlan.Change change, Failure failure) { }
	public static boolean supports(CraftingRecipe recipe) {
		return (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) && !recipe.getIngredients().isEmpty()
				&& recipe.getIngredients().size() <= 9 && recipe.canCraftInDimensions(3, 3);
	}
	public static Result plan(CraftingRecipe recipe, List<ItemStack> source, List<ItemStack> inventory, boolean maximum) {
		if (!supports(recipe) || source.size() != 9 || inventory.size() != 36) return new Result(null, Failure.UNSUPPORTED);
		var ingredients = new ArrayList<>(Collections.nCopies(9, Ingredient.EMPTY));
		int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
		for (int i = 0; i < recipe.getIngredients().size(); i++) ingredients.set((i / width) * 3 + i % width, recipe.getIngredients().get(i));
		var groups = new ArrayList<ItemStack>(); var totals = new ArrayList<Integer>();
		for (var collection : List.of(source, inventory)) for (var stack : collection) {
			if (stack.isEmpty() || stack.getItem() instanceof WirelessTerminalItem) continue;
			int group = group(groups, stack); if (group < 0) { groups.add(stack.copyWithCount(1)); totals.add(Math.min(576, stack.getCount())); }
			else totals.set(group, (int) Math.min(576L, totals.get(group) + (long) stack.getCount()));
		}
		var choices = new boolean[9][groups.size()]; var preferred = new int[9];
		for (int slot = 0; slot < 9; slot++) {
			preferred[slot] = group(groups, source.get(slot));
			for (int g = 0; g < groups.size(); g++) choices[slot][g] = !ingredients.get(slot).isEmpty() && ingredients.get(slot).test(groups.get(g).copy());
		}
		int[] selected = match(ingredients, groups, totals, choices, preferred, 1);
		if (selected == null) return new Result(null, Failure.MISSING);
		int count = 1;
		if (maximum) {
			int low = 2, high = 64;
			while (low <= high) { int mid = (low + high) / 2; var candidate = match(ingredients, groups, totals, choices, preferred, mid);
				if (candidate == null) high = mid - 1; else { selected = candidate; count = mid; low = mid + 1; } }
		}
		var oldGrid = TerminalCraftingPlan.copy(source); var player = TerminalCraftingPlan.copy(inventory);
		var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
		for (int slot = 0; slot < 9; slot++) if (selected[slot] >= 0) {
			var item = groups.get(selected[slot]); int remaining = count;
			for (var collection : List.of(oldGrid, player)) for (var stack : collection) {
				if (remaining == 0) break;
				if (ItemStack.isSameItemSameComponents(stack, item)) { int taken = Math.min(remaining, stack.getCount()); stack.shrink(taken); remaining -= taken; }
			}
			if (remaining != 0) throw new IllegalStateException("Recipe fill allocation lost its material");
			grid.set(slot, item.copyWithCount(count));
		}
		// 保留原网格中仍适用的多余材料；其它旧材料全部能放回背包才发布。
		for (var stack : oldGrid) {
			for (var target : grid) if (!target.isEmpty() && ItemStack.isSameItemSameComponents(stack, target)) {
				int taken = Math.min(stack.getCount(), Math.max(0, Math.min(64, target.getMaxStackSize()) - target.getCount())); target.grow(taken); stack.shrink(taken);
			}
			if (!TerminalCraftingPlan.insert(player, stack).isEmpty()) return new Result(null, Failure.NO_SPACE);
		}
		int moved = 0;
		for (int i = 0; i < 9; i++) moved += Math.max(0, grid.get(i).getCount()
				- (ItemStack.isSameItemSameComponents(source.get(i), grid.get(i)) ? source.get(i).getCount() : 0));
		return new Result(new TerminalCraftingPlan.Change(grid, player, moved), null);
	}
	private static int group(List<ItemStack> groups, ItemStack stack) {
		if (stack.isEmpty()) return -1;
		for (int i = 0; i < groups.size(); i++) if (ItemStack.isSameItemSameComponents(groups.get(i), stack)) return i; return -1;
	}
	private static int[] match(List<Ingredient> ingredients, List<ItemStack> groups, List<Integer> totals, boolean[][] choices, int[] preferred, int count) {
		var capacity = new int[groups.size()]; for (int i = 0; i < capacity.length; i++) capacity[i] = count <= groups.get(i).getMaxStackSize() ? totals.get(i) / count : 0;
		var selected = new int[9]; Arrays.fill(selected, -1); var used = new int[groups.size()];
		for (int slot = 0; slot < 9; slot++) if (!ingredients.get(slot).isEmpty()
				&& !assign(slot, choices, preferred, capacity, selected, used, new boolean[9], new boolean[groups.size()])) return null;
		return selected;
	}
	private static boolean assign(int slot, boolean[][] choices, int[] preferred, int[] capacity, int[] selected, int[] used, boolean[] seenSlots, boolean[] seenGroups) {
		if (seenSlots[slot]) return false; seenSlots[slot] = true;
		for (int step = -1; step < capacity.length; step++) {
			int g = step < 0 ? preferred[slot] : step;
			if (g < 0 || seenGroups[g] || !choices[slot][g] || capacity[g] == 0) continue; seenGroups[g] = true;
			if (used[g] >= capacity[g]) {
				boolean freed = false;
				for (int other = 0; other < 9; other++) if (selected[other] == g && assign(other, choices, preferred, capacity, selected, used, seenSlots, seenGroups)) { freed = true; break; }
				if (!freed) continue;
			}
			if (selected[slot] >= 0) used[selected[slot]]--; selected[slot] = g; used[g]++; return true;
		}
		return false;
	}
	private TerminalRecipeFillPlan() { }
}
