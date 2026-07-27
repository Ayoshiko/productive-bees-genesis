package com.ayoshiko.productivebeesgenesis.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 无尽创世蜜脾 — 仅为万象创世蜜脾提供材质
 * <br/>
 * 自定义 Item 子类，添加 tooltip 提示该物品无实际作用。
 */
public class ItemInfinityCreationComb extends Item {

	public ItemInfinityCreationComb(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context,
			List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
		tooltipComponents.add(Component.translatable("tooltip.productivebeesgenesis.infinitycreation_comb_item"));
	}
}
