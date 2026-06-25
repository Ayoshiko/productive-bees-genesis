package com.ayoshiko.productivebeesgenesis.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.ProductiveBees;
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
 */
public final class BeeInfoHelper {

	/** 万象创世自身类型，列表展示时排除 */
	private static final ResourceLocation MYRIADCREATIONS_TYPE =
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "myriadcreations");

	private BeeInfoHelper() {
		// 工具类禁止实例化
	}

	/**
	 * 获取所有已注册的蜜蜂类型（排除万象创世自身）
	 * <p>
	 * 数据来源：ProductiveBees 的 BeeReloadListener，包含所有通过 JSON 注册的可配置蜜蜂。
	 *
	 * @return 蜜蜂类型列表（只读副本，可能为空）
	 */
	@Nonnull
	public static List<ResourceLocation> getAllBeeTypes() {
		try {
			Map<ResourceLocation, ?> beeData = BeeReloadListener.INSTANCE.getData();
			if (beeData == null || beeData.isEmpty()) {
				return List.of();
			}
			List<ResourceLocation> result = new ArrayList<>(beeData.size());
			for (ResourceLocation beeType : beeData.keySet()) {
				if (!MYRIADCREATIONS_TYPE.equals(beeType)) {
					result.add(beeType);
				}
			}
			return result;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("获取蜜蜂类型列表时发生错误", e);
			return List.of();
		}
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
			String translationKey = "entity." + ProductiveBees.MODID + "." + beeName + "_bee";
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
	 * 遍历 AdvancedBeehiveRecipe 配方，匹配 beeType 后返回产物。
	 *
	 * @param level   客户端世界（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @return 产物列表（可能为空）
	 */
	@Nonnull
	public static List<ItemStack> getBeeProduce(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		try {
			List<RecipeHolder<AdvancedBeehiveRecipe>> recipes = level.getRecipeManager()
					.getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get());
			// 使用 PB 自身的 IdentifierInventory 进行配方匹配，避免手动比较遗漏
			BeeHelper.IdentifierInventory beeInv = new BeeHelper.IdentifierInventory(beeType.toString());
			RecipeHolder<AdvancedBeehiveRecipe> matched = null;
			for (RecipeHolder<AdvancedBeehiveRecipe> recipe : recipes) {
				if (recipe.value().matches(beeInv, level)) {
					matched = recipe;
					break;
				}
			}
			if (matched == null) {
				return List.of();
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
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "lumber_bee"), "productivebeesgenesis.config.product.lumber_bee",
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "quarry_bee"), "productivebeesgenesis.config.product.quarry_bee",
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "dye_bee"), "productivebeesgenesis.config.product.dye_bee",
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "wanna"), "productivebeesgenesis.config.product.wanna",
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "farmer_bee"), "productivebeesgenesis.config.product.farmer_bee",
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "collector_bee"), "productivebeesgenesis.config.product.collector_bee",
			ResourceLocation.fromNamespaceAndPath(ProductiveBees.MODID, "hoarder_bee"), "productivebeesgenesis.config.product.hoarder_bee");

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
			return null;
		}
	}
}
