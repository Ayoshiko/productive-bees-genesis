package com.ayoshiko.productivebeesgenesis.item;

import cy.jdkdigital.productivelib.common.item.UpgradeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Functional upgrade that discards honey and optional pollen-puff byproducts.
 */
public final class UselessByproductUpgradeItem extends UpgradeItem {

	public UselessByproductUpgradeItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
			List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.byproduct_destruction_upgrade.description.hive")
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.byproduct_destruction_upgrade.description.centrifuge")
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.byproduct_destruction_upgrade.description.limit")
				.withStyle(ChatFormatting.DARK_GRAY));
		super.appendHoverText(stack, context, tooltip, flag);
	}
}
