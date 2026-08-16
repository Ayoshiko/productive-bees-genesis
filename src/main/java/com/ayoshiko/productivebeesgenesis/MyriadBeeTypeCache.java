package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.CompiledBeeTypeFilter;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
	 * 万象创世蜜蜂类型缓存管理器
	 * <br/>
	 * 从 {@link MyriadCreationsEventHandler} 抽离，负责：
	 * <ul>
	 *   <li>蜜蜂类型缓存的定期刷新（从 PB 数据源读取，应用配置过滤）</li>
	 *   <li>预构建蜜脾/蜜脾块模板数组（避免高频路径重复构造 ItemStack）</li>
	 *   <li>缓存的失效与清理（服务器停止、配置重载时）</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：
	 * <ul>
	 *   <li>{@link #beeTypeCacheSnapshot} 为 volatile 引用，通过不可变 {@link BeeTypeCacheSnapshot} 原子替换</li>
	 *   <li>读写模式：服务端 tick 线程单写，GUI 线程/Mixin 线程多读</li>
	 * </ul>
	 */
public final class MyriadBeeTypeCache {

	/**
	 * 不可变快照：封装蜜蜂类型缓存和预构建模板数组
	 * <p>
	 * 通过单一 volatile 引用原子替换，避免多 volatile 字段在 rebuild 期间出现
	 * "类型已更新但模板仍为旧值"的不一致状态。
	 * <p>
	 * <b>不可变性约束</b>：
	 * <ul>
	 * <li>{@code beeTypes} 为 {@link List} 类型，EMPTY 快照使用 {@link List#of()} 真正不可变</li>
	 * <li>实际发布时使用 {@link List#copyOf(java.util.Collection)}，防止调用方修改共享类型表</li>
	 * <li>{@code honeycombTemplates} / {@code combBlockTemplates} 为 {@link ItemStack} 数组，
	 *       <b>调用方不得修改数组元素</b>，必须通过 {@link ItemStack#copy()} 获取独立副本后再修改</li>
	 * <li>{@code honeycombTemplateByType} / {@code combBlockTemplateByType} 为 immutable Map，
	 *       提供 O(1) ResourceLocation → ItemStack 查找，避免 generateAggregatedStacks 中
	 *       O(N) 线性扫描 templates 数组（Spark 显示此处 5.13ms 热点）</li>
	 * </ul>
	 */
	public record BeeTypeCacheSnapshot(
			List<ResourceLocation> beeTypes,
			ItemStack[] honeycombTemplates,
			ItemStack[] combBlockTemplates,
			Map<ResourceLocation, ItemStack> honeycombTemplateByType,
			Map<ResourceLocation, ItemStack> combBlockTemplateByType) {
		// EMPTY 使用 List.of()，避免共享可变列表实例。
		static final BeeTypeCacheSnapshot EMPTY = new BeeTypeCacheSnapshot(
				List.of(), new ItemStack[0], new ItemStack[0], Map.of(), Map.of());
	}

	/** 当前快照 — volatile 引用保证原子替换 */
	private static volatile BeeTypeCacheSnapshot beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;

	/**
	 * 预热完成标志
	 * <br/>
	 * {@code false} 时 {@link #onServerTick()} 每 tick 都触发缓存构建尝试；
	 * {@code true} 时不再周期扫描，直到配置或数据重载调用 {@link #invalidate()}。
	 * <p>
	 * BeeReloadListener 数据可用后，无论过滤结果是否为空，都发布快照并完成预热。
	 */
	private static volatile boolean warmupComplete = false;

	/** "缓存未就绪"日志冷却器（info 级别，5 秒冷却） */
	private static final LogThrottle cacheNotReadyThrottle = new LogThrottle(100L);

	/** "空白名单"日志冷却器（info 级别，5 秒冷却） */
	private static final LogThrottle emptyWhitelistThrottle = new LogThrottle(100L);

	/** "过滤结果为空"日志冷却器（warn 级别，10 秒冷却） */
	private static final LogThrottle configFilterThrottle = new LogThrottle(200L);

	private MyriadBeeTypeCache() {}

	/** 读取当前快照（volatile 读保证可见性） */
	public static BeeTypeCacheSnapshot snapshot() {
		return beeTypeCacheSnapshot;
	}

	/**
	 * 兼容性访问：返回当前的蜜蜂类型列表
	 * <br/>
	 * 返回的列表不可修改：EMPTY 快照返回 {@link List#of()}（不可变），
	 * 实际缓存也通过 {@link List#copyOf(java.util.Collection)} 发布为不可变列表。
	 */
	public static List<ResourceLocation> cachedBeeTypes() {
		return snapshot().beeTypes;
	}

	/**
	 * 兼容性访问：返回当前的蜜脾模板数组
	 * <br/>
	 * <b>调用方必须通过 {@link ItemStack#copy()} 获取独立副本后再修改</b>，
	 * 直接修改数组元素会污染缓存模板，导致后续所有 copy() 携带错误数据。
	 */
	public static ItemStack[] cachedHoneycombTemplates() {
		return snapshot().honeycombTemplates;
	}

	/**
	 * 兼容性访问：返回当前的蜜脾块模板数组
	 * <br/>
	 * <b>调用方必须通过 {@link ItemStack#copy()} 获取独立副本后再修改</b>，
	 * 直接修改数组元素会污染缓存模板，导致后续所有 copy() 携带错误数据。
	 */
	public static ItemStack[] cachedCombBlockTemplates() {
		return snapshot().combBlockTemplates;
	}

	/**
	 * 服务器 tick — 检查是否有事件驱动的重建请求
	 * <br/>
	 * 服务器启动后首次获得 BeeReloadListener 数据前，每 tick 都触发更新尝试。
	 * 数据就绪并发布结果后返回 {@code false}，因此正常蜂箱工作期间不会读取配置、
	 * 扫描蜜蜂注册数据或查询配方。配置、标签或配方重载会调用 {@link #invalidate()}
	 * 重新进入待构建状态。
	 *
	 * @return true 如果本次 tick 触发了缓存更新检查（无论是否实际重建）
	 */
	public static boolean onServerTick() {
		return !warmupComplete;
	}

	/**
	 * 查询预热阶段是否完成
	 * <br/>
	 * 供 {@link MyriadCreationsEventHandler#isBeeTypeCacheWarmupComplete()} 转发，
	 * 用于在缓存为空时区分"蜜蜂数据未就绪"与"已发布的空过滤结果"。
	 *
	 * @return true 如果预热阶段已完成
	 */
	public static boolean isWarmupComplete() {
		return warmupComplete;
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
	 * <b>预热与空缓存区分</b>：
	 * <ul>
	 *   <li>BeeReloadListener 未加载 — info 日志"缓存未就绪"，每 tick 重试，不更新 snapshot</li>
	 *   <li>BeeReloadListener 已加载 — 发布最新快照；合法的空过滤结果同样会覆盖旧快照</li>
	 * </ul>
	 *
	 * @param level 服务端世界
	 */
	public static void updateBeeTypeCache(ServerLevel level) {
		long currentTick = level.getGameTime();
		if (!AbstractCombEventHandler.isBeeReloadListenerReady()) {
			cacheNotReadyThrottle.tryLog(currentTick, suppressed ->
					DevLog.info("bee_cache", "蜜蜂数据尚未就绪，万象创世类型缓存将在下一 tick 重试"
							+ "（抑制 {} 次类似日志）", suppressed));
			return;
		}

		Set<ResourceLocation> excluded = Set.of(PBConstants.MYRIADCREATIONS_TYPE);

		// 每次重建只编译一次规则，统一处理 TOML 中的空格、重复项与空列表语义。
		ModConfig.FilterMode mode = ModConfig.SERVER.myriadCreationsFilterMode.get();
		List<? extends String> filteredList = ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get();
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile(mode.name(), filteredList);

		List<ResourceLocation> newCache = AbstractCombEventHandler.buildBeeTypeCache(
				level, excluded, beeType -> filter.allows(beeType.toString()));

		if (newCache.isEmpty()) {
			logEmptyResult(filter, currentTick);
		}
		publishSnapshot(newCache);
	}

	/** 原子发布类型、模板和索引；空列表也必须覆盖旧快照并通知下游缓存。 */
	private static void publishSnapshot(List<ResourceLocation> beeTypes) {
		List<ResourceLocation> immutableTypes = List.copyOf(beeTypes);
		if (immutableTypes.isEmpty()) {
			beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;
		} else {
			// 高倍加速下通过模板 copy 替代重复构造 ItemStack，降低 GC 压力。
			ItemStack[] newHoneycombTemplates = RandomHoneycombSelector.buildHoneycombTemplates(immutableTypes);
			ItemStack[] newCombBlockTemplates = RandomHoneycombSelector.buildCombBlockTemplates(immutableTypes);
			Map<ResourceLocation, ItemStack> honeycombByType = new HashMap<>(immutableTypes.size() * 2);
			for (int i = 0; i < immutableTypes.size(); i++) {
				honeycombByType.put(immutableTypes.get(i), newHoneycombTemplates[i]);
			}
			Map<ResourceLocation, ItemStack> combBlockByType = new HashMap<>(immutableTypes.size() * 2);
			for (int i = 0; i < immutableTypes.size(); i++) {
				combBlockByType.put(immutableTypes.get(i), newCombBlockTemplates[i]);
			}
			beeTypeCacheSnapshot = new BeeTypeCacheSnapshot(
					immutableTypes, newHoneycombTemplates, newCombBlockTemplates,
					Map.copyOf(honeycombByType), Map.copyOf(combBlockByType));
		}

		warmupComplete = true;
		MyriadSelectionCache.onBeeTypesUpdated();
		WeightedTypeSelector.getInstance().onTypesUpdated(immutableTypes);
	}

	private static void logEmptyResult(CompiledBeeTypeFilter filter, long currentTick) {
		if (filter.isEmptyWhitelist()) {
			emptyWhitelistThrottle.tryLog(currentTick, suppressed ->
					DevLog.info("bee_cache", "万象创世白名单为空，已发布空类型缓存"
							+ "（抑制 {} 次类似日志）", suppressed));
			return;
		}
		configFilterThrottle.tryLog(currentTick, suppressed ->
				DevLog.warn("bee_cache", "万象创世过滤后没有可转化蜜蜂（mode={}, filterCount={}）"
							+ "，已发布空类型缓存（抑制 {} 次类似警告）",
						filter.modeName(), filter.entryCount(), suppressed));
	}

	/**
	 * 失效缓存（配置重载时调用）
	 * <br/>
	 * 使基于配置过滤的蜜蜂类型缓存立即失效，下次 tick 强制重建。
	 * <p>
	 * 同时清空已发布快照与下游选择器，使收紧后的规则不会继续使用旧类型；
	 * 下一 tick 起重试，直到 BeeReloadListener 数据就绪并发布新结果。
	 */
	public static void invalidate() {
		clearPublishedSnapshot();
		warmupComplete = false;
	}

	/**
	 * 清理所有缓存（服务器停止时调用）
	 * <br/>
	 * 重置所有静态字段到初始状态，防止跨存档数据泄漏。
	 */
	public static void clearAll() {
		clearPublishedSnapshot();
		warmupComplete = false;
	}

	/** Clears every published view before a rebuild so no consumer can use stale types. */
	private static void clearPublishedSnapshot() {
		beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;
		MyriadSelectionCache.invalidate();
		WeightedTypeSelector.getInstance().onTypesUpdated(List.of());
	}
}
