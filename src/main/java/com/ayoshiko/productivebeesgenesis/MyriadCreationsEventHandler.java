package com.ayoshiko.productivebeesgenesis;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 万象创世蜜蜂事件处理器
 * <br/>
 * 负责：
 * <ol>
 *   <li>蜜蜂类型缓存管理（定期从PB数据源刷新，应用配置过滤）</li>
 *   <li>随机蜜脾/蜜脾块生成（供Mixin调用）</li>
 *   <li>离心机追加产出逻辑 + 空转拦截</li>
 * </ol>
 * <p>
 * 公共逻辑继承自 {@link AbstractCombEventHandler}，本类仅保留万象创世特有的：
 * <ul>
 *   <li>配置文件黑白名单过滤</li>
 *   <li>基于 bee_type 数据组件的类型判断</li>
 * </ul>
 * <p>
 * 注：万象创世蜜蜂使用 PB 的 configurable_honeycomb + bee_type 数据组件携带类型信息，
 * 不自动生成 configurable_honeycomb（createComb: false），与原 PB 体系兼容。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class MyriadCreationsEventHandler extends AbstractCombEventHandler {

	/** 缓存排除万象创世自身后的所有蜜蜂类型（volatile保证跨线程可见性） */
	private static volatile CopyOnWriteArrayList<ResourceLocation> CACHED_BEE_TYPES = new CopyOnWriteArrayList<>();
	/** 预构建的蜜脾模板数组，与 CACHED_BEE_TYPES 同步更新，避免高频生成时重复创建ItemStack */
	private static volatile ItemStack[] CACHED_HONEYCOMB_TEMPLATES = new ItemStack[0];
	/** 预构建的蜜脾块模板数组 */
	private static volatile ItemStack[] CACHED_COMB_BLOCK_TEMPLATES = new ItemStack[0];
	private static final AtomicBoolean CACHE_VALID = new AtomicBoolean(false);
	private static final AtomicInteger lastCacheUpdateTick = new AtomicInteger(0);

	/**
	 * 按 handler 实例存储空转拦截缓存，避免多机器场景下缓存互相覆盖
	 * <p>
	 * 使用 {@link Collections#synchronizedMap} 包装的 {@link WeakHashMap}：
	 * <ul>
	 *   <li>WeakHashMap 的 key 为弱引用，handler 与 BlockEntity 生命周期绑定，
	 *       BlockEntity 被 GC 时 handler 也会被 GC，缓存条目自动被回收，避免内存泄漏</li>
	 *   <li>synchronizedMap 提供线程安全访问，复合操作在 {@link CombBlockCheckCache#checkBlockOperation}
	 *       内通过 synchronized 块保护</li>
	 * </ul>
	 */
	private static final Map<IItemHandlerModifiable, CombBlockCheckCache.BlockCheckCache> BLOCK_CHECK_CACHES =
			Collections.synchronizedMap(new WeakHashMap<>());

	// ========== 缓存管理 ==========
	// Task 23: 类型选择缓存逻辑已抽取到 {@link MyriadSelectionCache}（SRP 拆分）

	/** 服务器tick事件 — 定期更新蜜蜂类型缓存 */
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		// 万象创世功能被禁用时，跳过缓存更新
		if (!isMyriadCreationsEnabled()) return;
		if (lastCacheUpdateTick.incrementAndGet() >= CACHE_UPDATE_INTERVAL) {
			lastCacheUpdateTick.set(0);
			updateBeeTypeCache(event.getServer().overworld());
		}
	}

	/** 服务器停止事件 — 清理static缓存防止内存泄漏 */
	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		CombBlockCheckCache.clearCaches(BLOCK_CHECK_CACHES);
		CACHE_VALID.set(false);
		// 清理蜜蜂类型缓存和预构建模板，防止跨存档切换时旧存档数据残留
		// 不清理会导致：旧存档的蜜蜂类型在新存档不存在时仍被使用，产生无效产物
		CACHED_BEE_TYPES = new CopyOnWriteArrayList<>();
		CACHED_HONEYCOMB_TEMPLATES = new ItemStack[0];
		CACHED_COMB_BLOCK_TEMPLATES = new ItemStack[0];
		lastCacheUpdateTick.set(0);
		MyriadSelectionCache.invalidate();
	}

	/**
	 * 失效过滤缓存（Task 15）。
	 * <p>
	 * 在 {@code ModConfigEvent.Reloading} 监听器中调用，使基于配置过滤的蜜蜂类型缓存
	 * 立即失效。配置重载后过滤模式或过滤列表可能变化，缓存中的蜜蜂类型集合已过期，
	 * 必须主动失效让下次 {@link #onServerTick} 重建缓存反映最新配置。
	 * <p>
	 * 失效范围：
	 * <ul>
	 *   <li>{@link #CACHE_VALID} 置为 false，使 {@link #updateBeeTypeCache} 在下次 tick 强制重建</li>
	 *   <li>委托 {@link MyriadSelectionCache#invalidate()} 递增版本号并重置所有类型选择缓存条目，
	 *       使版本号校验失败，强制下次随机采样重新执行</li>
	 * </ul>
	 * <p>
	 * 线程安全：AtomicBoolean/AtomicInteger 保证可见性；MyriadSelectionCache 字段为 volatile，
	 * 多工厂实例共享静态数组时交叉访问安全。
	 */
	public static void invalidateFilterCache() {
		CACHE_VALID.set(false);
		MyriadSelectionCache.invalidate();
	}

	/**
	 * 更新蜜蜂类型缓存
	 * <p>
	 * 过滤逻辑：
	 * <ol>
	 *   <li>排除万象创世自身</li>
	 *   <li>排除没有离心配方的蜜蜂</li>
	 *   <li>应用配置文件的黑白名单过滤</li>
	 * </ol>
	 * <p>
	 * 性能优化：配置的过滤模式与过滤列表在单次缓存更新内不会变化，
	 * 预先构建 filterSet 并捕获到 Predicate 中，避免对每个蜜蜂类型重复创建 HashSet。
	 *
	 * @param level 服务端世界
	 */
	private static void updateBeeTypeCache(ServerLevel level) {
		Set<ResourceLocation> excluded = new HashSet<>();
		excluded.add(PBConstants.MYRIADCREATIONS_TYPE);

		// 预先读取配置并构建 filterSet，避免在 Predicate 中对每个蜜蜂类型重复分配
		ModConfig.FilterMode mode = ModConfig.SERVER.myriadCreationsFilterMode.get();
		List<? extends String> filteredList = ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get();
		Set<String> filterSet = filteredList.isEmpty() ? Set.of() : new HashSet<>(filteredList);

		CopyOnWriteArrayList<ResourceLocation> newCache = buildBeeTypeCache(
				level, excluded, beeType -> applyConfigFilter(beeType, mode, filterSet));
		if (newCache.isEmpty() && !CACHE_VALID.get()) {
			// 首次构建且为空时不更新，保留旧缓存
			return;
		}
		CACHED_BEE_TYPES = newCache;
		// 同步预构建模板数组，256倍加速下通过copy()替代new ItemStack+set组件，显著降低GC压力
		CACHED_HONEYCOMB_TEMPLATES = RandomHoneycombSelector.buildHoneycombTemplates(newCache);
		CACHED_COMB_BLOCK_TEMPLATES = RandomHoneycombSelector.buildCombBlockTemplates(newCache);
		CACHE_VALID.set(!newCache.isEmpty());
		// Task 23: 递增版本号使类型选择缓存自动失效
		MyriadSelectionCache.onBeeTypesUpdated();
	}

	/**
	 * 配置过滤谓词 — 根据配置的黑白名单模式过滤
	 * <p>
	 * DISABLED — 不过滤（默认）
	 * BLACKLIST — 排除列表中的蜜蜂类型
	 * WHITELIST — 仅保留列表中的蜜蜂类型
	 * <p>
	 * filterSet 由调用方预先构建并传入，避免对每个蜜蜂类型重复创建 HashSet。
	 *
	 * @param beeType   待检查的蜜蜂类型
	 * @param mode      过滤模式
	 * @param filterSet 预先构建的过滤集合
	 * @return true 保留该类型，false 排除
	 */
	private static boolean applyConfigFilter(ResourceLocation beeType, ModConfig.FilterMode mode, Set<String> filterSet) {
		if (mode == ModConfig.FilterMode.DISABLED) return true;
		if (filterSet.isEmpty()) return true;

		String beeTypeStr = beeType.toString();
		if (mode == ModConfig.FilterMode.BLACKLIST) {
			return !filterSet.contains(beeTypeStr);
		} else if (mode == ModConfig.FilterMode.WHITELIST) {
			return filterSet.contains(beeTypeStr);
		}
		return true;
	}

	// ========== 随机蜜脾生成 ==========

	/** 获取随机蜜脾（排除万象创世自身） */
	public static ItemStack getRandomHoneycomb() {
		return RandomHoneycombSelector.generateRandomHoneycomb(CACHED_HONEYCOMB_TEMPLATES);
	}

	/** 获取随机蜜脾块 */
	public static ItemStack getRandomCombBlock() {
		return RandomHoneycombSelector.generateRandomCombBlock(CACHED_COMB_BLOCK_TEMPLATES);
	}

	/**
	 * 批量获取随机蜜脾
	 *
	 * @param count 生成数量
	 * @return 包含 count 个随机蜜脾的可变列表
	 */
	public static List<ItemStack> getRandomHoneycombs(int count) {
		return RandomHoneycombSelector.generateRandomHoneycombs(count, CACHED_HONEYCOMB_TEMPLATES);
	}

	/**
	 * 批量获取随机蜜脾块
	 *
	 * @param count 生成数量
	 * @return 包含 count 个随机蜜脾块的可变列表
	 */
	public static List<ItemStack> getRandomCombBlocks(int count) {
		return RandomHoneycombSelector.generateRandomCombBlocks(count, CACHED_COMB_BLOCK_TEMPLATES);
	}

	/**
	 * 批量追加随机蜜脾到输出列表
	 *
	 * @param out   输出列表
	 * @param count 生成数量
	 */
	public static void appendRandomHoneycombs(List<ItemStack> out, int count) {
		RandomHoneycombSelector.appendRandomHoneycombs(out, count, CACHED_HONEYCOMB_TEMPLATES);
	}

	/**
	 * 批量追加随机蜜脾块到输出列表
	 *
	 * @param out   输出列表
	 * @param count 生成数量
	 */
	public static void appendRandomCombBlocks(List<ItemStack> out, int count) {
		RandomHoneycombSelector.appendRandomCombBlocks(out, count, CACHED_COMB_BLOCK_TEMPLATES);
	}

	/**
	 * 获取聚合后的随机蜜脾（最多 9 种类型，每种 1~2 个 stack）
	 *
	 * @param totalCount 总数量
	 * @param random     随机源
	 * @return 聚合后的随机蜜脾列表，如果万象创世被禁用则返回空列表
	 */
	public static List<ItemStack> getAggregatedRandomHoneycombs(int totalCount, RandomSource random) {
		if (!isMyriadCreationsEnabled()) return List.of();
		return RandomHoneycombSelector.generateAggregatedStacks(
				totalCount,
				ModItems.CONFIGURABLE_HONEYCOMB.get(),
				CACHED_BEE_TYPES,
				CACHED_HONEYCOMB_TEMPLATES,
				random);
	}

	/**
	 * 获取聚合后的随机蜜脾块（最多 9 种类型，每种 1~2 个 stack）
	 *
	 * @param totalCount 总数量
	 * @param random     随机源
	 * @return 聚合后的随机蜜脾块列表，如果万象创世被禁用则返回空列表
	 */
	public static List<ItemStack> getAggregatedRandomCombBlocks(int totalCount, RandomSource random) {
		if (!isMyriadCreationsEnabled()) return List.of();
		return RandomHoneycombSelector.generateAggregatedStacks(
				totalCount,
				ModItems.CONFIGURABLE_COMB_BLOCK.get(),
				CACHED_BEE_TYPES,
				CACHED_COMB_BLOCK_TEMPLATES,
				random);
	}

	/**
	 * 检查物品是否为万象创世蜜脾或蜜脾块
	 */
	public static boolean isMyriadCreationsItem(ItemStack stack) {
		return isMyriadCreationsHoneycomb(stack) || isMyriadCreationsCombBlock(stack);
	}

	/**
	 * 向输出列表追加指定数量的万象创世蜜脾（聚合为不超过 64 的 stack）
	 */
	public static void appendMyriadHoneycombStacks(List<ItemStack> out, int count) {
		if (!isMyriadCreationsEnabled()) return;
		if (count <= 0) return;
		Item item = ModItems.CONFIGURABLE_HONEYCOMB.get();
		while (count > 0) {
			int stackSize = Math.min(64, count);
			ItemStack stack = new ItemStack(item, stackSize);
			stack.set(ModDataComponents.BEE_TYPE.get(), PBConstants.MYRIADCREATIONS_TYPE);
			out.add(stack);
			count -= stackSize;
		}
	}

	// ========== 类型判断 ==========

	/**
	 * 检查万象创世蜜蜂功能是否启用
	 * <p>
	 * 用于配方、事件处理器等判断是否应该处理万象创世蜜蜂相关逻辑。
	 * <p>
	 * 注意：此方法在客户端和服务端都可以调用。NeoForge 会自动同步 SERVER 配置到客户端，
	 * 所以在客户端调用此方法时，配置应该已经加载（玩家加入后）。
	 * 如果配置未加载（如客户端刚启动尚未连接服务端），默认返回 true（启用状态），
	 * 这是为了在单人游戏或配置同步前保持向后兼容。
	 *
	 * @return true 如果万象创世蜜蜂功能已启用
	 */
	public static boolean isMyriadCreationsEnabled() {
		// 配置未加载时默认启用（向后兼容）
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			return true;
		}
		return ModConfig.SERVER.myriadCreationsEnabled.get();
	}

	/**
	 * 检查是否为万象创世蜜蜂类型
	 *
	 * @param beeType 蜜蜂类型 ResourceLocation
	 * @return true 如果是万象创世蜜蜂类型
	 */
	public static boolean isMyriadCreationsBeeType(ResourceLocation beeType) {
		return beeType != null && PBConstants.MYRIADCREATIONS_TYPE.equals(beeType);
	}

	/**
	 * 检查是否为万象创世蜜脾
	 * <p>
	 * 万象创世蜜脾使用PB的CONFIGURABLE_HONEYCOMB + bee_type=myriadcreations，
	 * 需要同时检查物品和bee_type数据组件。
	 * <p>
	 * 当配置 {@code myriadCreationsEnabled} 为 false 时，始终返回 false，
	 * 禁用万象创世蜜蜂的所有功能。
	 */
	public static boolean isMyriadCreationsHoneycomb(ItemStack stack) {
		if (!isMyriadCreationsEnabled()) return false;
		if (stack == null || stack.isEmpty()) return false;
		try {
			if (stack.getItem() == ModItems.CONFIGURABLE_HONEYCOMB.get()) {
				ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
				return isMyriadCreationsBeeType(beeType);
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("检查蜜脾类型时发生错误", e);
		}
		return false;
	}

	/**
	 * 检查是否为万象创世蜜脾块
	 * <p>
	 * 当配置 {@code myriadCreationsEnabled} 为 false 时，始终返回 false。
	 */
	public static boolean isMyriadCreationsCombBlock(ItemStack stack) {
		if (!isMyriadCreationsEnabled()) return false;
		if (stack == null || stack.isEmpty()) return false;
		try {
			if (stack.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
				return isMyriadCreationsBeeType(beeType);
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
	 * 空转拦截统一检查方法
	 * <p>
	 * 当输入为任意可配置蜜脾/蜜脾块且输出槽完全无空间时返回true。
	 *
	 * @param handler 物品处理器
	 * @return 是否应阻止机器运行
	 */
	public static boolean shouldBlockOperation(IItemHandlerModifiable handler) {
		return CombBlockCheckCache.checkBlockOperation(handler, MyriadCreationsEventHandler::isConfigurableItem, BLOCK_CHECK_CACHES);
	}

	/** 判断物品是否为可配置蜜脾或蜜脾块（用于空转拦截） */
	private static boolean isConfigurableItem(Item item) {
		return item == ModItems.CONFIGURABLE_HONEYCOMB.get()
				|| item == ModItems.CONFIGURABLE_COMB_BLOCK.get();
	}

	/**
	 * 离心机追加随机蜜脾产出（万象核心机制：转化）
	 * <p>
	 * 每个输入的万象创世蜜脾精确转化为1个随机蜜脾（线性缩放）。
	 *
	 * @param input              输入物品
	 * @param invHandler         物品处理器
	 * @param random             随机源
	 * @param productivityModifier PB升级倍率（无升级=1，Omega=32）
	 */
	public static void appendRandomCombs(ItemStack input, IItemHandlerModifiable invHandler, RandomSource random, int productivityModifier) {
		appendRandomCombsInternal(
				input, invHandler, random, productivityModifier,
				MyriadCreationsEventHandler::isMyriadCreationsHoneycomb,
				MyriadCreationsEventHandler::isMyriadCreationsCombBlock,
				CACHED_BEE_TYPES);
	}

	/**
	 * 从蜜蜂缓存中随机选取指定数量的不同类型
	 * <p>
	 * 委托给基类方法，保持public static API兼容。
	 */
	public static List<ResourceLocation> selectDistinctBeeTypes(int count, RandomSource random) {
		return RandomHoneycombSelector.selectDistinctBeeTypes(count, random, CACHED_BEE_TYPES);
	}

	/**
	 * 带缓存的随机类型选择（Task 23）
	 * <p>
	 * 在工厂 256x 高倍加速、每 tick 多次完成万象创世配方的场景下，
	 * 原 {@link #selectDistinctBeeTypes(int, RandomSource)} 会被高频调用，
	 * 产生大量 {@link HashSet} 分配与随机数计算。此缓存按
	 * {@code (count, gameTime, beeTypesVersion)} 复用结果，将同 tick 同 count 的
	 * 多次选择合并为一次随机采样，显著降低 CPU 占用。
	 * <p>
	 * 随机性影响：同一游戏刻内相同 {@code count} 的多次完成使用同一类型集合；
	 * 下一游戏刻会重新随机，长期分布基本不变，且更有利于同类型堆叠。
	 * <p>
	 * 实现已抽取到 {@link MyriadSelectionCache}，本方法仅做委托转发，保持 public static API 兼容。
	 *
	 * @param count 需要选取的类型数
	 * @param level 世界（用于获取当前游戏刻与随机源）
	 * @return 选中的蜜蜂类型列表
	 */
	public static List<ResourceLocation> selectDistinctBeeTypesCached(int count, Level level) {
		return MyriadSelectionCache.selectDistinctBeeTypesCached(count, level, CACHED_BEE_TYPES);
	}

	/**
	 * 将 total 均匀分配到各蜜蜂类型上
	 * <p>
	 * 委托给 {@link RandomHoneycombSelector}，保持public static API兼容。
	 */
	public static Map<ResourceLocation, Integer> allocateEvenly(int total, List<ResourceLocation> types) {
		return RandomHoneycombSelector.allocateEvenly(total, types);
	}

	/**
	 * 将 total 随机分配到各蜜蜂类型上（允许某些类型为0）
	 * <p>
	 * 委托给 {@link RandomHoneycombSelector}，保持public static API兼容。
	 */
	public static Map<ResourceLocation, Integer> allocateRandomly(int total, List<ResourceLocation> types, RandomSource random) {
		return RandomHoneycombSelector.allocateRandomly(total, types, random);
	}

	/**
	 * 检查离心机输出槽是否有剩余空间
	 * <p>
	 * 委托给 {@link CombBlockCheckCache}，保持public static API兼容。
	 */
	public static boolean hasOutputSpace(IItemHandlerModifiable invHandler) {
		return CombBlockCheckCache.hasOutputSpace(invHandler);
	}

	// ========== MEK 离心机万象创世专用辅助方法 ==========

	/**
	 * 判断两个物品是否为相同的 bee_type（均须为可配置蜜脾/蜜脾块）
	 *
	 * @param a 物品A
	 * @param b 物品B
	 * @return true 如果均为非空且 bee_type 相同
	 */
	public static boolean isSameBeeType(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty()) return false;
		ResourceLocation typeA = getBeeTypeFromStack(a);
		ResourceLocation typeB = getBeeTypeFromStack(b);
		return typeA != null && typeA.equals(typeB);
	}

	/** 从可配置蜜脾/蜜脾块中提取 bee_type */
	private static ResourceLocation getBeeTypeFromStack(ItemStack stack) {
		if (stack.isEmpty()) return null;
		if (isConfigurableHoneycomb(stack) || isConfigurableCombBlock(stack)) {
			return stack.get(ModDataComponents.BEE_TYPE.get());
		}
		return null;
	}
}
