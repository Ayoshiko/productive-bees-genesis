package com.ayoshiko.productivebeesgenesis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 无尽·创世蜜蜂事件处理器
 * <br/>
 * 负责：
 * <ol>
 *   <li>蜜蜂类型缓存管理（定期从PB数据源刷新，排除infinitycreation与myriadcreations）</li>
 *   <li>随机蜜脾/蜜脾块生成（供Mixin调用）</li>
 *   <li>离心机追加产出逻辑 + 空转拦截（独立于万象创世处理器，实现风险隔离）</li>
 * </ol>
 * <p>
 * 公共逻辑继承自 {@link AbstractCombEventHandler}，本类仅保留无尽·创世特有的：
 * <ul>
 *   <li>输入物品为自定义的INFINITY_CREATION_COMB/INFINITY_CREATION_COMB_BLOCK_ITEM（专属物品）</li>
 *   <li>缓存排除infinitycreation与myriadcreations，避免无限循环转化</li>
 *   <li>不使用配置过滤（独立模块，不依赖ModConfig的万象创世过滤项）</li>
 * </ul>
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class InfinityCreationEventHandler extends AbstractCombEventHandler {

	/** 无尽·创世蜜蜂类型ID */
	public static final ResourceLocation INFINITY_CREATION_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "infinitycreation");

	/** 万象创世蜜蜂类型ID — 缓存排除用，避免无限循环转化 */
	private static final ResourceLocation MYRIADCREATIONS_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "myriadcreations");

	/** 缓存排除infinitycreation与myriadcreations后的所有蜜蜂类型（volatile保证跨线程可见性） */
	private static volatile CopyOnWriteArrayList<ResourceLocation> CACHED_BEE_TYPES = new CopyOnWriteArrayList<>();
	private static final AtomicBoolean CACHE_VALID = new AtomicBoolean(false);
	private static final AtomicInteger lastCacheUpdateTick = new AtomicInteger(0);

	/** 按 handler 实例存储空转拦截缓存，避免多机器场景下缓存互相覆盖 */
	private static final ConcurrentHashMap<IItemHandlerModifiable, BlockCheckCache> BLOCK_CHECK_CACHES = new ConcurrentHashMap<>();

	// ========== 缓存管理 ==========

	/** 服务器tick事件 — 定期更新蜜蜂类型缓存 */
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		if (lastCacheUpdateTick.incrementAndGet() >= CACHE_UPDATE_INTERVAL) {
			lastCacheUpdateTick.set(0);
			updateBeeTypeCache(event.getServer().overworld());
		}
	}

	/** 服务器停止事件 — 清理static缓存防止内存泄漏 */
	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		clearBlockCheckCaches(BLOCK_CHECK_CACHES);
		CACHE_VALID.set(false);
	}

	/**
	 * 更新蜜蜂类型缓存
	 * <p>
	 * 过滤逻辑：
	 * <ol>
	 *   <li>排除无尽·创世自身</li>
	 *   <li>排除万象创世（避免无限循环转化：infinitycreation→myriadcreations→infinitycreation）</li>
	 *   <li>排除没有离心配方的蜜蜂</li>
	 * </ol>
	 *
	 * @param level 服务端世界
	 */
	private static void updateBeeTypeCache(ServerLevel level) {
		Set<ResourceLocation> excluded = new HashSet<>();
		excluded.add(INFINITY_CREATION_TYPE);
		excluded.add(MYRIADCREATIONS_TYPE);

		CopyOnWriteArrayList<ResourceLocation> newCache = buildBeeTypeCache(level, excluded, null);
		if (newCache.isEmpty() && !CACHE_VALID.get()) {
			return;
		}
		CACHED_BEE_TYPES = newCache;
		CACHE_VALID.set(!newCache.isEmpty());
	}

	// ========== 随机蜜脾生成 ==========

	/** 获取随机蜜脾（排除infinitycreation与myriadcreations） */
	public static ItemStack getRandomHoneycomb() {
		return generateRandomHoneycomb(CACHED_BEE_TYPES);
	}

	/** 获取随机蜜脾块 */
	public static ItemStack getRandomCombBlock() {
		return generateRandomCombBlock(CACHED_BEE_TYPES);
	}

	// ========== 类型判断 ==========

	/**
	 * 检查是否为无尽·创世蜜脾
	 * <p>
	 * INFINITY_CREATION_COMB为自定义物品，注册时已预置bee_type=infinitycreation，
	 * 专属于infinitycreation蜜蜂，因此只需检查物品本身，无需检查bee_type数据组件。
	 *
	 * @param stack 待检查物品
	 * @return 是否为无尽·创世蜜脾
	 */
	public static boolean isInfinityCreationHoneycomb(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		try {
			return stack.getItem() == com.ayoshiko.productivebeesgenesis.init.ModItems.INFINITY_CREATION_COMB.get();
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("检查无尽·创世蜜脾类型时发生错误", e);
			return false;
		}
	}

	/**
	 * 检查是否为无尽·创世蜜脾块
	 * <p>
	 * INFINITY_CREATION_COMB_BLOCK_ITEM为自定义BlockItem，专属于infinitycreation蜜蜂，
	 * 因此只需检查物品本身，无需检查bee_type数据组件。
	 *
	 * @param stack 待检查物品
	 * @return 是否为无尽·创世蜜脾块
	 */
	public static boolean isInfinityCreationCombBlock(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		try {
			return stack.getItem() == com.ayoshiko.productivebeesgenesis.init.ModItems.INFINITY_CREATION_COMB_BLOCK_ITEM.get();
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("检查无尽·创世蜜脾块类型时发生错误", e);
			return false;
		}
	}

	// ========== 离心机公共逻辑（供Mixin调用）==========

	/**
	 * 空转拦截统一检查方法（仅对无尽·创世蜜脾/蜜脾块生效）
	 * <p>
	 * 当输入为无尽·创世蜜脾/蜜脾块且输出槽完全无空间时返回true（应阻止运行）。
	 *
	 * @param handler 物品处理器
	 * @return 是否应阻止机器运行
	 */
	public static boolean shouldBlockOperation(IItemHandlerModifiable handler) {
		return checkBlockOperation(handler, InfinityCreationEventHandler::isInfinityCreationItem, BLOCK_CHECK_CACHES);
	}

	/** 判断物品是否为无尽·创世蜜脾或蜜脾块（用于空转拦截） */
	private static boolean isInfinityCreationItem(Item item) {
		return item == com.ayoshiko.productivebeesgenesis.init.ModItems.INFINITY_CREATION_COMB.get()
				|| item == com.ayoshiko.productivebeesgenesis.init.ModItems.INFINITY_CREATION_COMB_BLOCK_ITEM.get();
	}

	/**
	 * 离心机追加随机蜜脾产出（无尽·创世核心机制：转化）
	 * <p>
	 * 设计理念：无尽·创世 = 「支付一个无尽·创世物品，转化为任意同类型物品」。
	 * 每个输入的无尽·创世蜜脾精确转化为1个随机蜜脾（线性缩放）。
	 *
	 * @param input              输入物品（须为无尽·创世蜜脾/蜜脾块）
	 * @param invHandler         物品处理器
	 * @param random             随机源
	 * @param productivityModifier PB升级倍率（无升级=1，Omega=32）
	 */
	public static void appendRandomCombs(ItemStack input, IItemHandlerModifiable invHandler, RandomSource random, int productivityModifier) {
		appendRandomCombsInternal(
				input, invHandler, random, productivityModifier,
				InfinityCreationEventHandler::isInfinityCreationHoneycomb,
				InfinityCreationEventHandler::isInfinityCreationCombBlock,
				CACHED_BEE_TYPES);
	}

	/**
	 * 从蜜蜂缓存中随机选取指定数量的不同类型
	 * <p>
	 * 委托给基类方法，保持public static API兼容。
	 */
	public static List<ResourceLocation> selectDistinctBeeTypes(int count, RandomSource random) {
		return selectDistinctBeeTypes(count, random, CACHED_BEE_TYPES);
	}

	/**
	 * 将 total 均匀分配到各蜜蜂类型上
	 * <p>
	 * 委托给基类方法，保持public static API兼容。
	 */
	public static java.util.Map<ResourceLocation, Integer> allocateEvenly(int total, List<ResourceLocation> types) {
		return AbstractCombEventHandler.allocateEvenly(total, types);
	}

	/**
	 * 检查离心机输出槽是否有剩余空间
	 * <p>
	 * 委托给基类方法，保持public static API兼容。
	 */
	public static boolean hasOutputSpace(IItemHandlerModifiable invHandler) {
		return AbstractCombEventHandler.hasOutputSpace(invHandler);
	}
}
