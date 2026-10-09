package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** 一次请求内的外部材料来源；返回实际量，未知结果由接收账户保管请求并停止重试。 */
public interface TerminalMaterialSource {
	boolean valid();
	List<ItemStack> candidates(CraftingRecipe recipe, int limit);
	int extract(ItemStack requested);
	String description();
}
