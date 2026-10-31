package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.init.ModFluids;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves a Productive Bees bee to the mod namespace of its final resource output.
 * <p>
 * Configurable bees themselves normally use the {@code productivebees} namespace, as do
 * their configurable combs. The meaningful ownership signal is therefore the centrifuge
 * result after tags have selected the item or fluid that will actually be produced.
 */
public final class BeeProductModResolver {

	private static final TagKey<Item> WAXES_TAG = TagKey.create(
			Registries.ITEM, new ResourceLocation("c", "waxes"));

	/**
	 * 产物归属缓存上限
	 * <p>
	 * 蜜蜂类型数量本身由注册表/配方数据界定（通常数百），此上限只作为内存防线：
	 * 防止自定义数据包生成大量一次性 bee_type 时缓存无界增长。
	 */
	private static final int MAX_CACHE_ENTRIES = 512;

	/**
	 * 产物归属缓存 — 访问顺序 LRU，超限淘汰最久未使用条目
	 * <p>
	 * 查询在 GUI 排序/搜索路径调用（非每 tick 热路径），全表锁开销可接受。
	 * 真正昂贵的 {@link #resolveUncached} 在锁外执行，并发争用时最多重复计算一次
	 * （结果幂等），不会长时间持锁做配方查找。
	 */
	private static final Map<ResourceLocation, BeeProductModProfile> PRODUCT_MOD_CACHE =
			BoundedLruMap.synchronizedAccessOrdered(MAX_CACHE_ENTRIES);

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
		// 先查缓存（持锁仅为哈希查找），未命中时在锁外解析，避免长时间持锁做配方查询。
		BeeProductModProfile cached = PRODUCT_MOD_CACHE.get(beeType);
		if (cached != null) return cached;
		BeeProductModProfile resolved = resolveUncached(level, beeType);
		// 并发争用时可能重复解析，结果幂等；putIfAbsent 保证同一 beeType 只保留一份实例。
		BeeProductModProfile existing = PRODUCT_MOD_CACHE.putIfAbsent(beeType, resolved);
		return existing != null ? existing : resolved;
	}

	@Nonnull
	private static BeeProductModProfile resolveUncached(Level level, ResourceLocation beeType) {
		try {
			CentrifugeRecipe holder = findCentrifugeRecipe(level, beeType);
			if (holder == null) {
				return BeeProductModProfile.fallback(PBConstants.PRODUCTIVE_BEES_MOD_ID);
			}

			CentrifugeRecipe recipe = holder;
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
	private static CentrifugeRecipe findCentrifugeRecipe(Level level, ResourceLocation beeType) {
		CentrifugeRecipe indexed = CentrifugeRecipeIndex.get(beeType);
		if (indexed != null) {
			return indexed;
		}

		// The index is normally rebuilt on TagsUpdatedEvent. This fallback also covers a GUI
		// opened during initial synchronization and recipes added by unusual reload pipelines.
		ItemStack comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		PbDataComponents.setBeeType(comb, beeType);
		for (CentrifugeRecipe holder : level.getRecipeManager()
				.getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get())) {
			if (holder.ingredient.test(comb)) {
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
		FluidStack output = PbRecipeOutputs.fluid(recipe);
		if (output.isEmpty()) {
			output = firstFluid(recipe.fluidOutput);
		}
		if (output.isEmpty() || output.getFluid() == ModFluids.HONEY.get()) {
			return null;
		}
		ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(output.getFluid());
		return fluidId != null ? fluidId.getNamespace() : null;
	}

	private static FluidStack firstFluid(@Nullable com.mojang.datafixers.util.Pair<String, Integer> ingredient) {
		return PbRecipeOutputs.fluid(ingredient);
	}

	/** Clears product ownership after recipes or tags change. */
	public static void invalidateCache() {
		PRODUCT_MOD_CACHE.clear();
	}
}
