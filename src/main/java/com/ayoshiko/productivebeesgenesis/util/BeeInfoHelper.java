package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeHelper;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * 蜜蜂信息查询工具类
	 * <br/>
	 * 封装对 ProductiveBees 注册表和配方的访问：获取蜜蜂类型、显示名称、产物信息。
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
	 * 蜜蜂花朵偏好数据（Task E-1）
	 * <br/>
	 * 从 PB 的 CompoundTag 提取的花朵相关字段，供 {@link com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager}
	 * 进行喂食器花朵有效性精确匹配。字段含义与 PB ConfigurableBee 一致：
	 * flowerType（"blocks"/"items"/"entity_types"）、flowerTag、flowerItem、flowerFluid、
	 * flowerBlock（方块ID，如 sculk_bee 对应 minecraft:sculk_catalyst）、inverseFlower。
	 */
	public record FlowerPreference(
			String flowerType,
			String flowerTag,
			String flowerItem,
			String flowerFluid,
			String flowerBlock,
			boolean inverseFlower
	) {
		/** flowerType 常量：方块型花朵（默认） */
		public static final String TYPE_BLOCKS = "blocks";

		/** flowerType 常量：实体型花朵 */
		public static final String TYPE_ENTITY_TYPES = "entity_types";

		/** 空偏好（无花朵定义） */
		public static final FlowerPreference EMPTY =
				new FlowerPreference(TYPE_BLOCKS, "", "", "", "", false);

		/**
		 * 是否有花朵定义
		 *
		 * @return true 如果任一花朵字段非空
		 */
		public boolean hasFlowerDefinition() {
			return !flowerTag.isEmpty() || !flowerItem.isEmpty()
					|| !flowerFluid.isEmpty() || !flowerBlock.isEmpty();
		}
	}

	/**
	 * 花朵偏好缓存 — 高频调用（每tick每只蜜蜂）性能优化
	 * <p>
	 * 使用普通 {@link HashMap}（非 ConcurrentHashMap）+ volatile 引用原子替换实现线程安全：
	 * <ul>
	 * <li>{@link #invalidateCache()} 创建新的空 HashMap 原子替换引用，避免 put/clear 与 get 并发冲突</li>
	 * <li>{@link #getFlowerPreference} 读取时拿到引用副本，无锁访问，HashMap.get 比 ConcurrentHashMap.get 快 3-4 倍</li>
	 * <li>绝大多数调用是 cache hit（256× 加速下每 tick 数十次）</li>
	 * <li>cache miss 时（仅首次访问新蜜蜂类型）write-on-copy 复制整张 map 后原子替换引用，
	 *     与 invalidate 的引用替换通过 synchronized 互斥，保证写入不丢失</li>
	 * </ul>
	 */
	private static volatile Map<ResourceLocation, FlowerPreference> flowerPreferenceCache = new HashMap<>();

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
	 * <p>
	 * flowerPreferenceCache 通过 volatile 引用原子替换实现失效：
	 * 创建新的空 HashMap 替换引用，避免与 getFlowerPreference 的 write-on-copy 写操作
	 * 产生 HashMap 结构损坏。getFlowerPreference 的 cache hit 无锁访问无需保护。
	 */
	public static void invalidateCache() {
		cachedAllBeeTypes = null;
		// 模块 2+3：清空配方索引与输出表缓存，防止配方重载后返回过期数据
		BeeProduceQueries.invalidate();
		// 用 synchronized 保护 invalidate 与 getFlowerPreference 的 write-on-copy 互斥，
		// 避免新条目被 replace 覆盖造成 cache 写入丢失
		synchronized (BeeInfoHelper.class) {
			flowerPreferenceCache = new HashMap<>();
		}
		// 同步清空客户端蜜蜂实体缓存（spec 要求监听重载事件）
		// BeeEntityCache 仅引用通用 MC 类，服务端加载安全；try-catch 防御性保护
		try {
			com.ayoshiko.productivebeesgenesis.apiary.client.BeeEntityCache.clearCache();
		} catch (Exception | LinkageError e) {
			// 客户端类未加载时忽略（NoClassDefFoundError 是 LinkageError 子类，服务端无渲染实体缓存）
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
			// 模块 2+3：getBeeProduce 返回原始配方 Map，显示用途改用 getBeeProduceStacks（取 max 代表值）
			List<ItemStack> outputs = getBeeProduceStacks(level, beeType);
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
	 * 查询指定蜜蜂类型的产物配方输出表 — 委托 {@link BeeProduceQueries#getBeeProduce}
	 */
	@Nonnull
	public static Map<ItemStack, ChancedOutput> getBeeProduce(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		return BeeProduceQueries.getBeeProduce(level, beeType);
	}

	/**
	 * 查询指定蜜蜂类型的产物 ItemStack 列表（显示用途）— 委托 {@link BeeProduceQueries#getBeeProduceStacks}
	 */
	@Nonnull
	public static List<ItemStack> getBeeProduceStacks(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		return BeeProduceQueries.getBeeProduceStacks(level, beeType);
	}

	/**
	 * 解析蜜蜂的代表物品图标（默认蜜脾形态）
	 * <p>
	 * 委托给 {@link #resolveBeeIcon(Level, ResourceLocation, boolean)}，isBlock=false。
	 *
	 * @param level   客户端世界（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @return 代表该蜜蜂的图标 ItemStack（不会为空）
	 */
	@Nonnull
	public static ItemStack resolveBeeIcon(@Nonnull Level level, @Nonnull ResourceLocation beeType) {
		return resolveBeeIcon(level, beeType, false);
	}

	/**
	 * 解析蜜蜂的代表物品图标（区分蜜脾和蜜脾块）
	 * <p>
	 * isBlock=true 时直接返回绑定 bee_type 的 CONFIGURABLE_COMB_BLOCK；
	 * isBlock=false 时优先取配方首个产物，无产物时回退到 CONFIGURABLE_HONEYCOMB。
	 * PB 物品不可用时退化到原版蜜脾。返回堆叠数量固定为 1，避免渲染遮挡。
	 *
	 * @param level   客户端世界（用于配方查询）
	 * @param beeType 蜜蜂类型ID
	 * @param isBlock 是否为蜜脾块（true 返回 CONFIGURABLE_COMB_BLOCK，false 返回 CONFIGURABLE_HONEYCOMB）
	 * @return 代表该蜜蜂的图标 ItemStack（不会为空）
	 */
	@Nonnull
	public static ItemStack resolveBeeIcon(@Nonnull Level level, @Nonnull ResourceLocation beeType, boolean isBlock) {
		try {
			// V16: isBlock=true 时直接返回蜜脾块图标，不再忽略 isBlock 参数
			if (isBlock) {
				Item blockItem = ModItems.CONFIGURABLE_COMB_BLOCK.get();
				if (blockItem != null) {
					ItemStack stack = new ItemStack(blockItem);
					stack.set(ModDataComponents.BEE_TYPE.get(), beeType);
					return stack;
				}
				return new ItemStack(Items.HONEYCOMB);
			}
			// isBlock=false 时保持原逻辑：优先返回配方首个产物
			// 模块 2+3：getBeeProduce 返回原始配方 Map，显示用途改用 getBeeProduceStacks
			List<ItemStack> outputs = BeeProduceQueries.getBeeProduceStacks(level, beeType);
			for (ItemStack output : outputs) {
				if (!output.isEmpty()) {
					return output.copyWithCount(1);
				}
			}
			// 无配方产物时 fallback 到蜜脾物品
			Item combItem = ModItems.CONFIGURABLE_HONEYCOMB.get();
			if (combItem != null) {
				ItemStack stack = new ItemStack(combItem);
				stack.set(ModDataComponents.BEE_TYPE.get(), beeType);
				return stack;
			}
			return new ItemStack(Items.HONEYCOMB);
		} catch (RuntimeException e) {
			// DevLog 节流日志便于排查（渲染路径，避免刷屏）
			DevLog.warn("bee_info", "解析蜜蜂图标失败: {} - {}", beeType, e.toString());
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
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "hoarder_bee"),
				"productivebeesgenesis.config.product.hoarder_bee");

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
		} catch (RuntimeException e) {
			// DevLog 节流日志便于排查（渲染路径，避免刷屏）
			DevLog.warn("bee_info", "isBeeTypeExists 检查异常: {} - {}", beeType, e.toString());
			return false;
		}
	}

	/**
	 * 获取指定蜜蜂类型的花朵偏好（Task E-1）
	 * <br/>
	 * 从 {@link BeeReloadListener} 查询蜜蜂的 CompoundTag 数据，提取花朵相关字段。
	 * 数据缺失时返回 {@link FlowerPreference#EMPTY}。
	 * <p>
	 * 原理：BeeReloadListener 存储的 CompoundTag 直接包含 flowerTag/flowerItem/flowerFluid/
	 * flowerType/inverseFlower 字段（由 BeeCreator.create 写入），无需反射。
	 *
	 * @param beeType 蜜蜂类型 ID
	 * @return 花朵偏好数据，永不为 null
	 */
	@Nonnull
	public static FlowerPreference getFlowerPreference(@Nonnull ResourceLocation beeType) {
		// 优先查缓存（高频调用性能优化）
		// volatile 读取获取引用副本，后续访问无锁，避免 ConcurrentHashMap 的 volatile 读开销
		Map<ResourceLocation, FlowerPreference> cache = flowerPreferenceCache;
		FlowerPreference cached = cache.get(beeType);
		if (cached != null) {
			return cached;
		}
		try {
			CompoundTag nbt = BeeReloadListener.INSTANCE.getData(BeeTypeNormalizer.resolveLoadedBeeType(beeType));
			FlowerPreference preference;
			if (nbt == null) {
				preference = FlowerPreference.EMPTY;
			} else {
				String flowerType = nbt.getString("flowerType");
				if (flowerType.isEmpty()) {
					flowerType = FlowerPreference.TYPE_BLOCKS;
				}
				preference = new FlowerPreference(
					flowerType,
					nbt.getString("flowerTag"),
					nbt.getString("flowerItem"),
					nbt.getString("flowerFluid"),
					nbt.getString("flowerBlock"),
					nbt.getBoolean("inverseFlower")
			);
			}
			// 缓存查询结果（含 EMPTY，避免重复查询不存在的蜜蜂类型）
			// cache miss 是稀有事件（仅首次访问新蜜蜂类型），用 synchronized 保护写操作
			// write-on-copy 模式：复制当前 map 添加新条目后原子替换引用
			synchronized (BeeInfoHelper.class) {
				Map<ResourceLocation, FlowerPreference> current = flowerPreferenceCache;
				if (!current.containsKey(beeType)) {
					Map<ResourceLocation, FlowerPreference> newCache = new HashMap<>(current);
					newCache.put(beeType, preference);
					flowerPreferenceCache = newCache;
				}
			}
			return preference;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("获取蜜蜂花朵偏好失败: {}", beeType, e);
			return FlowerPreference.EMPTY;
		}
	}
}
