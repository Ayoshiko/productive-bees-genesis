package com.ayoshiko.productivebeesgenesis.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.biome.Biome;

/**
 * 蜜蜂配方重载器
 * <br/>
 * 在数据包重载（/reload、服务器启动、数据包变更）后根据 {@link ModConfig} 动态修改
 * ProductiveBees 的 bee_fishing / bee_breeding / bee_spawning 配方，
 * 实现万象创世蜜蜂获得方式的运行时配置。
 * <p>
 * 原理：通过 {@link PreparableReloadListener} 在 RecipeManager 完成加载后介入，
 * 收集全部配方并替换其中万象创世相关的条目，最后调用 {@link RecipeManager#replaceRecipes}
 * 全量替换。线程安全：reload 的 apply 阶段在 gameExecutor（主线程）执行，无并发问题。
 */
public final class BeeRecipeReloader implements PreparableReloadListener {

	/** 万象创世蜜蜂类型常量 */
	private static final String MYRIADCREATIONS_TYPE = "productivebees:myriadcreations";

	private final RecipeManager recipeManager;
	private final HolderLookup.Provider registryAccess;

	/**
	 * @param recipeManager 配方管理器（来自 ReloadableServerResources）
	 * @param registryAccess 注册表访问（来自 AddReloadListenerEvent.getRegistryAccess()）
	 */
	public BeeRecipeReloader(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
		this.recipeManager = recipeManager;
		this.registryAccess = registryAccess;
	}

	@Override
	public CompletableFuture<Void> reload(
			PreparationBarrier barrier,
			ResourceManager resourceManager,
			ProfilerFiller preparationsProfiler,
			ProfilerFiller reloadProfiler,
			Executor backgroundExecutor,
			Executor gameExecutor) {
		// 准备阶段无操作，仅在 apply 阶段（主线程）执行配方覆盖
		return barrier.wait(CompletableFuture.completedFuture(null))
				.thenRunAsync(this::overrideRecipes, gameExecutor);
	}

	/**
	 * 收集全部配方，处理万象创世相关条目，若有修改则全量替换
	 */
	private void overrideRecipes() {
		try {
			List<RecipeHolder<?>> allRecipes = new ArrayList<>(recipeManager.getRecipes());
			boolean modified = false;

			for (int i = 0; i < allRecipes.size(); i++) {
				RecipeHolder<?> holder = allRecipes.get(i);
				RecipeHolder<?> processed = processRecipe(holder);
				if (processed == null) {
					allRecipes.remove(i);
					i--;
					modified = true;
				} else if (processed != holder) {
					allRecipes.set(i, processed);
					modified = true;
				}
			}

			if (modified) {
				recipeManager.replaceRecipes(allRecipes);
				clearBeeFishingCaches();
				ProductiveBeesGenesis.LOGGER.info("万象创世蜜蜂配方已根据配置重载");
			}
		} catch (Exception e) {
			// 任何异常都不应导致整体崩溃
			ProductiveBeesGenesis.LOGGER.error("重载万象创世蜜蜂配方时发生错误", e);
		}
	}

	/**
	 * 处理单个配方，返回 null 表示移除，返回原 holder 表示保留，返回新 holder 表示替换
	 */
	@SuppressWarnings("unchecked")
	private RecipeHolder<?> processRecipe(RecipeHolder<?> holder) {
		Recipe<?> recipe = holder.value();

		// 钓鱼配方：修改概率与群系，或禁用
		if (recipe instanceof BeeFishingRecipe fishing) {
			if (!isMyriadcreations(fishing.output)) {
				return holder;
			}
			if (!ModConfig.COMMON.fishingEnabled.get()) {
				return null;
			}
			HolderSet<Biome> biomes = createBiomeHolderSet(ModConfig.COMMON.fishingBiomes.get());
			float chance = ModConfig.COMMON.fishingChance.get().floatValue();
			BeeFishingRecipe newRecipe = new BeeFishingRecipe(fishing.output, biomes, chance);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		// 繁殖配方：修改亲代，或禁用
		if (recipe instanceof BeeBreedingRecipe breeding) {
			if (!isMyriadcreations(breeding.offspring)) {
				return holder;
			}
			if (!ModConfig.COMMON.breedingEnabled.get()) {
				return null;
			}
			Supplier<BeeIngredient> parent1 = BeeIngredientFactory.getIngredient(ModConfig.COMMON.breedingParent1.get());
			Supplier<BeeIngredient> parent2 = BeeIngredientFactory.getIngredient(ModConfig.COMMON.breedingParent2.get());
			BeeBreedingRecipe newRecipe = new BeeBreedingRecipe(parent1, parent2, breeding.offspring, breeding.parentDeathChance);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		// 蜂巢生成配方：修改蜂巢物品与群系，或禁用
		if (recipe instanceof BeeSpawningRecipe spawning) {
			if (!containsMyriadcreations(spawning.output)) {
				return holder;
			}
			if (!ModConfig.COMMON.spawningEnabled.get()) {
				return null;
			}
			Ingredient ingredient = createIngredient(ModConfig.COMMON.spawningNest.get());
			HolderSet<Biome> biomes = createBiomeHolderSetFromString(ModConfig.COMMON.spawningBiomes.get());
			BeeSpawningRecipe newRecipe = new BeeSpawningRecipe(ingredient, spawning.spawnItem, spawning.output, biomes);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		return holder;
	}

	/**
	 * 判断 BeeIngredient 供应商是否对应万象创世蜜蜂
	 */
	private static boolean isMyriadcreations(Supplier<BeeIngredient> supplier) {
		try {
			return supplier != null && supplier.get() != null
					&& MYRIADCREATIONS_TYPE.equals(supplier.get().getBeeType().toString());
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 判断 BeeIngredient 供应商列表中是否包含万象创世蜜蜂
	 */
	private static boolean containsMyriadcreations(List<Supplier<BeeIngredient>> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return false;
		}
		for (Supplier<BeeIngredient> supplier : outputs) {
			if (isMyriadcreations(supplier)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 根据群系 ID 列表构建 HolderSet（每个元素为单个群系 ID）
	 */
	private HolderSet<Biome> createBiomeHolderSet(List<? extends String> biomeIds) {
		if (biomeIds == null || biomeIds.isEmpty()) {
			return HolderSet.empty();
		}
		List<Holder<Biome>> holders = new ArrayList<>();
		for (String id : biomeIds) {
			Holder<Biome> holder = resolveBiome(id);
			if (holder != null) {
				holders.add(holder);
			} else {
				ProductiveBeesGenesis.LOGGER.warn("钓鱼群系 '{}' 未找到，已跳过", id);
			}
		}
		return holders.isEmpty() ? HolderSet.empty() : HolderSet.direct(holders);
	}

	/**
	 * 根据单个群系规格构建 HolderSet（支持标签 "#xxx" 或群系 ID "xxx"）
	 */
	private HolderSet<Biome> createBiomeHolderSetFromString(String biomeSpec) {
		if (biomeSpec == null || biomeSpec.isBlank()) {
			return HolderSet.empty();
		}
		if (biomeSpec.startsWith("#")) {
			// 标签规格：解析为 TagKey 并从注册表获取对应的 Named HolderSet
			try {
				ResourceLocation tagLoc = ResourceLocation.parse(biomeSpec.substring(1));
				TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagLoc);
				Optional<HolderSet.Named<Biome>> tag = registryAccess.lookup(Registries.BIOME)
						.flatMap(reg -> reg.get(tagKey));
				return tag.<HolderSet<Biome>>map(named -> named).orElse(HolderSet.empty());
			} catch (Exception e) {
				ProductiveBeesGenesis.LOGGER.warn("解析群系标签 '{}' 失败", biomeSpec, e);
				return HolderSet.empty();
			}
		}
		// 单个群系 ID
		Holder<Biome> holder = resolveBiome(biomeSpec);
		if (holder == null) {
			ProductiveBeesGenesis.LOGGER.warn("生成群系 '{}' 未找到", biomeSpec);
			return HolderSet.empty();
		}
		return HolderSet.direct(List.of(holder));
	}

	/**
	 * 通过群系 ID 解析为 Holder<Biome>
	 */
	private Holder<Biome> resolveBiome(String biomeId) {
		try {
			ResourceLocation rl = ResourceLocation.parse(biomeId);
			ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, rl);
			return registryAccess.lookup(Registries.BIOME)
					.flatMap(reg -> reg.get(key))
					.map(h -> (Holder<Biome>) h)
					.orElse(null);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析群系 '{}' 失败", biomeId, e);
			return null;
		}
	}

	/**
	 * 根据物品 ID 创建 Ingredient，找不到则返回 EMPTY
	 */
	private Ingredient createIngredient(String itemId) {
		try {
			ResourceLocation rl = ResourceLocation.parse(itemId);
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
			return item.<Ingredient>map(i -> Ingredient.of(i)).orElse(Ingredient.EMPTY);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析蜂巢物品 '{}' 失败，使用空 Ingredient", itemId, e);
			return Ingredient.EMPTY;
		}
	}

	/**
	 * 通过反射清理 BeeFishingRecipe 的静态缓存
	 * <br/>
	 * PB 在 BeeFishingRecipe 中维护了两个静态缓存（cachedBiomes、cachedRecipes），
	 * 替换配方后旧缓存会引用过期的 Recipe 实例，必须清理避免数据不一致。
	 */
	@SuppressWarnings("unchecked")
	private static void clearBeeFishingCaches() {
		try {
			Field cachedBiomesField = BeeFishingRecipe.class.getDeclaredField("cachedBiomes");
			cachedBiomesField.setAccessible(true);
			Map<?, ?> cachedBiomes = (Map<?, ?>) cachedBiomesField.get(null);
			if (cachedBiomes != null) {
				cachedBiomes.clear();
			}

			Field cachedRecipesField = BeeFishingRecipe.class.getDeclaredField("cachedRecipes");
			cachedRecipesField.setAccessible(true);
			Map<?, ?> cachedRecipes = (Map<?, ?>) cachedRecipesField.get(null);
			if (cachedRecipes != null) {
				cachedRecipes.clear();
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("清理 BeeFishingRecipe 静态缓存失败", e);
		}
	}
}
