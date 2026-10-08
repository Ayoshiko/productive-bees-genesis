package com.ayoshiko.productivebeesgenesis.domainprobe;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack;
import net.neoforged.neoforge.registries.RegisterEvent;

/** 隔离测试世界的单罐物品；使用 NeoForge 原生组件和能力实现，绝不进入发布包。 */
@EventBusSubscriber(modid = "productivebeesgenesis", bus = EventBusSubscriber.Bus.MOD)
public final class FluidContainerProbe {
	private static DataComponentType<SimpleFluidContent> content;
	@SubscribeEvent public static void register(RegisterEvent event) {
		if (!MeBridgeProbe.fluid()) return;
		event.register(Registries.DATA_COMPONENT_TYPE, helper -> {
			content = DataComponentType.<SimpleFluidContent>builder().persistent(SimpleFluidContent.CODEC).networkSynchronized(SimpleFluidContent.STREAM_CODEC).build();
			helper.register(ResourceLocation.parse("productivebeesgenesis:probe_fluid"), content);
		});
	}
	@SubscribeEvent public static void capabilities(RegisterCapabilitiesEvent event) {
		if (MeBridgeProbe.fluid()) event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ignored) -> new FluidHandlerItemStack(() -> content, stack, 1000), Items.PAPER);
	}
	private FluidContainerProbe() { }
}
