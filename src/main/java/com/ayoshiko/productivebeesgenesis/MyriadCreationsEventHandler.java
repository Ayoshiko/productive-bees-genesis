package com.ayoshiko.productivebeesgenesis;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
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
 *   <li>蜜蜂类型缓存管理（定期从PB数据源刷新，应用配置过滤）— 委托给 {@link MyriadBeeTypeCache}</li>
 *   <li>随机蜜脾/蜜脾块生成（供Mixin调用）— 委托给 {@link RandomHoneycombSelector}</li>
 *   <li>离心机追加产出逻辑 + 空转拦截 — 委托给 {@link CombBlockCheckCache}</li>
 * </ol>
 * <p>
 * 公共逻辑继承自 {@link AbstractCombEventHandler}，本类仅保留万象创世特有的：
 * <ul>
 *   <li>配置文件黑白名单过滤（在 {@link MyriadBeeTypeCache} 中实现）</li>
 *   <li>基于 bee_type 数据组件的类型判断</li>
 * </ul>
 * <p>
 * 注：万象创世蜜蜂使用 PB 的 configurable_honeycomb + bee_type 数据组件携带类型信息，
 * 不自动生成 configurable_honeycomb（createComb: false），与原 PB 体系兼容。
 * <p>
 * <b>职责分离</b>（v1.7.0 拆分）：
 * <ul>
 *   <li>{@link MyriadBeeTypeCache} — 蜜蜂类型缓存生命周期管理</li>
 *   <li>{@link RandomHoneycombSelector} — 随机蜜脾/蜜脾块生成</li>
 *   <li>{@link MyriadSelectionCache} — 类型选择缓存</li>
 *   <li>{@link CombBlockCheckCache} — 空转拦截缓存</li>
 * </ul>
 * 本类仅作为事件订阅入口 + 公共 API 转发层。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class MyriadCreationsEventHandler extends AbstractCombEventHandler {

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

	// ========== 事件订阅 ==========

	/** 服务器tick事件 — 定期更新蜜蜂类型缓存 */
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		// 万象创世功能被禁用时，跳过缓存更新
		if (!isMyriadCreationsEnabled()) return;
		if (MyriadBeeTypeCache.onServerTick()) {
			MyriadBeeTypeCache.updateBeeTypeCache(event.getServer().overworld());
		}
	}

	/** 服务器停止事件 — 清理static缓存防止内存泄漏 */
	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		clearAllCaches();
	}

	/**
	 * 清理所有静态缓存 — 服务器停止或外部需要主动清理时调用
	 * <br/>
	 * 公开方法供 {@link ProductiveBeesGenesis#onServerStopped} 显式调用，
	 * 确保即使 @SubscribeEvent 注解失效（例如类未注册到事件总线）也能清理。
	 */
	public static void clearAllCaches() {
		CombBlockCheckCache.clearCaches(BLOCK_CHECK_CACHES);
		MyriadBeeTypeCache.clearAll();
	}

	/**
	 * 失效过滤缓存（Task 15）。
	 * <br/>
	 * 在 {@code ModConfigEvent.Reloading} 监听器中调用，使基于配置过滤的蜜蜂类型缓存
	 * 立即失效。配置重载后过滤模式或过滤列表可能变化，缓存中的蜜蜂类型集合已过期，
	 * 必须主动失效让下次 {@link #onServerTick} 重建缓存反映最新配置。
	 */
	public static void invalidateFilterCache() {
		MyriadBeeTypeCache.invalidate();
	}

	// ========== 随机蜜脾生成（委托给 RandomHoneycombSelector）==========

	/** 获取随机蜜脾（排除万象创世自身） */
	public static ItemStack getRandomHoneycomb() {
		return RandomHoneycombSelector.generateRandomHoneycomb(MyriadBeeTypeCache.cachedHoneycombTemplates());
	}

	/** 获取随机蜜脾块 */
	public static ItemStack getRandomCombBlock() {
		return RandomHoneycombSelector.generateRandomCombBlock(MyriadBeeTypeCache.cachedCombBlockTemplates());
	}

	/** 批量获取随机蜜脾 */
	public static List<ItemStack> getRandomHoneycombs(int count) {
		return RandomHoneycombSelector.generateRandomHoneycombs(count, MyriadBeeTypeCache.cachedHoneycombTemplates());
	}

	/** 批量获取随机蜜脾块 */
	public static List<ItemStack> getRandomCombBlocks(int count) {
		return RandomHoneycombSelector.generateRandomCombBlocks(count, MyriadBeeTypeCache.cachedCombBlockTemplates());
	}

	/** 批量追加随机蜜脾到输出列表 */
	public static void appendRandomHoneycombs(List<ItemStack> out, int count) {
		RandomHoneycombSelector.appendRandomHoneycombs(out, count, MyriadBeeTypeCache.cachedHoneycombTemplates());
	}

	/** 批量追加随机蜜脾块到输出列表 */
	public static void appendRandomCombBlocks(List<ItemStack> out, int count) {
		RandomHoneycombSelector.appendRandomCombBlocks(out, count, MyriadBeeTypeCache.cachedCombBlockTemplates());
	}

	/**
	 * 获取聚合后的随机蜜脾（最多 9 种类型，每种 1~2 个 stack）
	 */
	public static List<ItemStack> getAggregatedRandomHoneycombs(int totalCount, RandomSource random) {
		if (!isMyriadCreationsEnabled()) return List.of();
		MyriadBeeTypeCache.BeeTypeCacheSnapshot snap = MyriadBeeTypeCache.snapshot();
		return RandomHoneycombSelector.generateAggregatedStacks(
				totalCount,
				ModItems.CONFIGURABLE_HONEYCOMB.get(),
				snap.beeTypes(),
				snap.honeycombTemplates(),
				random);
	}

	/**
	 * 获取聚合后的随机蜜脾块（最多 9 种类型，每种 1~2 个 stack）
	 */
	public static List<ItemStack> getAggregatedRandomCombBlocks(int totalCount, RandomSource random) {
		if (!isMyriadCreationsEnabled()) return List.of();
		MyriadBeeTypeCache.BeeTypeCacheSnapshot snap = MyriadBeeTypeCache.snapshot();
		return RandomHoneycombSelector.generateAggregatedStacks(
				totalCount,
				ModItems.CONFIGURABLE_COMB_BLOCK.get(),
				snap.beeTypes(),
				snap.combBlockTemplates(),
				random);
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

	/** 检查物品是否为万象创世蜜脾或蜜脾块 */
	public static boolean isMyriadCreationsItem(ItemStack stack) {
		return isMyriadCreationsHoneycomb(stack) || isMyriadCreationsCombBlock(stack);
	}

	/** 检查万象创世蜜蜂功能是否启用（配置未加载时默认启用，向后兼容） */
	public static boolean isMyriadCreationsEnabled() {
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			return true;
		}
		return ModConfig.SERVER.myriadCreationsEnabled.get();
	}

	/** 检查是否为万象创世蜜蜂类型 */
	public static boolean isMyriadCreationsBeeType(ResourceLocation beeType) {
		return beeType != null && PBConstants.MYRIADCREATIONS_TYPE.equals(beeType);
	}

	/** 检查是否为万象创世蜜脾 */
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

	/** 检查是否为万象创世蜜脾块 */
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

	/** 判断两个物品是否为相同的 bee_type */
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

	// ========== 离心机公共逻辑（供Mixin调用）==========

	/** 空转拦截统一检查方法 */
	public static boolean shouldBlockOperation(IItemHandlerModifiable handler) {
		return CombBlockCheckCache.checkBlockOperation(handler, MyriadCreationsEventHandler::isConfigurableItem, BLOCK_CHECK_CACHES);
	}

	/** 判断物品是否为可配置蜜脾或蜜脾块（用于空转拦截） */
	private static boolean isConfigurableItem(Item item) {
		return item == ModItems.CONFIGURABLE_HONEYCOMB.get()
				|| item == ModItems.CONFIGURABLE_COMB_BLOCK.get();
	}

	/** 离心机追加随机蜜脾产出（万象核心机制：转化） */
	public static void appendRandomCombs(ItemStack input, IItemHandlerModifiable invHandler, RandomSource random, int productivityModifier) {
		appendRandomCombsInternal(
				input, invHandler, random, productivityModifier,
				MyriadCreationsEventHandler::isMyriadCreationsHoneycomb,
				MyriadCreationsEventHandler::isMyriadCreationsCombBlock,
				MyriadBeeTypeCache.cachedBeeTypes());
	}

	/** 从蜜蜂缓存中随机选取指定数量的不同类型 */
	public static List<ResourceLocation> selectDistinctBeeTypes(int count, RandomSource random) {
		return RandomHoneycombSelector.selectDistinctBeeTypes(count, random, MyriadBeeTypeCache.cachedBeeTypes());
	}

	/** 带缓存的随机类型选择（Task 23） */
	public static List<ResourceLocation> selectDistinctBeeTypesCached(int count, Level level) {
		return MyriadSelectionCache.selectDistinctBeeTypesCached(count, level, MyriadBeeTypeCache.cachedBeeTypes());
	}

	/** 将 total 均匀分配到各蜜蜂类型上 */
	public static Map<ResourceLocation, Integer> allocateEvenly(int total, List<ResourceLocation> types) {
		return RandomHoneycombSelector.allocateEvenly(total, types);
	}

	/** 将 total 随机分配到各蜜蜂类型上（允许某些类型为0） */
	public static Map<ResourceLocation, Integer> allocateRandomly(int total, List<ResourceLocation> types, RandomSource random) {
		return RandomHoneycombSelector.allocateRandomly(total, types, random);
	}

	/** 检查离心机输出槽是否有剩余空间 */
	public static boolean hasOutputSpace(IItemHandlerModifiable invHandler) {
		return CombBlockCheckCache.hasOutputSpace(invHandler);
	}
}
