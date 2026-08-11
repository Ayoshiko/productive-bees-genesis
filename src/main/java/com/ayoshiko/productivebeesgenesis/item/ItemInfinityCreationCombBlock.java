package com.ayoshiko.productivebeesgenesis.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
	 * 无尽创世蜜脾块 — 仅为万象创世蜜脾块提供材质
	 * <br/>
	 * 自定义 BlockItem 子类，添加 tooltip 提示该物品无实际作用。
	 */
public class ItemInfinityCreationCombBlock extends BlockItem {

	public ItemInfinityCreationCombBlock(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context,
			List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
		tooltipComponents.add(Component.translatable("tooltip.productivebeesgenesis.infinitycreation_comb_block"));
	}
}
