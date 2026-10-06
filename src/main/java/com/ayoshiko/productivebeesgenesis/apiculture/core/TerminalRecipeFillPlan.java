package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

/** 最多 45 个本地组件组、128 个账本候选与九格；最小缺额匹配避免宽泛标签抢占唯一材料。 */
public final class TerminalRecipeFillPlan {
	public enum Failure { UNSUPPORTED, MISSING, NO_SPACE }
	public record Result(TerminalCraftingPlan.Change change, Failure failure, List<ItemStack> withdrawals) {
		public Result(TerminalCraftingPlan.Change change, Failure failure) { this(change, failure, List.of()); }
	}
	public static boolean supports(CraftingRecipe recipe) {
		return (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) && !recipe.getIngredients().isEmpty()
				&& recipe.getIngredients().size() <= 9 && recipe.canCraftInDimensions(3, 3);
	}
	public static Result plan(CraftingRecipe recipe, List<ItemStack> source, List<ItemStack> inventory, boolean maximum) {
		return plan(recipe, source, inventory, List.of(), maximum);
	}
	public static Result plan(CraftingRecipe recipe, List<ItemStack> source, List<ItemStack> inventory, List<ItemStack> supplies, boolean maximum) {
		if (!supports(recipe) || source.size() != 9 || inventory.size() != 36 || supplies.size() > 128) return new Result(null, Failure.UNSUPPORTED);
		var ingredients = new ArrayList<>(Collections.nCopies(9, Ingredient.EMPTY));
		int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
		for (int i = 0; i < recipe.getIngredients().size(); i++) ingredients.set((i / width) * 3 + i % width, recipe.getIngredients().get(i));
		var groups = new ArrayList<ItemStack>(); var totals = new ArrayList<Integer>(); var local = new ArrayList<Integer>();
		for (var collection : List.of(source, inventory, supplies)) for (var stack : collection) {
			if (stack.isEmpty() || stack.getItem() instanceof WirelessTerminalItem) continue;
			int g = group(groups, stack);
			if (g < 0) { g = groups.size(); groups.add(stack.copyWithCount(1)); totals.add(0); local.add(0); }
			totals.set(g, (int) Math.min(576L, totals.get(g) + (long) stack.getCount()));
			if (collection != supplies) local.set(g, (int) Math.min(576L, local.get(g) + (long) stack.getCount()));
		}
		var choices = new boolean[9][groups.size()]; var preferred = new int[9];
		for (int slot = 0; slot < 9; slot++) {
			preferred[slot] = group(groups, source.get(slot));
			for (int g = 0; g < groups.size(); g++) choices[slot][g] = !ingredients.get(slot).isEmpty() && ingredients.get(slot).test(groups.get(g).copy());
		}
		int[] selected = match(ingredients, groups, totals, local, choices, preferred, 1);
		if (selected == null) return new Result(null, Failure.MISSING);
		int count = 1;
		if (maximum) {
			int low = 2, high = 64;
			while (low <= high) { int mid = (low + high) / 2; var candidate = match(ingredients, groups, totals, local, choices, preferred, mid);
				if (candidate == null) high = mid - 1; else { selected = candidate; count = mid; low = mid + 1; } }
		}
		var oldGrid = TerminalCraftingPlan.copy(source); var player = TerminalCraftingPlan.copy(inventory);
		var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); var missing = new int[groups.size()];
		for (int slot = 0; slot < 9; slot++) if (selected[slot] >= 0) {
			var item = groups.get(selected[slot]); int remaining = count;
			for (var collection : List.of(oldGrid, player)) for (var stack : collection) {
				if (remaining == 0) break;
				if (ItemStack.isSameItemSameComponents(stack, item)) { int taken = Math.min(remaining, stack.getCount()); stack.shrink(taken); remaining -= taken; }
			}
			missing[selected[slot]] += remaining;
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
		var withdrawals = new ArrayList<ItemStack>();
		for (int g = 0; g < missing.length; g++) if (missing[g] > 0) withdrawals.add(groups.get(g).copyWithCount(missing[g]));
		return new Result(new TerminalCraftingPlan.Change(grid, player, moved), null, List.copyOf(withdrawals));
	}
	private static int group(List<ItemStack> groups, ItemStack stack) {
		if (stack.isEmpty()) return -1;
		for (int i = 0; i < groups.size(); i++) if (ItemStack.isSameItemSameComponents(groups.get(i), stack)) return i; return -1;
	}
	private static int[] match(List<Ingredient> ingredients, List<ItemStack> groups, List<Integer> totals, List<Integer> local,
			boolean[][] choices, int[] preferred, int count) {
		var capacity = new int[groups.size()];
		for (int g = 0; g < capacity.length; g++) capacity[g] = count <= groups.get(g).getMaxStackSize() ? totals.get(g) / count : 0;
		var selected = new int[9]; Arrays.fill(selected, -1); var used = new int[groups.size()];
		for (int slot = 0; slot < 9; slot++) if (!ingredients.get(slot).isEmpty()
				&& !assign(slot, choices, preferred, capacity, local, count, selected, used)) return null;
		return selected;
	}
	/** 九次增广的最小费用匹配：每少提取一件优先于全部九格的位置偏好。 */
	private static boolean assign(int start, boolean[][] choices, int[] preferred, int[] capacity, List<Integer> local,
			int count, int[] selected, int[] used) {
		int infinity = 1_000_000;
		var slots = new int[9]; Arrays.fill(slots, infinity); slots[start] = 0;
		var groups = new int[capacity.length]; Arrays.fill(groups, infinity);
		var previous = new int[capacity.length]; Arrays.fill(previous, -1);
		// 残量边 group -> 已分配 slot 允许交换宽泛材料；负费用只撤销原位置偏好。
		for (int round = 0; round < 9; round++) {
			boolean changed = false;
			for (int slot = 0; slot < 9; slot++) if (slots[slot] < infinity) {
				for (int g = 0; g < capacity.length; g++) if (capacity[g] > 0 && choices[slot][g] && selected[slot] != g) {
					int cost = slots[slot] + (preferred[slot] == g ? 0 : 1);
					if (cost < groups[g]) { groups[g] = cost; previous[g] = slot; changed = true; }
				}
			}
			for (int slot = 0; slot < 9; slot++) if (selected[slot] >= 0 && groups[selected[slot]] < infinity) {
				int cost = groups[selected[slot]] - (preferred[slot] == selected[slot] ? 0 : 1);
				if (cost < slots[slot]) { slots[slot] = cost; changed = true; }
			}
			if (!changed) break;
		}
		int best = -1, cost = infinity;
		for (int g = 0; g < capacity.length; g++) if (used[g] < capacity[g] && groups[g] < infinity) {
			int extra = Math.max(0, (used[g] + 1) * count - local.get(g)) - Math.max(0, used[g] * count - local.get(g));
			int candidate = groups[g] + extra * 10;
			if (candidate < cost) { best = g; cost = candidate; }
		}
		if (best < 0) return false;
		while (best >= 0) {
			int slot = previous[best], old = selected[slot];
			selected[slot] = best; used[best]++; if (old >= 0) used[old]--; best = old;
		}
		return true;
	}
	private TerminalRecipeFillPlan() { }
}
