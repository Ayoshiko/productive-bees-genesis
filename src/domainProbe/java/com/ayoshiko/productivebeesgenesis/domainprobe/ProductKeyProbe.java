package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class ProductKeyProbe {
	static void verify(HolderLookup.Provider registries) {
		var stack = new ItemStack(Items.GOLD_INGOT, 42);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Named gold"));
		var data = new CompoundTag(); data.putIntArray("array", new int[] {1, 2});
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		stack.remove(DataComponents.RARITY);
		var frozen = ProductKeyCodec.item(stack, registries);
		var restored = ProductKeyCodec.item(frozen, 42, registries);
		require(ItemStack.isSameItemSameComponents(stack, restored), "Item full components changed");
		require(frozen.equals(ProductKeyCodec.item(stack.copyWithCount(1), registries)), "Count became identity");
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Mutated gold"));
		require(!frozen.equals(ProductKeyCodec.item(stack, registries)), "Source mutation reached frozen key");
		restored.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		require(!frozen.equals(ProductKeyCodec.item(restored, registries)), "Restored mutation reached frozen key");

		var comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		comb.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
		var iron = ProductKeyCodec.item(comb, registries);
		comb.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:gold"));
		require(!iron.equals(ProductKeyCodec.item(comb, registries)), "Bee type lost");
		require(iron.equals(ProductKeyCodec.item(ProductKeyCodec.item(iron, 1, registries), registries)), "PB key round trip failed");

		var water = new FluidStack(Fluids.WATER, 1000);
		water.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		var fluid = ProductKeyCodec.fluid(water, registries);
		require(FluidStack.isSameFluidSameComponents(water, ProductKeyCodec.fluid(fluid, 12, registries)), "Fluid components lost");
		require(fluid.equals(ProductKeyCodec.fluid(water.copyWithAmount(1), registries)), "Fluid amount became identity");

		DataComponentType<Integer> transientType = DataComponentType.<Integer>builder().networkSynchronized(ByteBufCodecs.INT).build();
		stack.set(transientType, 7);
		try {
			ProductKeyCodec.item(stack, registries);
			throw new IllegalStateException("Transient component was silently stripped");
		} catch (IllegalArgumentException expected) {
			require(expected.getMessage().startsWith("Transient product component"), "Unexpected component error");
		}
	}
}
