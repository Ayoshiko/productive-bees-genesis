package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import mekanism.common.recipe.upgrade.MekanismShapedRecipe;
import mekanism.common.registries.MekanismBlocks;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 配方数据生成器
 * <br/>
 * 为MEK离心机/蜂箱工厂方块生成合成配方，使用Mekanism的mekanism:mek_data类型配方
 * （合成时保留机器数据，如能量、物品等）。
 * <p>
 * 配方模式遵循各模组原版：
 * - Mekanism基础：基础离心机/蜂箱(RBR/ICI/RBR)，4级工厂TIER_PATTERN（ACA/IPI/ACA）
 * - EM 5等级：TIER_PATTERN，使用EM的合金/电路/锭标签
 * - ME 4等级：TIER_PATTERN，使用ME的合金/电路/锭标签（INFINITE特殊模式）
 * - EME 4等级：EMEXTRA_PATTERN（ACT/PXQ/TCA），组合ME+EM材料
 * <p>
 * 所有配方使用ModLoadedCondition条件，仅在对应模组加载时生成。
 * <p>
 * 配方按内容拆分到独立辅助类：
 * <ul>
 *   <li>{@link ModRecipesCentrifuge} — 离心机相关配方</li>
 *   <li>{@link ModRecipesApiary} — 蜂箱相关配方</li>
 * </ul>
 * 本类保留共享的 MekDataBuilder 构建器与通用工具方法（addTierRecipe、addEMETierRecipe、rl）。
 */
public final class ModRecipes extends RecipeProvider {

	public ModRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected void buildRecipes(RecipeOutput output) {
		ModRecipesCentrifuge.addRecipes(output);
		ModRecipesApiary.addRecipes(output);
	}

	// ======================== MekData配方构建器 ========================

	/**
	 * 构建mekanism:mek_data类型的有序配方
	 * <br/>
	 * MekanismShapedRecipe是ShapedRecipe的包装器，在合成时保留机器数据（能量、物品等）。
	 * 由于Mekanism的MekDataShapedRecipeBuilder在datagen模块中（不在主jar中），
	 * 这里手动构建ShapedRecipe并包装为MekanismShapedRecipe。
	 */
	static final class MekDataBuilder {
		private final ItemStack result;
		private final List<String> pattern = new ArrayList<>();
		private final Map<Character, Ingredient> key = new LinkedHashMap<>();
		private final Map<String, Criterion<?>> criteria = new LinkedHashMap<>();
		private final List<ICondition> conditions = new ArrayList<>();

		MekDataBuilder(ItemLike result, int count) {
			this.result = new ItemStack(result, count);
		}

		MekDataBuilder pattern(String... rows) {
			this.pattern.clear();
			for (String row : rows) {
				this.pattern.add(row);
			}
			return this;
		}

		MekDataBuilder key(char symbol, TagKey<Item> tag) {
			key.put(symbol, Ingredient.of(tag));
			return this;
		}

		MekDataBuilder key(char symbol, ItemLike item) {
			key.put(symbol, Ingredient.of(item));
			return this;
		}

		MekDataBuilder key(char symbol, Ingredient ingredient) {
			key.put(symbol, ingredient);
			return this;
		}

		MekDataBuilder addCondition(ICondition condition) {
			conditions.add(condition);
			return this;
		}

		void build(RecipeOutput output, ResourceLocation id) {
			ShapedRecipe shapedRecipe = new ShapedRecipe(
					"", CraftingBookCategory.EQUIPMENT,
					ShapedRecipePattern.of(key, pattern),
					result, true);
			// 包装为MekanismShapedRecipe，使用mekanism:mek_data序列化器
			Recipe<?> mekRecipe = new MekanismShapedRecipe(shapedRecipe);

			net.minecraft.advancements.AdvancementHolder advancementHolder = null;
			if (!criteria.isEmpty()) {
				Advancement.Builder builder = output.advancement()
						.addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(id))
						.rewards(AdvancementRewards.Builder.recipe(id))
						.requirements(AdvancementRequirements.Strategy.OR);
				criteria.forEach(builder::addCriterion);
				advancementHolder = builder.build(id.withPrefix("recipes/"));
			}
			output.accept(id, mekRecipe, advancementHolder, conditions.toArray(new ICondition[0]));
		}
	}

	// ======================== 通用配方方法 ========================

	/**
	 * 添加标准TIER_PATTERN配方（ACA/IPI/ACA）
	 * <br/>
	 * A=合金, C=电路, I=锭/材料, P=上一级工厂
	 * 适用于EM和ME等级
	 */
	static void addTierRecipe(RecipeOutput output, String path,
			DeferredBlock<?> previousFactory,
			DeferredBlock<?> resultFactory,
			TagKey<Item> ingotTag,
			TagKey<Item> alloyTag, TagKey<Item> circuitTag,
			ICondition condition) {
		if (previousFactory == null || resultFactory == null) {
			return;
		}
		new MekDataBuilder(resultFactory, 1)
				.pattern("ACA", "IPI", "ACA")
				.key('A', alloyTag)
				.key('C', circuitTag)
				.key('I', ingotTag)
				.key('P', previousFactory)
				.addCondition(condition)
				.build(output, rl(path));
	}

	/**
	 * 添加标准TIER_PATTERN配方（ItemLike版本，用于nether_star等非Tag材料）
	 */
	static void addTierRecipe(RecipeOutput output, String path,
			DeferredBlock<?> previousFactory,
			DeferredBlock<?> resultFactory,
			ItemLike ingotItem,
			TagKey<Item> alloyTag, TagKey<Item> circuitTag,
			ICondition condition) {
		if (previousFactory == null || resultFactory == null) {
			return;
		}
		new MekDataBuilder(resultFactory, 1)
				.pattern("ACA", "IPI", "ACA")
				.key('A', alloyTag)
				.key('C', circuitTag)
				.key('I', ingotItem)
				.key('P', previousFactory)
				.addCondition(condition)
				.build(output, rl(path));
	}

	/**
	 * 添加EME组合配方（ACT/PXQ/TCA）
	 * <br/>
	 * A=ME合金, C=EME电路, T=EM合金, P=ME上一级工厂, Q=EM上一级工厂, X=Steel Casing
	 */
	static void addEMETierRecipe(RecipeOutput output, String path,
			DeferredBlock<?> mePreviousFactory,
			DeferredBlock<?> emPreviousFactory,
			DeferredBlock<?> resultFactory,
			TagKey<Item> meAlloyTag, TagKey<Item> emAlloyTag, TagKey<Item> emeCircuitTag,
			ICondition condition) {
		if (mePreviousFactory == null || emPreviousFactory == null || resultFactory == null) {
			return;
		}
		new MekDataBuilder(resultFactory, 1)
				.pattern("ACT", "PXQ", "TCA")
				.key('A', meAlloyTag)
				.key('C', emeCircuitTag)
				.key('T', emAlloyTag)
				.key('P', mePreviousFactory)
				.key('Q', emPreviousFactory)
				.key('X', MekanismBlocks.STEEL_CASING)
				.addCondition(condition)
				.build(output, rl(path));
	}

	/** 创建模组命名空间的ResourceLocation */
	static ResourceLocation rl(String path) {
		return ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, path);
	}
}
