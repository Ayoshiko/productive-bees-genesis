package com.ayoshiko.productivebeesgenesis.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.biome.Biome;

/**
 * 配方 Ingredient 与 Biome 解析工具
 * <br/>
 * 从 {@link BeeRecipeReloader} 抽离，负责根据配置解析：
 * <ul>
 *   <li>群系 ID / 标签 → {@link HolderSet}（钓鱼、生成配方使用）</li>
 *   <li>物品 ID → {@link Ingredient}（蜂巢、转化物品使用）</li>
 *   <li>蜜蜂类型名 → {@link BeeIngredient} 供应商（繁殖、转化亲代使用）</li>
 *   <li>产出配置 → {@link TagOutputRecipe.ChancedOutput} 列表</li>
 * </ul>
 * 实例类（持有 {@link HolderLookup.Provider}），一次注入多次使用，符合 SRP。
 */
public final class RecipeIngredientFactory {

	private final HolderLookup.Provider registryAccess;

	/**
	 * @param registryAccess 注册表访问（来自 AddReloadListenerEvent.getRegistryAccess()）
	 */
	public RecipeIngredientFactory(HolderLookup.Provider registryAccess) {
		this.registryAccess = registryAccess;
	}

	/**
	 * 根据群系 ID 列表构建 HolderSet（每个元素为单个群系 ID）
	 */
	public HolderSet<Biome> createBiomeHolderSet(List<? extends String> biomeIds) {
		if (biomeIds == null || biomeIds.isEmpty()) {
			return HolderSet.empty();
		}
		List<Holder<Biome>> holders = new ArrayList<>();
		for (String id : biomeIds) {
			Holder<Biome> holder = resolveBiome(id);
			if (holder != null) {
				holders.add(holder);
			} else {
				// DevLog 节流日志便于排查（数据重载路径，统一门面）
				DevLog.warn("recipe_reload", "钓鱼群系 '{}' 未找到，已跳过", id);
			}
		}
		return holders.isEmpty() ? HolderSet.empty() : HolderSet.direct(holders);
	}

	/**
	 * 根据单个群系规格构建 HolderSet（支持标签 "#xxx" 或群系 ID "xxx"）
	 */
	public HolderSet<Biome> createBiomeHolderSetFromString(String biomeSpec) {
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
				// 显式宽化 Named<Biome> → HolderSet<Biome>，使 orElse 类型匹配
				return tag.<HolderSet<Biome>>map(n -> n).orElse(HolderSet.empty());
			} catch (Exception e) {
				// DevLog 节流日志便于排查（数据重载路径，统一门面）
				DevLog.warn("recipe_reload", "解析群系标签 '{}' 失败: {}", biomeSpec, e.toString());
				return HolderSet.empty();
			}
		}
		// 单个群系 ID
		Holder<Biome> holder = resolveBiome(biomeSpec);
		if (holder == null) {
			// DevLog 节流日志便于排查（数据重载路径，统一门面）
			DevLog.warn("recipe_reload", "生成群系 '{}' 未找到", biomeSpec);
			return HolderSet.empty();
		}
		return HolderSet.direct(List.of(holder));
	}

	/**
	 * 通过群系 ID 解析为 Holder<Biome>
	 */
	public Holder<Biome> resolveBiome(String biomeId) {
		try {
			ResourceLocation rl = ResourceLocation.parse(biomeId);
			ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, rl);
			return registryAccess.lookup(Registries.BIOME)
					.flatMap(reg -> reg.get(key))
					.map(h -> (Holder<Biome>) h)
					.orElse(null);
		} catch (Exception e) {
			// DevLog 节流日志便于排查（数据重载路径，统一门面）
			DevLog.warn("recipe_reload", "解析群系 '{}' 失败: {}", biomeId, e.toString());
			return null;
		}
	}

	/**
	 * 根据物品 ID 创建 Ingredient，找不到则返回 EMPTY
	 */
	public static Ingredient createIngredient(String itemId) {
		try {
			ResourceLocation rl = ResourceLocation.parse(itemId);
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
			return item.<Ingredient>map(i -> Ingredient.of(i)).orElse(Ingredient.EMPTY);
		} catch (Exception e) {
			// DevLog 节流日志便于排查（数据重载路径，统一门面）
			DevLog.warn("recipe_reload", "解析蜂巢物品 '{}' 失败，使用空 Ingredient: {}", itemId, e.toString());
			return Ingredient.EMPTY;
		}
	}

	/**
	 * 根据蜜蜂类型名获取 BeeIngredient 供应商
	 * <br/>
	 * 使用 {@link BeeIngredientFactory#getIngredient(String)} 获取 lazy supplier。
	 * 调用前会先校验 BeeIngredientFactory 已包含该类型，避免序列化时返回 null。
	 * 若类型不存在，回退到 minecraft:bee 防止 NPE。
	 */
	public static Supplier<BeeIngredient> getBeeIngredient(String name) {
		if (name == null || name.isBlank()) {
			return BeeIngredientFactory.getIngredient("minecraft:bee");
		}
		if (!BeeIngredientFactory.getOrCreateList().containsKey(name)) {
			// DevLog 节流日志便于排查（数据重载路径，统一门面）
			DevLog.warn("recipe_reload", "蜜蜂类型 '{}' 未在 BeeIngredientFactory 中找到，回退到 minecraft:bee", name);
			return BeeIngredientFactory.getIngredient("minecraft:bee");
		}
		return BeeIngredientFactory.getIngredient(name);
	}

	/**
	 * 根据配置构建万象创世蜜脾的产出列表
	 * <br/>
	 * 返回单个 {@link TagOutputRecipe.ChancedOutput}，物品、数量、概率均来自配置。
	 * 防御性处理：当配置出现 min > max 时自动纠正。
	 */
	public static List<TagOutputRecipe.ChancedOutput> createProduceOutputs() {
		Ingredient ingredient = createIngredient(ModConfig.SERVER.produceOutputItem.get());
		int min = ModConfig.SERVER.produceOutputMin.get();
		int max = ModConfig.SERVER.produceOutputMax.get();
		// 防御性处理：当配置出现 min > max 时自动纠正，避免 ChancedOutput 行为异常
		// 注：min > max 的告警由 ModConfig 交叉校验统一输出，此处不重复记录
		int finalMin = Math.min(min, max);
		int finalMax = Math.max(min, max);
		float chance = ModConfig.SERVER.produceOutputChance.get().floatValue();
		List<TagOutputRecipe.ChancedOutput> outputs = new ArrayList<>(1);
		outputs.add(new TagOutputRecipe.ChancedOutput(ingredient, finalMin, finalMax, chance));
		return outputs;
	}
}
