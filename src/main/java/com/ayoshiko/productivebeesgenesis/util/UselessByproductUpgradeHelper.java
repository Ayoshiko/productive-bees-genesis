package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import cy.jdkdigital.productivebees.init.ModFluids;
import cy.jdkdigital.productivebees.init.ModTags;
import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shared predicates for the useless-byproduct upgrade and its optional compat. */
public final class UselessByproductUpgradeHelper {

	public static final ResourceLocation POLLEN_PUFF_ID =
			ResourceLocation.fromNamespaceAndPath("the_bumblezone", "pollen_puff");
	/** 只保存注册表物品的不可变集合；跨调用共享，标签重载和停服时释放。 */
	private static volatile Set<Item> waxItems;

	private UselessByproductUpgradeHelper() {
	}

	public static boolean hasUpgrade(BlockEntity blockEntity) {
		if (blockEntity instanceof IPbUpgradeProvider provider) {
			return provider.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0;
		}
		if (blockEntity instanceof IUpgradeableBlockEntity upgradeable) {
			return upgradeable.getUpgradeCount(ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get()) > 0;
		}
		return false;
	}

	public static boolean isPollenPuff(ItemStack stack) {
		return !stack.isEmpty() && POLLEN_PUFF_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
	}

	/** Returns whether the item belongs to the common wax tag used by centrifuge recipes. */
	public static boolean isWax(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Set<Item> items = waxItems;
		if (items == null) items = resolveWaxItems();
		return items.contains(stack.getItem());
	}

	private static synchronized Set<Item> resolveWaxItems() {
		if (waxItems == null) {
			Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
			BuiltInRegistries.ITEM.getTag(ModTags.Common.WAXES).ifPresent(
					tag -> tag.forEach(holder -> items.add(holder.value())));
			waxItems = Collections.unmodifiableSet(items);
		}
		return waxItems;
	}

	public static synchronized void invalidateCache() {
		waxItems = null;
	}

	public static boolean isHoney(FluidStack stack) {
		return !stack.isEmpty() && stack.getFluid() == ModFluids.HONEY.get();
	}
}
