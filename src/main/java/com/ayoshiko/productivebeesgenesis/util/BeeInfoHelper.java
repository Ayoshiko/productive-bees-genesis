package com.ayoshiko.productivebeesgenesis.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * 蜜蜂信息查询工具类
 * <br/>
 * 封装对 ProductiveBees 注册表和配方的访问，提供：
 * <ol>
 *   <li>获取所有已注册的蜜蜂类型</li>
 *   <li>通过蜜蜂类型ID获取显示名称</li>
 *   <li>通过蜜蜂类型ID获取产物信息</li>
 * </ol>
 * <p>
 * 设计原则：单一职责（SRP），仅负责蜜蜂信息查询，不涉及配置读写。
 * <br/>
 * 线程安全：所有查询方法均为只读操作，BeeReloadListener 内部使用 Map 替换保证读安全。
 * 配方索引使用 volatile 不可变快照，重载时整体原子替换。
 */
public final class BeeInfoHelper {

	/**
	 * getAllBeeTypes 结果缓存
	 * <p>
	 * 蜜蜂类型在 BeeReloadListener 重载前不会变化，缓存避免 GUI 每次打开时重复遍历。
	 * 使用 volatile 保证跨线程可见性（客户端 GUI 线程读取，重载事件线程失效）。
	 * 缓存值为不可变列表，发布后安全共享。
	 */
	private static volatile List<ResourceLocation> cachedAllBeeTypes = null;

	/**
	 * 不可变快照：封装 AdvancedBeehiveRecipe 索引
	 * <p>
	 * 通过单一 volatile 引用原子替换，保证读线程看到一致状态。
	 * 替代旧版 getBeeProduce 中的 O(N) 全量遍历，将 GUI 打开时 N 个蜜蜂的产物查询
	 * 从 O(N²) 降为 O(N)。
	 */
	private record AdvancedBeehiveRecipeIndex(
			Map<String, RecipeHolder<AdvancedBeehiveRecipe>> byBeeType) {
		static final AdvancedBeehiveRecipeIndex EMPTY =
				new AdvancedBeehiveRecipeIndex(Map.of());
	}

	/** 当前配方索引 — volatile 引用保证原子替换 */
	private static volatile AdvancedBeehiveRecipeIndex beehiveRecipeIndex =
			AdvancedBeehiveRecipeIndex.EMPTY;

	private BeeInfoHelper() {
		// 工具类禁止实例化
	}

	/**
	 * 获取所有已注册的蜜蜂类型（排除万象创世自身）
	 * <p>
	 * 数据来源：ProductiveBees 的 BeeReloadListener，包含所有通过 JSON 注册的可配置蜜蜂。
	 * 结果会被缓存，直到 {@link #invalidateCache()} 被调用（通常在数据重载时）。
	 *
	 * @return 蜜蜂类型列表（只读副本，可能为空）
	 */
	@Nonnull
	public static List<ResourceLocation> getAllBeeTypes() {
		List<ResourceLocation> cached = cachedAllBeeTypes;
		if (cached != null) {
			return cached;
		}
		try {
			Map<ResourceLocation, ?> beeData = BeeReloadListener.INSTANCE.getData();
			if (beeData == null || beeData.isEmpty()) {
				return List.of();
			}
			List<ResourceLocation> result = new ArrayList<>(beeData.size());
			for (ResourceLocation beeType : beeData.keySet()) {
				if (!PBConstants.MYRIADCREATIONS_TYPE.equals(beeType)) {
					result.add(beeType);
				}
			}
			// 发布为不可变列表，便于安全共享
			List<ResourceLocation> immutable = List.copyOf(result);
			cachedAllBeeTypes = immutable;
			return immutable;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("获取蜜蜂类型列表时发生错误", e);
			return List.of();
		}
	}

	/**
	 * 失效蜜蜂类型缓存
	 * <p>
	 * 应在 BeeReloadListener 重载完成或数据包变更时调用，确保下次查询返回最新数据。
	 * 由 {@link ProductiveBeesGenesis#onTagsReload} 在 TagsUpdatedEvent 中统一调用。
	 * 同步失效 AdvancedBeehiveRecipe 索引，保证下次 getBeeProduce 重建索引。
	 */
	public static void invalidateCache() {
		cachedAllBeeTypes = null;
		beehiveRecipeIndex = AdvancedBeehiveRecipeIndex.EMPTY;
	}

	/**
	 * 通过蜜蜂类型ID获取显示名称
	 * <p>
	 * 翻译键格式：{@code entity.productivebees.<bee_name>_bee}
	 * <br/>其中 bee_name 为 ResourceLocation 的 path 去除 "_bee" 后缀。
	 * <p>
	 * 若翻译缺失，返回类型ID本身作为兜底。
	 *
	 * @param beeType 蜜蜂类型ID
	 * @return 显示名称组件
	 */
	@Nonnull
	public static Component getBeeDisplayName(@Nonnull ResourceLocation beeType) {
		try {
			String beeName = ProductiveBee.getBeeName(beeType);
			String translationKey = "entity." + PBConstants.PRODUCTIVE_BEES_MOD_ID + "." + beeName + "_bee";
			return Component.translatable(translationKey);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("获取蜜蜂显示名称失败: {}", beeType, e);
			return Component.literal(beeType.toString());
		}
	}

	/**
	 * 通过蜜蜂类型ID获取产物信息
	 * <p>
	 * 优先从 AdvancedBeehiveRecipe 配方中查询静态产出；
	 * 对于无配方但由环境决定产物的特殊蜜蜂（lumber/quarry/dye/wanna），
	 * 返回动态描述；均无产物时返回无产物翻译键。
	 *
	 * @param level   世界实例（用于配方查询，客户端传入 minecraft.level）
	 * @param beeType 蜜蜂类型ID
	 * @return 产物信息组件，无产物时返回空组件
	 */
	@Nonnull
	public static Component getBeeProductInfo(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		try {
			List<ItemStack> outputs = getBeeProduce(level, beeType);
			if (outputs.isEmpty()) {
				// 部分蜜蜂没有 AdvancedBeehiveRecipe，产物由花朵/琥珀等环境决定
				Component specialInfo = getSpecialBeeProductInfo(beeType);
				if (specialInfo != null) {
					return specialInfo;
				}
				return Component.translatable("productivebeesgenesis.config.no_product");
			}
			List<String> descriptions = new ArrayList<>();
			for (ItemStack stack : outputs) {
				String name = stack.getHoverName().getString();
				descriptions.add(name + " x" + stack.getCount());
			}
			return Component.literal(String.join(", ", descriptions));
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("获取蜜蜂产物信息失败: {}", beeType, e);
			return Component.empty();
		}
	}

	/**
	 * 查询指定蜜蜂类型的产物 ItemStack 列表
	 * <p>
	 * 优先通过静态索引 O(1) 查找配方；索引未建立时回退到全量遍历并构建索引。
	 * <p>
	 * 性能优化：原版每次调用都 O(N) 遍历所有 AdvancedBeehiveRecipe，
	 * GUI 打开时若有数百个蜜蜂类型会形成 O(N²) 复杂度。
	 * 改为首次调用时构建 {@code beeType -> recipe} 索引并发布为不可变快照，
	 * 后续调用 O(1) 命中，索引在 {@link #invalidateCache()} 时整体原子替换为空。
	 *
	 * @param level   客户端世界（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @return 产物列表（可能为空）
	 */
	@Nonnull
	public static List<ItemStack> getBeeProduce(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		try {
			String beeTypeKey = beeType.toString();
			// 1. 优先走索引（O(1)）
			RecipeHolder<AdvancedBeehiveRecipe> matched = beehiveRecipeIndex.byBeeType.get(beeTypeKey);
			if (matched == null) {
				// 2. 索引未命中时检查是否需要重建（避免 N 个蜜蜂各自重建 N 次的浪费）
				if (beehiveRecipeIndex == AdvancedBeehiveRecipeIndex.EMPTY) {
					rebuildBeehiveRecipeIndex(level);
					matched = beehiveRecipeIndex.byBeeType.get(beeTypeKey);
				}
				if (matched == null) {
					return List.of();
				}
			}
			List<ItemStack> result = new ArrayList<>();
			matched.value().getRecipeOutputs().forEach((stack, chancedOutput) -> {
				ItemStack copy = stack.copy();
				copy.setCount(Math.max(1, (int) chancedOutput.max()));
				result.add(copy);
			});
			return result;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("查询蜜蜂产物配方失败: {}", beeType, e);
			return List.of();
		}
	}

	/**
	 * 重建 AdvancedBeehiveRecipe 索引
	 * <p>
	 * 遍历全部配方，从每个配方的 ingredient（{@code Supplier<BeeIngredient>}）中提取 beeType，
	 * 构建 {@code beeType -> recipe} 映射。完成后发布为不可变快照，保证后续读取的线程安全。
	 * 单条配方解析失败不影响整体索引。
	 *
	 * @param level 世界实例
	 */
	private static void rebuildBeehiveRecipeIndex(@Nonnull Level level) {
		try {
			List<RecipeHolder<AdvancedBeehiveRecipe>> recipes = level.getRecipeManager()
					.getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get());
			Map<String, RecipeHolder<AdvancedBeehiveRecipe>> newIndex = new HashMap<>(recipes.size() * 2);
			for (RecipeHolder<AdvancedBeehiveRecipe> recipe : recipes) {
				try {
					// AdvancedBeehiveRecipe.ingredient 是 Supplier<BeeIngredient>，
					// 通过 supplier.get() 获取 BeeIngredient 后调用 getBeeType() 提取 beeType
					BeeIngredient ing = recipe.value().ingredient.get();
					if (ing == null) continue;
					ResourceLocation beeType = ing.getBeeType();
					if (beeType == null) continue;
					newIndex.putIfAbsent(beeType.toString(), recipe);
				} catch (Exception e) {
					ProductiveBeesGenesis.LOGGER.warn("构建 AdvancedBeehiveRecipe 索引时跳过无法解析的配方 {}", recipe.id(), e);
				}
			}
			// 原子替换：发布不可变快照
			beehiveRecipeIndex = new AdvancedBeehiveRecipeIndex(Map.copyOf(newIndex));
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("重建 AdvancedBeehiveRecipe 索引失败", e);
		}
	}

	/**
	 * 解析蜜蜂的代表物品图标
	 * <p>
	 * 优先取配方首个产物；无产物时回退到 PB 可配置蜜脾并绑定 bee_type 数据组件；
	 * PB 可配置蜜脾不可用时退化到原版蜜脾。返回堆叠数量固定为 1，避免渲染遮挡。
	 *
	 * @param level   客户端世界（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @return 代表该蜜蜂的图标 ItemStack（不会为空）
	 */
	@Nonnull
	public static ItemStack resolveBeeIcon(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		try {
			List<ItemStack> outputs = getBeeProduce(level, beeType);
			for (ItemStack output : outputs) {
				if (!output.isEmpty()) {
					return output.copyWithCount(1);
				}
			}
			Item honeycombItem = ModItems.CONFIGURABLE_HONEYCOMB.get();
			if (honeycombItem != null) {
				ItemStack stack = new ItemStack(honeycombItem);
				stack.set(ModDataComponents.BEE_TYPE.get(), beeType);
				return stack;
			}
			return new ItemStack(Items.HONEYCOMB);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析蜜蜂图标失败: {}", beeType, e);
			return new ItemStack(Items.HONEYCOMB);
		}
	}

	/** 特殊蜜蜂产物描述注册表：键为蜜蜂类型ID，值为产物翻译键（数据驱动，避免硬编码 switch） */
	private static final Map<ResourceLocation, String> SPECIAL_BEE_PRODUCTS = Map.of(
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "lumber_bee"), "productivebeesgenesis.config.product.lumber_bee",
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "quarry_bee"), "productivebeesgenesis.config.product.quarry_bee",
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "dye_bee"), "productivebeesgenesis.config.product.dye_bee",
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "wanna"), "productivebeesgenesis.config.product.wanna",
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "farmer_bee"), "productivebeesgenesis.config.product.farmer_bee",
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "collector_bee"), "productivebeesgenesis.config.product.collector_bee",
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "hoarder_bee"), "productivebeesgenesis.config.product.hoarder_bee");

	/**
	 * 获取特殊蜜蜂的动态产物描述
	 * <p>
	 * 从 {@link #SPECIAL_BEE_PRODUCTS} 数据驱动注册表中查询，未命中返回 null。
	 * ProductiveBees 中部分蜜蜂没有 AdvancedBeehiveRecipe，其产物由环境决定。
	 *
	 * @param beeType 蜜蜂类型ID
	 * @return 动态产物描述组件，非特殊蜜蜂返回 null
	 */
	@Nullable
	private static Component getSpecialBeeProductInfo(@Nonnull ResourceLocation beeType) {
		String key = SPECIAL_BEE_PRODUCTS.get(beeType);
		return key != null ? Component.translatable(key) : null;
	}

	/**
	 * 检查蜜蜂类型是否存在
	 *
	 * @param beeType 蜜蜂类型ID
	 * @return 是否已注册
	 */
	public static boolean isBeeTypeExists(@Nonnull ResourceLocation beeType) {
		try {
			Map<ResourceLocation, ?> beeData = BeeReloadListener.INSTANCE.getData();
			return beeData != null && beeData.containsKey(beeType);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("isBeeTypeExists 检查异常: {}", beeType, e);
			return false;
		}
	}

	/**
	 * 将字符串解析为 ResourceLocation
	 *
	 * @param id 字符串ID（如 "productivebees:iron"）
	 * @return 解析后的 ResourceLocation，解析失败返回 null
	 */
	@Nullable
	public static ResourceLocation parseBeeType(@Nonnull String id) {
		try {
			String trimmed = id.trim();
			if (trimmed.isEmpty()) return null;
			return ResourceLocation.parse(trimmed);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("parseBeeType 解析异常: {}", id, e);
			return null;
		}
	}
}
