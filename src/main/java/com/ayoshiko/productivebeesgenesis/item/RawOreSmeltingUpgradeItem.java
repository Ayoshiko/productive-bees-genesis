package com.ayoshiko.productivebeesgenesis.item;

import cy.jdkdigital.productivelib.common.item.UpgradeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 将离心产出的粗矿按熔炼配方直接转为对应锭的功能升级。 */
public final class RawOreSmeltingUpgradeItem extends UpgradeItem {

	/** 创建粗矿熔炼升级物品。 */
	public RawOreSmeltingUpgradeItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
			List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.raw_ore_smelting_upgrade.description.machine")
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.raw_ore_smelting_upgrade.description.rule")
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(
				"item.productivebeesgenesis.raw_ore_smelting_upgrade.description.limit")
				.withStyle(ChatFormatting.DARK_GRAY));
		super.appendHoverText(stack, context, tooltip, flag);
	}
}
