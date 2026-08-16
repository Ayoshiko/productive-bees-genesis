package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModFluids;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a Productive Bees bee to the mod namespace of its final resource output.
 * <p>
 * Configurable bees themselves normally use the {@code productivebees} namespace, as do
 * their configurable combs. The meaningful ownership signal is therefore the centrifuge
 * result after tags have selected the item or fluid that will actually be produced.
 */
public final class BeeProductModResolver {

	private static final TagKey<Item> WAXES_TAG = TagKey.create(
			Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "waxes"));
	private static final Map<ResourceLocation, BeeProductModProfile> PRODUCT_MOD_CACHE = new ConcurrentHashMap<>();

	private BeeProductModResolver() {
	}

	/**
	 * Returns the primary final-product namespace for a bee.
	 * <p>
	 * The first non-wax item output wins; a non-honey fluid is used for fluid-only bees.
	 * Recipes that only produce shared Productive Bees byproducts fall back to
	 * {@code productivebees}. Recipe order is preserved so custom multi-output recipes have
	 * a deterministic primary product.
	 */
	@Nonnull
	public static String resolve(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		return resolveProfile(level, beeType).primaryModId();
	}

	/** Returns the primary grouping mod and all final-product mods for search. */
	@Nonnull
	public static BeeProductModProfile resolveProfile(
			@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		return PRODUCT_MOD_CACHE.computeIfAbsent(beeType, key -> resolveUncached(level, key));
	}

	@Nonnull
	private static BeeProductModProfile resolveUncached(Level level, ResourceLocation beeType) {
		try {
			RecipeHolder<CentrifugeRecipe> holder = findCentrifugeRecipe(level, beeType);
			if (holder == null) {
				return BeeProductModProfile.fallback(PBConstants.PRODUCTIVE_BEES_MOD_ID);
			}

			CentrifugeRecipe recipe = holder.value();
			List<String> itemNamespaces = resolveItemNamespaces(recipe);
			String fluidNamespace = resolveFluidNamespace(recipe);
			return BeeProductModProfile.create(
					itemNamespaces, fluidNamespace, PBConstants.PRODUCTIVE_BEES_MOD_ID);
		} catch (RuntimeException e) {
			DevLog.warn("bee_product_mod", "解析蜜蜂最终产物模组失败: {} - {}", beeType, e.toString());
			return BeeProductModProfile.fallback(PBConstants.PRODUCTIVE_BEES_MOD_ID);
		}
	}

	@Nullable
	private static RecipeHolder<CentrifugeRecipe> findCentrifugeRecipe(Level level, ResourceLocation beeType) {
		RecipeHolder<CentrifugeRecipe> indexed = CentrifugeRecipeIndex.get(beeType);
		if (indexed != null) {
			return indexed;
		}

		// The index is normally rebuilt on TagsUpdatedEvent. This fallback also covers a GUI
		// opened during initial synchronization and recipes added by unusual reload pipelines.
		ItemStack comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		comb.set(ModDataComponents.BEE_TYPE.get(), beeType);
		for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
				.getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get())) {
			if (holder.value().ingredient.test(comb)) {
				return holder;
			}
		}
		return null;
	}

	private static List<String> resolveItemNamespaces(CentrifugeRecipe recipe) {
		List<String> namespaces = new ArrayList<>();
		for (ItemStack stack : recipe.getRecipeOutputs().keySet()) {
			if (stack.isEmpty() || stack.is(WAXES_TAG)) {
				continue;
			}
			ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
			if (itemId != null && !namespaces.contains(itemId.getNamespace())) {
				namespaces.add(itemId.getNamespace());
			}
		}
		return namespaces;
	}

	@Nullable
	private static String resolveFluidNamespace(CentrifugeRecipe recipe) {
		FluidStack output = recipe.getFluidOutputs();
		if (output.isEmpty()) {
			output = firstFluid(recipe.fluidOutput);
		}
		if (output.isEmpty() || output.getFluid() == ModFluids.HONEY.get()) {
			return null;
		}
		ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(output.getFluid());
		return fluidId != null ? fluidId.getNamespace() : null;
	}

	private static FluidStack firstFluid(@Nullable SizedFluidIngredient ingredient) {
		if (ingredient == null) {
			return FluidStack.EMPTY;
		}
		for (FluidStack stack : ingredient.getFluids()) {
			return stack.copyWithAmount(ingredient.amount());
		}
		return FluidStack.EMPTY;
	}

	/** Clears product ownership after recipes or tags change. */
	public static void invalidateCache() {
		PRODUCT_MOD_CACHE.clear();
	}
}
