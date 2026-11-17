package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.common.item.Honeycomb;
import cy.jdkdigital.productivebees.init.ModBlocks;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** 蜜脾块升级使用 PB 的确定映射，1:1 替换普通采蜜产物并保留数量。 */
public class CombBlockConverter {

	/** 转换结果不写入共享配方缓存，不同蜂箱的升级状态分别生效。 */
	public List<ItemStack> convertCombsToBlocks(List<ItemStack> items) {
		List<ItemStack> result = new ArrayList<>(items.size());
		for (ItemStack stack : items) {
			if (!stack.isEmpty()) {
				result.add(convertStack(stack));
			}
		}
		return result;
	}

	/** 转换调用方独占的生产列表，不另分配列表。 */
	List<ItemStack> convertCombsToBlocksInPlace(List<ItemStack> items) {
		int writeIndex = 0;
		for (int readIndex = 0; readIndex < items.size(); readIndex++) {
			ItemStack stack = items.get(readIndex);
			if (stack.isEmpty()) {
				continue;
			}
			items.set(writeIndex, convertStack(stack));
			writeIndex++;
		}
		if (writeIndex < items.size()) {
			items.subList(writeIndex, items.size()).clear();
		}
		return items;
	}

	private ItemStack convertStack(ItemStack stack) {
		if (stack.getItem() instanceof Honeycomb) {
			ItemStack block = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get(), stack.getCount());
			block.set(ModDataComponents.BEE_TYPE, stack.get(ModDataComponents.BEE_TYPE));
			return block;
		}
		if (stack.is(Items.HONEYCOMB)) {
			return new ItemStack(Blocks.HONEYCOMB_BLOCK, stack.getCount());
		}
		if (stack.is(ModItems.HONEYCOMB_GHOSTLY.get())) {
			return new ItemStack(ModBlocks.COMB_GHOSTLY.get(), stack.getCount());
		}
		if (stack.is(ModItems.HONEYCOMB_MILKY.get())) {
			return new ItemStack(ModBlocks.COMB_MILKY.get(), stack.getCount());
		}
		if (stack.is(ModItems.HONEYCOMB_POWDERY.get())) {
			return new ItemStack(ModBlocks.COMB_POWDERY.get(), stack.getCount());
		}
		// 没有 PB 对应块的第三方物品保留原身份，不影响其他正常产物转换。
		return stack;
	}
}
