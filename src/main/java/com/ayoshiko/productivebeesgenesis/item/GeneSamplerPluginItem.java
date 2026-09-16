package com.ayoshiko.productivebeesgenesis.item;

import cy.jdkdigital.productivelib.common.item.UpgradeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 基因采样器功能插件的共用物品实现。 */
public final class GeneSamplerPluginItem extends UpgradeItem {

	private final String summaryKey;
	private final String limitKey;

	public GeneSamplerPluginItem(Item.Properties properties, String summaryKey, String limitKey) {
		super(properties);
		this.summaryKey = summaryKey;
		this.limitKey = limitKey;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
			List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable(summaryKey).withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(limitKey).withStyle(ChatFormatting.DARK_GRAY));
	}
}
