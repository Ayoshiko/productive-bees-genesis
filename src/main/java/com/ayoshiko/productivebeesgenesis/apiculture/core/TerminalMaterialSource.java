package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** 一次请求内的外部材料库存；存取返回实际量，未知结果由交接账户保管请求并停止重试。 */
public interface TerminalMaterialSource {
	boolean valid();
	List<ItemStack> candidates(CraftingRecipe recipe, int limit);
	int extract(ItemStack requested);
	/** 可写来源返回实际存入量；只读实现明确拒收。 */
	default int insert(ItemStack requested) { return 0; }
	String description();
}
