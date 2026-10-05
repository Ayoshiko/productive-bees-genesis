package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

/** 仅转换全新有线终端；无线设备合并必须另走保留能量与材料的事务。 */
public final class WirelessTerminalRecipe extends CustomRecipe {
	public WirelessTerminalRecipe(CraftingBookCategory category) { super(category); }
	private ItemStack result(CraftingInput input) {
		if (input.ingredientCount() != 3) return ItemStack.EMPTY;
		boolean pearl = false, redstone = false; ItemStack output = ItemStack.EMPTY;
		for (var stack : input.items()) {
			if (stack.isEmpty()) continue;
			if (!pearl && ItemStack.isSameItemSameComponents(stack, Items.ENDER_PEARL.getDefaultInstance())) pearl = true;
			else if (!redstone && ItemStack.isSameItemSameComponents(stack, Items.REDSTONE.getDefaultInstance())) redstone = true;
			else if (output.isEmpty()) {
				if (ItemStack.isSameItemSameComponents(stack, NetworkContent.BEE_TERMINAL_ITEM.get().getDefaultInstance())) output = NetworkContent.WIRELESS_BEE.get().getDefaultInstance();
				else if (ItemStack.isSameItemSameComponents(stack, NetworkContent.CENTRIFUGE_TERMINAL_ITEM.get().getDefaultInstance())) output = NetworkContent.WIRELESS_CENTRIFUGE.get().getDefaultInstance();
				else if (ItemStack.isSameItemSameComponents(stack, NetworkContent.COMBINED_TERMINAL_ITEM.get().getDefaultInstance())) output = NetworkContent.WIRELESS_COMBINED.get().getDefaultInstance();
				else return ItemStack.EMPTY;
			} else return ItemStack.EMPTY;
		}
		return pearl && redstone ? output : ItemStack.EMPTY;
	}
	@Override public boolean matches(CraftingInput input, Level level) { return !result(input).isEmpty(); }
	@Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) { return result(input); }
	@Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 3; }
	@Override public RecipeSerializer<?> getSerializer() { return NetworkContent.WIRELESS_RECIPE.get(); }
}
