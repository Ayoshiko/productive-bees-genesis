package com.ayoshiko.productivebeesgenesis;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * 万象创世蜜蜂类型选择缓存（Task 23 + Task 4 锁优化）
 * <p>
 * 从 {@link MyriadCreationsEventHandler} 抽取的类型选择缓存逻辑，遵循单一职责原则（SRP）：
 * <ul>
 *   <li>按 {@code (count, gameTime, beeTypesVersion)} 复用随机选择结果</li>
 *   <li>同 tick 同 count 的多次选择合并为一次随机采样，显著降低 CPU 占用</li>
 *   <li>缓存版本号在蜜蜂类型缓存更新或配置重载时递增，自动失效旧结果</li>
 * </ul>
 * <p>
 * <b>Task 4 锁优化</b>：移除 {@code synchronized(MyriadSelectionCache.class)} 类级锁，
 * 改为 volatile 字段双检查模式。服务端单线程执行实际无竞争，锁纯属性能损耗。
 * volatile 字段保证跨区块可见性，最坏情况下多线程可能重复计算（不导致数据损坏）。
 * <p>
 * <b>线程安全</b>：{@code BEE_TYPES_VERSION} 使用 {@link AtomicInteger}，
 * {@link SelectionCache} 字段为 volatile，多工厂实例共享静态数组时交叉访问安全。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class MyriadSelectionCache {

	/** 蜜蜂类型缓存版本号，每次成功更新 cachedBeeTypes 后递增 */
	private static final AtomicInteger BEE_TYPES_VERSION = new AtomicInteger(0);

	/** 每 count 每 tick 的类型选择缓存（工厂中 count 通常为 1–3，预留到 9 覆盖蜂箱聚合路径） */
	static final int MAX_SELECTION_CACHE = 9;
	private static final SelectionCache[] SELECTION_CACHES;

	static {
		SELECTION_CACHES = new SelectionCache[MAX_SELECTION_CACHE + 1];
		for (int i = 0; i < SELECTION_CACHES.length; i++) {
			SELECTION_CACHES[i] = new SelectionCache();
		}
	}

	private MyriadSelectionCache() {
		// 工具类禁止实例化
	}

	/** 类型选择缓存条目 */
	private static final class SelectionCache {
		// volatile 保证多工厂共享静态数组时的字段可见性
		// （SELECTION_CACHES 为所有工厂实例共享，不同 chunk/tick 调度下可能交叉访问同一索引位）
		// List.copyOf 返回的不可变列表本身线程安全，volatile 保证引用可见性
		volatile long cachedTick = -1L;
		volatile int cachedVersion = -1;
		volatile List<ResourceLocation> selected = List.of();
	}

	/**
	 * 蜜蜂类型缓存更新后调用 — 递增版本号使类型选择缓存自动失效。
	 * <p>
	 * 失效后下次 {@link #selectDistinctBeeTypesCached} 会重新随机采样。
	 */
	static void onBeeTypesUpdated() {
		BEE_TYPES_VERSION.incrementAndGet();
	}

	/**
	 * 失效所有类型选择缓存条目（配置重载时调用）。
	 * <p>
	 * 递增版本号并重置所有缓存条目的 tick/version/selected，强制下次随机采样重新执行。
	 * <p>
	 * <b>Task 4</b>：移除类级锁。服务端单线程执行下无并发问题。
	 * 同时清空 selected 列表：避免在版本号递增后，selectDistinctBeeTypesCached 的双重检查
	 * 命中旧 tick 但新 version 之前的过渡窗口读到已过期的 selected。
	 */
	static void invalidate() {
		BEE_TYPES_VERSION.incrementAndGet();
		// 重置类型选择缓存条目，强制下次 selectDistinctBeeTypesCached 重新随机采样
		for (SelectionCache entry : SELECTION_CACHES) {
			entry.cachedTick = -1L;
			entry.cachedVersion = -1;
			entry.selected = List.of();
		}
	}

	/**
	 * 带缓存的随机类型选择（Task 23 + Task 4 锁优化）
	 * <p>
	 * 在工厂 256x 高倍加速、每 tick 多次完成万象创世配方的场景下，
	 * 原 {@link MyriadCreationsEventHandler#selectDistinctBeeTypes(int, RandomSource)}
	 * 会被高频调用，产生大量 {@link java.util.HashSet} 分配与随机数计算。此缓存按
	 * {@code (count, gameTime, beeTypesVersion)} 复用结果，将同 tick 同 count 的
	 * 多次选择合并为一次随机采样，显著降低 CPU 占用。
	 * <p>
	 * 随机性影响：同一游戏刻内相同 {@code count} 的多次完成使用同一类型集合；
	 * 下一游戏刻会重新随机，长期分布基本不变，且更有利于同类型堆叠。
	 * <p>
	 * <b>Task 4</b>：移除类级锁，依赖 volatile 字段可见性。服务端单线程执行无竞争。
	 * 多线程最坏情况下可能重复执行预生成循环（不导致数据损坏）。
	 *
	 * @param count          需要选取的类型数
	 * @param level          世界（用于获取当前游戏刻与随机源）
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 选中的蜜蜂类型列表
	 */
	static List<ResourceLocation> selectDistinctBeeTypesCached(
			int count, Level level, List<ResourceLocation> cachedBeeTypes) {
		if (count <= 0 || level == null) {
			return List.of();
		}
		int poolSize = cachedBeeTypes.size();
		if (poolSize == 0) {
			return List.of();
		}
		if (count >= poolSize) {
			return List.copyOf(cachedBeeTypes);
		}
		if (count > MAX_SELECTION_CACHE) {
			// 超出缓存范围时回退到无缓存版本（防御性）
			return RandomHoneycombSelector.selectDistinctBeeTypes(count, level.getRandom(), cachedBeeTypes);
		}

		long currentTick = level.getGameTime();
		int currentVersion = BEE_TYPES_VERSION.get();
		SelectionCache entry = SELECTION_CACHES[count];
		// 双重检查：先尝试读（volatile 读保证可见性）
		if (entry.cachedTick == currentTick && entry.cachedVersion == currentVersion) {
			return entry.selected;
		}

		// 缓存失效时一次性预生成 1..MAX_SELECTION_CACHE 的候选列表，
		// 避免同一 tick 内多个 count 各自触发随机采样（高倍加速下仍可能每 tick 多次完成万象配方）
		// Task 4: 移除 synchronized，服务端单线程无竞争
		RandomSource random = level.getRandom();
		for (int i = 1; i <= MAX_SELECTION_CACHE; i++) {
			SelectionCache e = SELECTION_CACHES[i];
			if (poolSize <= i) {
				e.selected = List.copyOf(cachedBeeTypes);
			} else {
				e.selected = List.copyOf(RandomHoneycombSelector.selectDistinctBeeTypes(i, random, cachedBeeTypes));
			}
			e.cachedTick = currentTick;
			e.cachedVersion = currentVersion;
		}
		return entry.selected;
	}
}
