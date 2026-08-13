package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
	 *   <li>{@link #CACHE_VALID} 和 {@link #LAST_CACHE_UPDATE_TICK} 使用 AtomicBoolean/AtomicInteger</li>
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
	 * <li>实际构建时使用 {@link CopyOnWriteArrayList}（线程安全遍历），赋值给 List 字段</li>
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
		// EMPTY 使用 List.of() 真正不可变，避免共享可变 CopyOnWriteArrayList 实例
		static final BeeTypeCacheSnapshot EMPTY = new BeeTypeCacheSnapshot(
				List.of(), new ItemStack[0], new ItemStack[0], Map.of(), Map.of());
	}

	/** 当前快照 — volatile 引用保证原子替换 */
	private static volatile BeeTypeCacheSnapshot beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;

	private static final AtomicBoolean CACHE_VALID = new AtomicBoolean(false);
	private static final AtomicInteger LAST_CACHE_UPDATE_TICK = new AtomicInteger(0);

	/**
	 * 预热完成标志（SubTask 1.3）
	 * <br/>
	 * {@code false} 时 {@link #onServerTick()} 每 tick 都触发缓存构建尝试；
	 * {@code true} 时切换为 {@link AbstractCombEventHandler#CACHE_UPDATE_INTERVAL} 常规间隔。
	 * <p>
	 * 预热完成条件：
	 * <ul>
	 *   <li>缓存成功构建（非空）— 正常切换到常规间隔</li>
	 *   <li>BeeReloadListener 已加载但缓存持续为空超过 {@link #WARMUP_RETRY_THRESHOLD} 次
	 *       — 判定为配置过滤过严，停止重试等待配置重载</li>
	 * </ul>
	 */
	private static volatile boolean warmupComplete = false;

	/** 预热阶段已尝试的次数（缓存为空时递增，成功时重置） */
	private static final AtomicInteger warmupAttempts = new AtomicInteger(0);

	/**
	 * 预热重试阈值 — 超过此值后 BeeReloadListener 已加载但缓存仍为空时判定为配置过滤过严
	 * <br/>
	 * 100 tick = 5 秒（20 tps），覆盖配方加载延迟与 TagsUpdatedEvent 完成时间。
	 */
	private static final int WARMUP_RETRY_THRESHOLD = 100;

	/** "缓存未就绪"日志冷却器（info 级别，5 秒冷却） */
	private static final LogThrottle cacheNotReadyThrottle = new LogThrottle(100L);

	/** "配置过滤过严"日志冷却器（warn 级别，10 秒冷却） */
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
	 * 实际缓存返回 {@link CopyOnWriteArrayList}（线程安全但可变）。
	 * 调用方应仅遍历读取，不得调用 add/remove/set 等修改方法。
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
	 * 服务器 tick — 检查是否需要更新缓存
	 * <br/>
	 * 每 {@link AbstractCombEventHandler#CACHE_UPDATE_INTERVAL} tick 更新一次。
	 * <p>
	 * <b>预热阶段（SubTask 1.3）</b>：服务器启动后首次成功构建缓存前，每 tick 都触发更新尝试，
	 * 不等待常规间隔（100 tick）。首次构建失败时持续每 tick 重试直到成功或判定为配置过滤过严。
	 * <p>
	 * 原子性：使用 {@link AtomicInteger#getAndUpdate} 实现"递增并按需重置"原子操作，
	 * 避免 incrementAndGet + set 之间的竞态窗口。
	 *
	 * @return true 如果本次 tick 触发了缓存更新检查（无论是否实际重建）
	 */
	public static boolean onServerTick() {
		// 预热阶段：每 tick 都触发缓存构建尝试，不等 20 tick 常规间隔
		if (!warmupComplete) {
			return true;
		}
		// getAndUpdate 原子地递增并在达到阈值时重置为 0，避免 incrementAndGet + set 的非原子窗口
		return LAST_CACHE_UPDATE_TICK.getAndUpdate(
				curr -> curr >= AbstractCombEventHandler.CACHE_UPDATE_INTERVAL ? 0 : curr + 1)
				>= AbstractCombEventHandler.CACHE_UPDATE_INTERVAL;
	}

	/**
	 * 查询预热阶段是否完成（SubTask 1.2 + 1.4）
	 * <br/>
	 * 供 {@link MyriadCreationsEventHandler#isBeeTypeCacheWarmupComplete()} 转发，
	 * 用于在缓存为空时区分"缓存未就绪"（info）与"配置过滤过严"（warn）两种日志级别。
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
	 * <b>预热与空缓存区分（SubTask 1.3 + 1.4）</b>：
	 * <ul>
	 *   <li>BeeReloadListener 未加载 — info 日志"缓存未就绪"，每 tick 重试，不更新 snapshot</li>
	 *   <li>BeeReloadListener 已加载但缓存为空且超过 {@link #WARMUP_RETRY_THRESHOLD} 次 —
	 *       warn 日志"配置过滤过严"，标记预热完成停止重试</li>
	 *   <li>缓存非空 — 标记预热完成，切换到常规 20 tick 间隔</li>
	 * </ul>
	 *
	 * @param level 服务端世界
	 */
	public static void updateBeeTypeCache(ServerLevel level) {
		Set<ResourceLocation> excluded = new HashSet<>();
		excluded.add(PBConstants.MYRIADCREATIONS_TYPE);

		// 预先读取配置并构建 filterSet，避免在 Predicate 中对每个蜜蜂类型重复分配
		ModConfig.FilterMode mode = ModConfig.SERVER.myriadCreationsFilterMode.get();
		List<? extends String> filteredList = ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get();
		Set<String> filterSet = filteredList.isEmpty() ? Set.of() : new HashSet<>(filteredList);

		// buildBeeTypeCache 返回 List<ResourceLocation>（实际为 CopyOnWriteArrayList，保证读安全遍历），
		// 通过 List 接口持有引用，避免暴露实现细节，便于未来替换为其他线程安全列表实现
		List<ResourceLocation> newCache = AbstractCombEventHandler.buildBeeTypeCache(
				level, excluded, beeType -> applyConfigFilter(beeType, mode, filterSet));

		long currentTick = level.getGameTime();

		if (newCache.isEmpty()) {
			// SubTask 1.3 + 1.4：区分两种空原因
			handleEmptyCache(mode, filterSet.size(), currentTick);
			return;
		}

		// 成功路径：缓存非空，预构建模板数组与索引 Map
		// 同步预构建模板数组，256倍加速下通过copy()替代new ItemStack+set组件，显著降低GC压力
		ItemStack[] newHoneycombTemplates = RandomHoneycombSelector.buildHoneycombTemplates(newCache);
		ItemStack[] newCombBlockTemplates = RandomHoneycombSelector.buildCombBlockTemplates(newCache);
		// 同步构建 ResourceLocation → ItemStack 索引 Map，供 generateAggregatedStacks O(1) 查找
		// 替代原 O(N) findTemplate 线性扫描（Spark 显示此处 5.13ms 热点）
		Map<ResourceLocation, ItemStack> honeycombByType = new HashMap<>(newCache.size() * 2);
		for (int i = 0; i < newCache.size(); i++) {
			honeycombByType.put(newCache.get(i), newHoneycombTemplates[i]);
		}
		Map<ResourceLocation, ItemStack> combBlockByType = new HashMap<>(newCache.size() * 2);
		for (int i = 0; i < newCache.size(); i++) {
			combBlockByType.put(newCache.get(i), newCombBlockTemplates[i]);
		}
		// 原子替换：使用不可变快照封装五个相关字段，保证读线程看到一致状态
		// newCache 为 CopyOnWriteArrayList（线程安全遍历），作为 List 字段赋值
		beeTypeCacheSnapshot = new BeeTypeCacheSnapshot(
				newCache, newHoneycombTemplates, newCombBlockTemplates,
				Map.copyOf(honeycombByType), Map.copyOf(combBlockByType));
		CACHE_VALID.set(true);
		// SubTask 1.3：缓存成功构建，标记预热完成，切换到常规 20 tick 间隔
		warmupComplete = true;
		warmupAttempts.set(0);
		// Task 23: 递增版本号使类型选择缓存自动失效
		MyriadSelectionCache.onBeeTypesUpdated();
		// Task 1: 同步更新 WeightedTypeSelector 的类型表与权重数组
		WeightedTypeSelector.getInstance().onTypesUpdated(newCache);
	}

	/**
	 * 处理缓存为空的情况 — 区分"缓存未就绪"与"配置过滤过严"（SubTask 1.3 + 1.4）
	 * <br/>
	 * <ul>
	 *   <li>BeeReloadListener 未加载（{@code beeDataReady == false}）：
	 *       info 日志"缓存未就绪"，递增 warmupAttempts，不更新 snapshot，下 tick 重试</li>
	 *   <li>BeeReloadListener 已加载但 newCache 为空：
	 *       <ul>
	 *         <li>尝试次数 &lt; {@link #WARMUP_RETRY_THRESHOLD}：静默重试（配方可能仍在加载）</li>
	 *         <li>尝试次数 ≥ {@link #WARMUP_RETRY_THRESHOLD}：warn 日志"配置过滤过严"，
	 *             标记 warmupComplete 停止重试，等待配置重载触发 invalidate 重新预热</li>
	 *       </ul>
	 *   </li>
	 * </ul>
	 *
	 * @param mode          配置过滤模式
	 * @param filterCount   过滤列表大小
	 * @param currentTick   当前游戏刻
	 */
	private static void handleEmptyCache(ModConfig.FilterMode mode,
			int filterCount, long currentTick) {
		int attempts = warmupAttempts.incrementAndGet();
		boolean beeDataReady = AbstractCombEventHandler.isBeeReloadListenerReady();

		if (!beeDataReady) {
			// 缓存未就绪 — BeeReloadListener 未加载或配方未就绪，短暂状态
			return;
		}

		// beeData 已就绪但 newCache 为空 — 可能为配方未完全加载或配置过滤过严
		if (attempts < WARMUP_RETRY_THRESHOLD) {
			// 短时间内可能配方仍在加载，静默重试
			return;
		}

		// 超过阈值仍为空 — 判定为配置过滤过严
		configFilterThrottle.tryLog(currentTick, suppressed ->
				DevLog.warn("bee_cache",
						"万象创世类型缓存持续为空，可能是配置过滤过严（mode={}, filterCount={}）"
								+ "，已尝试 {} 次后停止重试（抑制 {} 次类似警告）。"
								+ "请检查 myriadCreationsFilteredBeeTypes 配置，或重载配置触发重建",
						mode, filterCount, attempts, suppressed));
		// 标记预热完成以停止每 tick 重试，等待配置重载触发 invalidate 重新预热
		warmupComplete = true;
	}

	/**
	 * 配置过滤谓词 — 根据配置的黑白名单模式过滤
	 * <p>
	 * DISABLED — 不过滤（默认）
	 * BLACKLIST — 排除列表中的蜜蜂类型
	 * WHITELIST — 仅保留列表中的蜜蜂类型
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

	/**
	 * 失效缓存（配置重载时调用）
	 * <br/>
	 * 使基于配置过滤的蜜蜂类型缓存立即失效，下次 tick 强制重建。
	 * <p>
	 * SubTask 1.3：同时重置预热状态，使配置重载后重新进入预热阶段
	 * （每 tick 重试直到新配置下的缓存构建成功或判定为配置过滤过严）。
	 */
	public static void invalidate() {
		CACHE_VALID.set(false);
		warmupComplete = false;
		warmupAttempts.set(0);
		MyriadSelectionCache.invalidate();
		// Task 1: 同步失效 WeightedTypeSelector 的 tick 缓存
		WeightedTypeSelector.getInstance().invalidate();
	}

	/**
	 * 清理所有缓存（服务器停止时调用）
	 * <br/>
	 * 重置所有静态字段到初始状态，防止跨存档数据泄漏。
	 */
	public static void clearAll() {
		CACHE_VALID.set(false);
		beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;
		LAST_CACHE_UPDATE_TICK.set(0);
		warmupComplete = false;
		warmupAttempts.set(0);
		MyriadSelectionCache.invalidate();
		// Task 1: 同步失效 WeightedTypeSelector 的 tick 缓存
		WeightedTypeSelector.getInstance().invalidate();
	}
}
