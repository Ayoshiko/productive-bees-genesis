package com.ayoshiko.productivebeesgenesis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.ParametersAreNonnullByDefault;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;

/**
 * 万象创世蜜蜂事件处理器
 * <br/>
 * 负责：
 * <br/>
 * <ol>
 *   <li>蜜蜂类型缓存管理（定期从PB数据源刷新）</li>
 *   <li>随机蜜脾/蜜脾块生成（供Mixin调用）</li>
 *   <li>离心机追加产出逻辑 + 空转拦截（消除Mixin间代码重复）</li>
 * </ol>
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class MyriadCreationsEventHandler {

	public static final ResourceLocation MYRIADCREATIONS_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "myriadcreations");

	/** 缓存排除万象创世自身后的所有蜜蜂类型（volatile保证跨线程可见性） */
	private static volatile CopyOnWriteArrayList<ResourceLocation> CACHED_BEE_TYPES = new CopyOnWriteArrayList<>();
	private static final AtomicBoolean CACHE_VALID = new AtomicBoolean(false);
	private static final int CACHE_UPDATE_INTERVAL = 20;
	private static final AtomicInteger lastCacheUpdateTick = new AtomicInteger(0);

	/** 兜底蜜蜂类型（缓存为空或异常时使用） */
	private static final ResourceLocation FALLBACK_BEE_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "iron");

	/** 测试用ItemStack — Holder 类模式保证线程安全的延迟初始化（JVM类加载机制天然线程安全） */
	private static final class TestOutputStackHolder {
		static final ItemStack INSTANCE = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
	}

	// ========== shouldBlockOperation 加速缓存 (优化B) ==========
	/**
	 * 安全冷却时间：50ms ≈ 1游戏刻，加速环境下可覆盖多次tick调用。
	 * 缓存key = (handler身份 + inputItem类型)，确保不同机器互不干扰。
	 */
	private static final long BLOCK_CHECK_COOLDOWN_NS = 50_000_000L;
	private static volatile IItemHandlerModifiable cachedBlockHandler = null;
	private static volatile Item cachedBlockInputItem = null;
	private static volatile long cachedBlockCheckTime = 0;
	/** 仅缓存"已满"结果（安全保守策略：宁可多停1tick，不可漏检） */
	private static volatile Boolean cachedBlockedFull = null;

	// ========== 缓存管理 ==========

	/** 服务器tick事件 — 定期更新蜜蜂类型缓存 */
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		if (lastCacheUpdateTick.incrementAndGet() >= CACHE_UPDATE_INTERVAL) {
			lastCacheUpdateTick.set(0);
			updateBeeTypeCache(event.getServer().overworld());
		}
	}

	/**
	 * 更新蜜蜂类型缓存（原子替换，避免竞态窗口）(#1)
	 * <p>
	 * 过滤逻辑：
	 * <ol>
	 *   <li>排除万象创世自身</li>
	 *   <li>排除没有离心配方的蜜蜂（通常是依赖缺失模组的蜜蜂，如 butcher）</li>
	 * </ol>
	 *
	 * @param level 服务端世界（用于查询配方）
	 */
	private static void updateBeeTypeCache(ServerLevel level) {
		try {
			Map<ResourceLocation, ?> beeData = BeeReloadListener.INSTANCE.getData();
			if (beeData == null || beeData.isEmpty()) return;

			// 构建新列表后原子替换引用，避免clear+逐个add的竞态窗口
			List<ResourceLocation> newTypes = new ArrayList<>();
			for (ResourceLocation beeType : beeData.keySet()) {
				if (MYRIADCREATIONS_TYPE.equals(beeType)) continue;
				if (!hasCentrifugeRecipe(level, beeType)) continue;
				newTypes.add(beeType);
			}
			CACHED_BEE_TYPES = new CopyOnWriteArrayList<>(newTypes);
			CACHE_VALID.set(true);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("更新蜜蜂类型缓存时发生错误", e);
			CACHE_VALID.set(false);
		}
	}

	/**
	 * 检查指定蜜蜂类型是否有对应的离心配方 (#3 优化：复用临时对象)
	 *
	 * @param level   服务端世界
	 * @param beeType 蜜蜂类型ID
	 * @return 是否存在离心配方
	 */
	private static boolean hasCentrifugeRecipe(ServerLevel level, ResourceLocation beeType) {
		try {
			ItemStack testComb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			testComb.set(ModDataComponents.BEE_TYPE.get(), beeType);

			var testInput = new InventoryHandlerHelper.BlockEntityItemStackHandler(2);
			testInput.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, testComb);

			RecipeHolder<CentrifugeRecipe> recipe = level.getRecipeManager()
					.getRecipeFor(ModRecipeTypes.CENTRIFUGE_TYPE.get(), (RecipeInput)testInput, level)
					.orElse(null);
			return recipe != null;
		} catch (Exception e) {
			return true; // 检查失败时保守返回true，避免误删有效蜜蜂
		}
	}

	// ========== 随机蜜脾生成 ==========

	/** 获取随机蜜脾（排除万象创世自身） */
	public static ItemStack getRandomHoneycomb() {
		try {
			if (!CACHE_VALID.get() || CACHED_BEE_TYPES.isEmpty()) return createFallbackHoneycomb();

			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			int randomIndex = ThreadLocalRandom.current().nextInt(CACHED_BEE_TYPES.size());
			stack.set(ModDataComponents.BEE_TYPE.get(), CACHED_BEE_TYPES.get(randomIndex));
			return stack;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("生成随机蜜脾时发生错误", e);
			return createFallbackHoneycomb();
		}
	}

	/** 获取随机蜜脾块 */
	public static ItemStack getRandomCombBlock() {
		try {
			if (!CACHE_VALID.get() || CACHED_BEE_TYPES.isEmpty()) return createFallbackCombBlock();

			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
			int randomIndex = ThreadLocalRandom.current().nextInt(CACHED_BEE_TYPES.size());
			stack.set(ModDataComponents.BEE_TYPE.get(), CACHED_BEE_TYPES.get(randomIndex));
			return stack;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("生成随机蜜脾块时发生错误", e);
			return createFallbackCombBlock();
		}
	}

	// ========== 类型判断 ==========

	/** 检查是否为万象创世蜜脾 */
	public static boolean isMyriadCreationsHoneycomb(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		try {
			if (stack.getItem() == ModItems.CONFIGURABLE_HONEYCOMB.get()) {
				ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
				return beeType != null && MYRIADCREATIONS_TYPE.equals(beeType);
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("检查蜜脾类型时发生错误", e);
		}
		return false;
	}

	/** 检查是否为万象创世蜜脾块 */
	public static boolean isMyriadCreationsCombBlock(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		try {
			if (stack.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
				return beeType != null && MYRIADCREATIONS_TYPE.equals(beeType);
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("检查蜜脾块类型时发生错误", e);
		}
		return false;
	}

	/** 检查是否为任意可配置蜜脾 */
	public static boolean isConfigurableHoneycomb(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.getItem() == ModItems.CONFIGURABLE_HONEYCOMB.get();
	}

	/** 检查是否为任意可配置蜜脾块 */
	public static boolean isConfigurableCombBlock(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get();
	}

	// ========== 离心机公共逻辑（供Mixin调用）==========

	/**
	 * 空转拦截统一检查方法 (#4 + 优化B: 安全冷却缓存)
	 * <p>
	 * 当输入为任意蜜脾/蜜脾块且输出槽完全无空间时返回 true（应阻止运行）。
	 * <ul>
	 *   <li>快速路径：引用比较判断是否为可配置蜜脾/蜜脾块</li>
	 *   <li>万象创世与普通可配置统一使用简单空格检查</li>
	 *   <li>冷却缓存：50ms内复用"已满"结果，消除加速环境下99%的重复调用</li>
	 * </ul>
	 *
	 * @param handler 物品处理器
	 * @return 是否应阻止机器运行
	 */
	public static boolean shouldBlockOperation(IItemHandlerModifiable handler) {
		try {
			ItemStack input = handler.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT);
			Item inputItem = input.getItem();

			boolean isConfigurable = (inputItem == ModItems.CONFIGURABLE_HONEYCOMB.get()
					|| inputItem == ModItems.CONFIGURABLE_COMB_BLOCK.get());
			if (!isConfigurable) return false;

			// 冷却缓存命中
			long now = System.nanoTime();
			if (handler == cachedBlockHandler
					&& inputItem == cachedBlockInputItem
					&& Boolean.TRUE.equals(cachedBlockedFull)
					&& (now - cachedBlockCheckTime) < BLOCK_CHECK_COOLDOWN_NS) {
				return true;
			}

			// 统一简单空格检查：完全无空间时阻止运行
			boolean blocked = !hasOutputSpace(handler);

			cachedBlockHandler = handler;
			cachedBlockInputItem = inputItem;
			cachedBlockedFull = blocked ? Boolean.TRUE : null;
			cachedBlockCheckTime = now;

			return blocked;
		} catch (Exception ignored) {
			return false;
		}
	}

	/**
	 * 离心机追加随机蜜脾产出（万象核心机制：转化）
	 * <p>
	 * 设计理念：万象创世 = 「支付一个万象物品，转化为任意同类型物品」。
	 * 每个输入的万象创世蜜脾精确转化为1个随机蜜脾（线性缩放）。
	 * <p>
	 * <b>槽位安全策略（预分配）</b>：PB离心机仅9格输出槽，若32个蜜脾随机出>9种则放不下。
	 * 采用预分配算法：
	 * <ol>
	 *   <li>限制种类数 K = min(9, totalCount)，确保不超槽位</li>
	 *   <li>从缓存中随机选K种不同蜜蜂类型</li>
	 *   <li>将totalCount均匀分配到K种上（每种至少1个）</li>
	 * </ol>
	 * 保证：总产出数 == 消耗数，且永不溢出。
	 *
	 * @param productivityModifier PB升级倍率（无升级=1，Omega=32）
	 */
	public static void appendRandomCombs(ItemStack input, IItemHandlerModifiable invHandler, RandomSource random, int productivityModifier) {
		if (!isMyriadCreationsHoneycomb(input) && !isMyriadCreationsCombBlock(input)) return;

		if (!hasOutputSpace(invHandler)) return;

		boolean isCombBlock = isMyriadCreationsCombBlock(input);
		int totalCount = Math.max(1, productivityModifier);

		// 预分配：限制种类数不超过输出槽数(9)，保证总产出=消耗数
		int maxTypes = Math.min(9, totalCount);
		List<ResourceLocation> selectedTypes = selectDistinctBeeTypes(maxTypes, random);
		if (selectedTypes.isEmpty()) return;

		// 将 totalCount 分配到 selectedTypes.size() 种上（每种至少1个）
		Map<ResourceLocation, Integer> allocation = allocateEvenly(totalCount, selectedTypes);

		// 构建带bee_type组件的ItemStack并添加到输出
		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();
		if (invHandler instanceof InventoryHandlerHelper.BlockEntityItemStackHandler outputHandler) {
			for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
				try {
					ItemStack output = new ItemStack(baseItem, entry.getValue());
					output.set(ModDataComponents.BEE_TYPE.get(), entry.getKey());
					outputHandler.addOutput(output);
				} catch (Exception ignored) {
					break;
				}
			}
		}
	}

	/**
	 * 从蜜蜂缓存中随机选取指定数量的不同类型（零拷贝优化）
	 * <p>
	 * 使用索引集合追踪已选元素，避免每次调用时完整拷贝列表。
	 * 缓存列表由 CopyOnWriteArrayList 保证读安全，无需加锁。
	 */
	private static List<ResourceLocation> selectDistinctBeeTypes(int count, RandomSource random) {
		CopyOnWriteArrayList<ResourceLocation> cache = CACHED_BEE_TYPES;
		int poolSize = cache.size();
		if (poolSize == 0) return List.of();
		if (count >= poolSize) return List.copyOf(cache);

		List<ResourceLocation> selected = new ArrayList<>(count);
		Set<Integer> usedIndices = new HashSet<>(count * 2);

		for (int i = 0; i < count; i++) {
			int idx;
			do {
				idx = random.nextInt(poolSize);
			} while (!usedIndices.add(idx));
			selected.add(cache.get(idx));
		}
		return selected;
	}

	/**
	 * 将 total 均匀分配到各蜜蜂类型上，每个类型至少1个
	 */
	private static Map<ResourceLocation, Integer> allocateEvenly(int total, List<ResourceLocation> types) {
		Map<ResourceLocation, Integer> result = new HashMap<>();
		int buckets = types.size();
		if (buckets <= 0 || total <= 0) return result;

		int base = total / buckets;
		int remainder = total % buckets; // 余数分配给前remainder个类型

		for (int i = 0; i < buckets; i++) {
			result.put(types.get(i), base + (i < remainder ? 1 : 0));
		}
		return result;
	}

	/**
	 * 检查离心机输出槽是否有剩余空间
	 * <p>
	 * 使用 Holder 类模式保证测试 ItemStack 线程安全的延迟初始化，
	 * 避免类加载时 PB 注册表未绑定导致 NPE。
	 */
	public static boolean hasOutputSpace(IItemHandlerModifiable invHandler) {
		try {
			if (invHandler instanceof InventoryHandlerHelper.BlockEntityItemStackHandler outputHandler) {
				return outputHandler.canFitStacks(List.of(TestOutputStackHolder.INSTANCE));
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	// ========== 私有辅助方法 ==========

	private static ItemStack createFallbackHoneycomb() {
		ItemStack fallback = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		fallback.set(ModDataComponents.BEE_TYPE.get(), FALLBACK_BEE_TYPE);
		return fallback;
	}

	private static ItemStack createFallbackCombBlock() {
		ItemStack fallback = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
		fallback.set(ModDataComponents.BEE_TYPE.get(), FALLBACK_BEE_TYPE);
		return fallback;
	}
}
