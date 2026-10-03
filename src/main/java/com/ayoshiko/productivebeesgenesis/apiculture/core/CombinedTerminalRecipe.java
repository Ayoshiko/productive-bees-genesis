package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** 当前两类有线终端不持资产；拒绝附加组件，不能让合并静默丢弃未知配置。 */
public final class CombinedTerminalRecipe extends CustomRecipe {
	public CombinedTerminalRecipe(CraftingBookCategory category) { super(category); }
	@Override public boolean matches(CraftingInput input, Level level) { return accepts(input); }
	private boolean accepts(CraftingInput input) {
		if (input.ingredientCount() != 2) return false;
		boolean bee = false, centrifuge = false;
		for (int i = 0; i < input.size(); i++) {
			var stack = input.getItem(i); if (stack.isEmpty()) continue;
			if (!bee && ItemStack.isSameItemSameComponents(stack, NetworkContent.BEE_TERMINAL_ITEM.get().getDefaultInstance())) bee = true;
			else if (!centrifuge && ItemStack.isSameItemSameComponents(stack, NetworkContent.CENTRIFUGE_TERMINAL_ITEM.get().getDefaultInstance())) centrifuge = true;
			else return false;
		}
		return bee && centrifuge;
	}
	@Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
		return accepts(input) ? NetworkContent.COMBINED_TERMINAL_ITEM.get().getDefaultInstance() : ItemStack.EMPTY;
	}
	@Override public boolean canCraftInDimensions(int width, int height) { return width > 0 && height > 0 && (width > 1 || height > 1); }
	@Override public RecipeSerializer<?> getSerializer() { return NetworkContent.COMBINE_RECIPE.get(); }
}
