package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
	 * 根据群系 ID 列表构建 HolderSet（每个元素为单个群系 ID）。
	 * 空列表表示不限群系；非空列表没有有效群系时返回解析失败，不能用空集合放开限制。
	 */
	public Optional<HolderSet<Biome>> createBiomeHolderSet(List<? extends String> biomeIds) {
		if (biomeIds == null) {
			return Optional.empty();
		}
		if (biomeIds.isEmpty()) {
			return Optional.of(HolderSet.empty());
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
		if (holders.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(HolderSet.direct(holders));
	}

	/**
	 * 根据单个群系规格构建 HolderSet（支持标签 "#xxx" 或群系 ID "xxx"）。
	 * 空白规格、缺失或空标签均为解析失败。
	 */
	public Optional<HolderSet<Biome>> createBiomeHolderSetFromString(String biomeSpec) {
		if (biomeSpec == null || biomeSpec.isBlank()) {
			return Optional.empty();
		}
		if (biomeSpec.startsWith("#")) {
			ResourceLocation tagLoc = ResourceLocation.tryParse(biomeSpec.substring(1));
			if (tagLoc == null) {
				ProductiveBeesGenesis.LOGGER.warn("群系标签 '{}' 格式无效", biomeSpec);
				return Optional.empty();
			}
			TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagLoc);
			Optional<HolderSet.Named<Biome>> tag = registryAccess.lookup(Registries.BIOME)
					.flatMap(reg -> reg.get(tagKey));
			if (tag.isEmpty() || tag.get().size() == 0) {
				ProductiveBeesGenesis.LOGGER.warn("生成群系标签 '{}' 不存在或为空", biomeSpec);
				return Optional.empty();
			}
			return tag.<HolderSet<Biome>>map(n -> n);
		}
		Holder<Biome> holder = resolveBiome(biomeSpec);
		if (holder == null) {
			ProductiveBeesGenesis.LOGGER.warn("生成群系 '{}' 未找到", biomeSpec);
			return Optional.empty();
		}
		return Optional.of(HolderSet.direct(List.of(holder)));
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
			ProductiveBeesGenesis.LOGGER.warn("解析群系 '{}' 失败: {}", biomeId, e.toString());
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
