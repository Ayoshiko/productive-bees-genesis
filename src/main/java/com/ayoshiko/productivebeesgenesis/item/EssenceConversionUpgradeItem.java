package com.ayoshiko.productivebeesgenesis.item;

import cy.jdkdigital.productivelib.common.item.UpgradeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 将具有明确纯同物压缩配方的低级资源产物按配方比例转化。
 */
public final class EssenceConversionUpgradeItem extends UpgradeItem {

	/** 创建精华转化升级物品。 */
	public EssenceConversionUpgradeItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
			List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.essence_conversion_upgrade.description.summary")
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.essence_conversion_upgrade.description.limit")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
