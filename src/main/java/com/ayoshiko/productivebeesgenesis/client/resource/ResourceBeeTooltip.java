package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.BeeNbtHelper;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSpawnEggHelper;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.BeeDesignerClient;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.common.item.BeeCage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 物品与蜂槽共用当前配方目录的支持说明，不改变 PB 普通产物。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class ResourceBeeTooltip {

	private ResourceBeeTooltip() {
	}

	@SubscribeEvent
	public static void onItemTooltip(ItemTooltipEvent event) {
		ResourceLocation bee = beeType(event.getItemStack());
		event.getToolTip().addAll(lines(bee));
		if (bee != null && BeeDesignerClient.retired(bee.toString())) {
			event.getToolTip().add(Component.translatable("designer.retired_item").withStyle(ChatFormatting.GOLD));
		}
	}

	public static List<Component> lines(@Nullable ResourceLocation bee) {
		if (bee == null || !ResourceClientCatalog.hasExtendedOutputs(bee)) {
			return List.of();
		}
		return List.of(
				Component.translatable("resource.productivebeesgenesis.bee_support.title").withStyle(ChatFormatting.AQUA),
				Component.translatable("resource.productivebeesgenesis.bee_support.pb_unsupported").withStyle(ChatFormatting.GOLD),
				Component.translatable("resource.productivebeesgenesis.bee_support.machines").withStyle(ChatFormatting.GRAY),
				Component.translatable("resource.productivebeesgenesis.bee_support.feedback").withStyle(ChatFormatting.GRAY));
	}

	@Nullable
	private static ResourceLocation beeType(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		ResourceLocation bee = stack.get(PbDataComponents.beeType());
		if (bee != null) {
			return bee;
		}
		if (stack.getItem() instanceof BeeCage) {
			var data = stack.get(DataComponents.CUSTOM_DATA);
			return data == null ? null : BeeNbtHelper.resolveBeeTypeKey(data.copyTag());
		}
		return BeeSpawnEggHelper.getBeeType(stack);
	}
}
