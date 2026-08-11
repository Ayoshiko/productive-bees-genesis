package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe;
import net.minecraft.core.HolderSet;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.function.Supplier;

/**
	 * 万象创世单配方处理器
	 * <br/>
	 * 从 {@link BeeRecipeReloader} 抽离，负责处理单个配方的修改/移除决策。
	 * <p>
	 * 处理 5 种 PB 配方类型：
	 * <ul>
	 *   <li>{@link BeeFishingRecipe} — 钓鱼配方：修改概率与群系，或禁用</li>
	 *   <li>{@link BeeBreedingRecipe} — 繁殖配方：修改亲代，或禁用</li>
	 *   <li>{@link BeeSpawningRecipe} — 蜂巢生成配方：修改蜂巢物品与群系，或禁用</li>
	 *   <li>{@link BeeConversionRecipe} — 蜜蜂转化配方：用其他物品转化获得万象创世，或禁用</li>
	 *   <li>{@link AdvancedBeehiveRecipe} — 蜜蜂产出配方：万象创世蜜脾产出参数，或禁用</li>
	 * </ul>
	 * 返回值约定：
	 * <ul>
	 *   <li>null — 移除该配方</li>
	 *   <li>原 holder — 保留该配方不变</li>
	 *   <li>新 holder — 替换为新配方</li>
	 * </ul>
	 */
public final class MyriadRecipeProcessor {

	private final RecipeIngredientFactory ingredientFactory;

	/**
	 * @param ingredientFactory 配方 Ingredient 工厂（持有 registryAccess）
	 */
	public MyriadRecipeProcessor(RecipeIngredientFactory ingredientFactory) {
		this.ingredientFactory = ingredientFactory;
	}

	/**
	 * 处理单个配方，返回 null 表示移除，返回原 holder 表示保留，返回新 holder 表示替换
	 */
	@SuppressWarnings("unchecked")
	public RecipeHolder<?> processRecipe(RecipeHolder<?> holder) {
		Recipe<?> recipe = holder.value();

		// 钓鱼配方：修改概率与群系，或禁用
		if (recipe instanceof BeeFishingRecipe fishing) {
			return processFishingRecipe(holder, fishing);
		}

		// 繁殖配方：修改亲代，或禁用
		if (recipe instanceof BeeBreedingRecipe breeding) {
			return processBreedingRecipe(holder, breeding);
		}

		// 蜂巢生成配方：修改蜂巢物品与群系，或禁用
		if (recipe instanceof BeeSpawningRecipe spawning) {
			return processSpawningRecipe(holder, spawning);
		}

		// 蜜蜂转化配方：用其他物品转化获得万象创世，或禁用
		if (recipe instanceof BeeConversionRecipe conversion) {
			return processConversionRecipe(holder, conversion);
		}

		// 蜜蜂产出配方：万象创世蜜脾产出参数，或禁用
		if (recipe instanceof AdvancedBeehiveRecipe produce) {
			return processProduceRecipe(holder, produce);
		}

		return holder;
	}

	/** 处理钓鱼配方 */
	private RecipeHolder<?> processFishingRecipe(RecipeHolder<?> holder, BeeFishingRecipe fishing) {
		if (!isMyriadcreations(fishing.output)) {
			return holder;
		}
		// 总开关禁用时移除所有万象创世配方
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return null;
		}
		if (!ModConfig.SERVER.fishingEnabled.get()) {
			return null;
		}
		HolderSet<Biome> biomes = ingredientFactory.createBiomeHolderSet(ModConfig.SERVER.fishingBiomes.get());
		float chance = ModConfig.SERVER.fishingChance.get().floatValue();
		BeeFishingRecipe newRecipe = new BeeFishingRecipe(fishing.output, biomes, chance);
		return new RecipeHolder<>(holder.id(), newRecipe);
	}

	/** 处理繁殖配方 */
	private RecipeHolder<?> processBreedingRecipe(RecipeHolder<?> holder, BeeBreedingRecipe breeding) {
		// 检查 offspring、parent1 或 parent2 是否涉及万象创世蜜蜂
		boolean involvesMyriadCreations = isMyriadcreations(breeding.offspring)
				|| isMyriadcreations(breeding.parent1)
				|| isMyriadcreations(breeding.parent2);
		if (!involvesMyriadCreations) {
			return holder;
		}
		// 总开关禁用时移除所有涉及万象创世的繁殖配方
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return null;
		}
		// 只有 offspring 是万象创世时才修改亲代配置
		if (!isMyriadcreations(breeding.offspring)) {
			return holder;
		}
		if (!ModConfig.SERVER.breedingEnabled.get()) {
			return null;
		}
		Supplier<BeeIngredient> parent1 = RecipeIngredientFactory.getBeeIngredient(ModConfig.SERVER.breedingParent1.get());
		Supplier<BeeIngredient> parent2 = RecipeIngredientFactory.getBeeIngredient(ModConfig.SERVER.breedingParent2.get());
		BeeBreedingRecipe newRecipe = new BeeBreedingRecipe(parent1, parent2, breeding.offspring, breeding.parentDeathChance);
		return new RecipeHolder<>(holder.id(), newRecipe);
	}

	/** 处理蜂巢生成配方 */
	private RecipeHolder<?> processSpawningRecipe(RecipeHolder<?> holder, BeeSpawningRecipe spawning) {
		if (!containsMyriadcreations(spawning.output)) {
			return holder;
		}
		// 总开关禁用时移除所有万象创世配方
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return null;
		}
		if (!ModConfig.SERVER.spawningEnabled.get()) {
			return null;
		}
		Ingredient ingredient = RecipeIngredientFactory.createIngredient(ModConfig.SERVER.spawningNest.get());
		HolderSet<Biome> biomes = ingredientFactory.createBiomeHolderSetFromString(ModConfig.SERVER.spawningBiomes.get());
		BeeSpawningRecipe newRecipe = new BeeSpawningRecipe(ingredient, spawning.spawnItem, spawning.output, biomes);
		return new RecipeHolder<>(holder.id(), newRecipe);
	}

	/** 处理蜜蜂转化配方 */
	private RecipeHolder<?> processConversionRecipe(RecipeHolder<?> holder, BeeConversionRecipe conversion) {
		if (!isMyriadcreations(conversion.result)) {
			return holder;
		}
		// 总开关禁用时移除所有万象创世配方
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return null;
		}
		if (!ModConfig.SERVER.conversionEnabled.get()) {
			return null;
		}
		Supplier<BeeIngredient> source = RecipeIngredientFactory.getBeeIngredient(ModConfig.SERVER.conversionSource.get());
		Supplier<BeeIngredient> result = RecipeIngredientFactory.getBeeIngredient(ModConfig.SERVER.conversionResult.get());
		Ingredient item = RecipeIngredientFactory.createIngredient(ModConfig.SERVER.conversionItem.get());
		float chance = ModConfig.SERVER.conversionChance.get().floatValue();
		BeeConversionRecipe newRecipe = new BeeConversionRecipe(source, result, item, chance);
		return new RecipeHolder<>(holder.id(), newRecipe);
	}

	/** 处理蜜蜂产出配方 */
	private RecipeHolder<?> processProduceRecipe(RecipeHolder<?> holder, AdvancedBeehiveRecipe produce) {
		if (!isMyriadcreations(produce.ingredient)) {
			return holder;
		}
		// 总开关禁用时移除所有万象创世配方
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return null;
		}
		if (!ModConfig.SERVER.produceEnabled.get()) {
			return null;
		}
		List<TagOutputRecipe.ChancedOutput> outputs = RecipeIngredientFactory.createProduceOutputs();
		AdvancedBeehiveRecipe newRecipe = new AdvancedBeehiveRecipe(produce.ingredient, outputs);
		return new RecipeHolder<>(holder.id(), newRecipe);
	}

	/**
	 * 判断 BeeIngredient 供应商是否对应万象创世蜜蜂
	 */
	private static boolean isMyriadcreations(Supplier<BeeIngredient> supplier) {
		try {
			if (supplier == null) {
				return false;
			}
			// 缓存 supplier.get() 结果，避免重复求值（supplier 可能涉及懒加载）
			BeeIngredient ing = supplier.get();
			return ing != null && PBConstants.MYRIADCREATIONS_TYPE.equals(ing.getBeeType());
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.debug("解析蜜蜂类型供应商失败，返回 false", e);
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
}
