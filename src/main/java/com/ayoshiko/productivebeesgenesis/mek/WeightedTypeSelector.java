package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.ref.WeakReference;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 跨工厂共享的动态权重类型选择器（Task 1）
 * <p>
 * 替代 MyriadProductPool 的 200-tick 固定窗口，实现每 tick 动态权重选型。
 * 产出少的类型权重升高被优先选中，10 分钟内所有类型累计产出在 ±15% 范围内。
 * <p>
 * 选型算法：累积权重二分查找 + 位图去重（long[]，单次调用内去重，跨工厂允许重复）。
 * 工厂级调用计数缓存（WeakReference 弱引用 key，工厂卸载自动清理，避免内存泄漏）。
 * <p>
 * <b>线程安全</b>：服务端单线程执行，无锁竞争。volatile 字段保证跨区块可见性。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class WeightedTypeSelector {

	/** 单例 */
	private static final WeightedTypeSelector INSTANCE = new WeightedTypeSelector();

	/** 预留 200+ 类型上限 */
	static final int MAX_TYPES = 512;

	/** 权重表重建间隔（tick）— 1 秒 */
	static final int REBUILD_INTERVAL = 20;

	/** tickCache 死条目清理间隔（tick） — 每 200 tick 清理 WeakReference 已清除的死条目（修复 #5） */
	static final int CLEANUP_INTERVAL = 200;

	/**
	 * 工厂级调用计数缓存刷新间隔（Bug 2 修复）
	 * <br/>
	 * JDTE/JDT 加速模组下同一 gameTick 内多次调用 getGameTime() 返回相同值，导致 tick 缓存始终命中。
	 * 改用 AtomicLong 内部调用计数器，每 100 次调用刷新一次缓存。
	 */
	static final int CALL_COUNTER_REFRESH_INTERVAL = 100;

	/** EMA 窗口大小（对应 10 秒数据，匹配 ±15% / 10 分钟平衡目标） */
	static final int EMA_WINDOW = 200;

	/** 最小权重（防止单类型权重为 0 导致永不选中） */
	static final double MIN_WEIGHT = 0.1;

	/** 权重放大系数（产出少 50% 时权重翻倍） */
	static final double ALPHA = 2.0;

	/** 类型数组（与 typeIndex 同步更新） */
	private volatile ResourceLocation[] types = new ResourceLocation[0];

	/** 类型→索引映射（O(1) 查找，用于 recordOutput） */
	private volatile Map<ResourceLocation, Integer> typeIndex = Collections.emptyMap();

	/** 指数移动平均的近期产出量 */
	private volatile double[] recentEMA = new double[0];

	/** 当前权重值 */
	private volatile double[] weight = new double[0];

	/** 累积权重前缀和（用于二分查找） */
	private volatile double[] cumulativeWeight = new double[0];

	/** 每 tick 累加的实际产出（flush 时合并入 recentEMA） */
	private double[] pendingOutputs = new double[0];

	/** 类型缓存版本号 */
	private volatile int typesVersion = -1;

	/** 上次重建权重的 tick */
	private volatile long lastRebuildTick = -1L;

	/** 上次 flush EMA 的 tick */
	private volatile long lastFlushTick = -1L;

	/** 上次清理 tickCache 死条目的 tick — 修复 #5 */
	private volatile long lastCleanupTick = -1L;

	/**
	 * 工厂级调用计数缓存（Task 8 改造 + Bug 2 修复）
	 * <br/>
	 * 以 {@link System#identityHashCode(Object)} 为 key，O(1) 命中且无 hashCode 调用。
	 * 工厂实例被 GC 后 WeakReference 清除，{@code matches} 返回 false 自动覆盖。
	 * <p>
	 * Bug 2 修复：原使用 {@link Level#getGameTime()} 作为缓存有效期 key，
	 * JDTE/JDT 加速下同一 gameTick 内多次调用返回相同值导致缓存始终命中。
	 * 改用 {@link #callCounter}，每 {@link #CALL_COUNTER_REFRESH_INTERVAL} 次刷新。
	 */
	private final ConcurrentHashMap<Long, CachedSelection> tickCache = new ConcurrentHashMap<>();

	/** 内部调用计数器 — 替代 getGameTime() 作为缓存 key（Bug 2 修复） */
	private final AtomicLong callCounter = new AtomicLong(0);

	/** 降级日志冷却器 */
	private final LogThrottle degradeThrottle = new LogThrottle();

	private WeightedTypeSelector() {
	}

	public static WeightedTypeSelector getInstance() {
		return INSTANCE;
	}

	/**
	 * 类型缓存变更时调用 — 重建内部数组，重置 recentEMA
	 * <br/>
	 * 由 {@link com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache} 在缓存更新后触发。
	 * 内部递增版本号，下次 selectWeighted 时检测到版本变化重建权重表。
	 */
	public synchronized void onTypesUpdated(List<ResourceLocation> newTypes) {
		int newVersion = typesVersion + 1;
		int size = Math.min(newTypes.size(), MAX_TYPES);
		ResourceLocation[] newTypesArr = new ResourceLocation[size];
		Map<ResourceLocation, Integer> newIndex = new HashMap<>(size * 2);
		for (int i = 0; i < size; i++) {
			ResourceLocation t = newTypes.get(i);
			newTypesArr[i] = t;
			newIndex.put(t, i);
		}
		types = newTypesArr;
		typeIndex = Collections.unmodifiableMap(newIndex);
		recentEMA = new double[size];
		weight = new double[size];
		cumulativeWeight = new double[size];
		pendingOutputs = new double[size];
		// 初始权重全为 1.0（均匀分布）
		Arrays.fill(weight, 1.0);
		// 初始累积权重 [1, 2, 3, ...]
		for (int i = 0; i < size; i++) cumulativeWeight[i] = i + 1;
		typesVersion = newVersion;
		lastRebuildTick = -1L;
		lastFlushTick = -1L;
		lastCleanupTick = -1L;
		// 清空 tick 缓存（版本变化使旧缓存失效）
		tickCache.clear();
	}

	/**
	 * 每 tick 调用 — flush EMA + 必要时重建权重表
	 * <br/>
	 * 由 {@link #selectWeighted} 在每次调用时触发，保证 tick 内至少 flush 一次。
	 * <ul>
	 *   <li>flush EMA：每 tick 一次，公式 {@code recentEMA[i] = (recentEMA[i] * (EMA_WINDOW-1) + pendingOutputs[i]) / EMA_WINDOW}</li>
	 *   <li>重建权重：每 {@link #REBUILD_INTERVAL} tick 一次，公式 {@code weight[i] = max(MIN_WEIGHT, 1.0 + ALPHA * (avgRecent - recentEMA[i]) / max(avgRecent, 1.0))}</li>
	 * </ul>
	 */
	public synchronized void rebuildWeightsIfNeeded(Level level) {
		if (level == null) return;
		long currentTick = level.getGameTime();

		// 修复 #5：tickCache 死条目清理 — 每 CLEANUP_INTERVAL tick 清理 WeakReference 已清除的死条目
		if (lastCleanupTick == -1L || currentTick - lastCleanupTick >= CLEANUP_INTERVAL) {
			lastCleanupTick = currentTick;
			tickCache.entrySet().removeIf(e -> e.getValue().factoryRef.get() == null);
		}

		// flush EMA（每 tick 一次）
		if (lastFlushTick != currentTick) {
			double[] ema = recentEMA;
			double[] pend = pendingOutputs;
			if (ema.length > 0 && pend.length == ema.length) {
				for (int i = 0; i < ema.length; i++) {
					ema[i] = (ema[i] * (EMA_WINDOW - 1) + pend[i]) / EMA_WINDOW;
					pend[i] = 0.0;
				}
			}
			lastFlushTick = currentTick;
		}

		// 重建权重表（每 REBUILD_INTERVAL tick 一次）
		if (lastRebuildTick != -1L && currentTick - lastRebuildTick < REBUILD_INTERVAL) return;
		int size = types.length;
		if (size == 0) return;

		// 计算 avgRecent
		double[] ema = recentEMA;
		double sum = 0.0;
		for (int i = 0; i < size; i++) sum += ema[i];
		double avgRecent = sum / size;
		double maxAvg = Math.max(avgRecent, 1.0);

		// 计算权重 + 累积权重前缀和
		double[] w = weight;
		double[] cw = cumulativeWeight;
		double total = 0.0;
		for (int i = 0; i < size; i++) {
			double diff = (avgRecent - ema[i]) / maxAvg;
			double newWeight = 1.0 + ALPHA * diff;
			if (newWeight < MIN_WEIGHT) newWeight = MIN_WEIGHT;
			w[i] = newWeight;
			total += newWeight;
			cw[i] = total;
		}
		lastRebuildTick = currentTick;
	}

	/**
	 * 加权二分查找选型 + 位图去重 + 工厂级调用计数缓存
	 * <p>
	 * <b>工厂级缓存</b>：每 {@link #CALL_COUNTER_REFRESH_INTERVAL} 次调用刷新一次，同一工厂相同 {@code count} 的多次调用返回相同结果。
	 * <p>
	 * <b>退化安全</b>：异常时退化为 {@link RandomHoneycombSelector#selectDistinctBeeTypes}。
	 *
	 * @param count      需要选取的类型数
	 * @param level      世界（仍用于 rebuildWeightsIfNeeded 的 EMA flush）
	 * @param allTypes   候选蜜蜂类型列表
	 * @param factoryKey 工厂实例（WeakReference 弱引用 key）
	 * @return 选中的蜜蜂类型列表（不可变）
	 */
	public List<ResourceLocation> selectWeighted(int count, Level level,
			List<ResourceLocation> allTypes, Object factoryKey) {
		if (count <= 0 || allTypes == null || allTypes.isEmpty()) return List.of();
		int poolSize = allTypes.size();
		if (count >= poolSize) return List.copyOf(allTypes);

		rebuildWeightsIfNeeded(level);

		// 工厂级调用计数缓存命中检查（Bug 2 修复：用 callCounter 替代 getGameTime）
		long currentCallCount = -1L;
		if (factoryKey != null && level != null) {
			currentCallCount = callCounter.incrementAndGet();
			Long factoryId = (long) System.identityHashCode(factoryKey);
			CachedSelection cached = tickCache.get(factoryId);
			if (cached != null && cached.matches(count, currentCallCount, typesVersion, factoryKey)) {
				return cached.selected;
			}
		}

		// 实际选型
		List<ResourceLocation> result;
		try {
			result = doSelectWeighted(count, allTypes);
		} catch (Exception e) {
			logDegrade(level, "加权选型异常，退化为原版随机", e);
			result = RandomHoneycombSelector.selectDistinctBeeTypes(count, level.getRandom(), allTypes);
		}

		// 缓存结果（不可变副本）
		if (factoryKey != null && level != null) {
			Long factoryId = (long) System.identityHashCode(factoryKey);
			CachedSelection existing = tickCache.get(factoryId);
			// 修复 #15：identityHashCode 冲突时不同工厂实例不应互相驱逐缓存。
			// 若已存在条目但属于不同工厂实例（factoryRef 不指向当前 factoryKey），跳过缓存写入。
			// 性能降级（每次 miss）但不破坏对方缓存，避免两个工厂互相驱逐导致缓存永远失效。
			if (existing == null || existing.factoryRef.get() == factoryKey) {
				CachedSelection cs = new CachedSelection(factoryKey);
				cs.cachedCallCount = currentCallCount;
				cs.cachedCount = count;
				cs.cachedVersion = typesVersion;
				cs.selected = List.copyOf(result);
				tickCache.put(factoryId, cs);
			}
		}

		return result;
	}

	/**
	 * 工厂级共享选型 — 每进程分到独立 3 种类型（Task 5 SubTask 5.10）
	 * <br/>
	 * 内部调用 {@link #selectWeighted} 一次选 {@code processCount × 3} 种类型，按 {@code processIndex} 切片取 3 种。
	 * 候选不足时 round-robin 回绕，允许跨进程共享（退化场景）。
	 *
	 * @param processIndex  当前进程索引（0-based）
	 * @param processCount 进程总数（覆盖最高 19 进程 EM CREATIVE）
	 * @param level         世界
	 * @param allTypes      候选类型列表
	 * @param factoryKey    工厂实例（WeakReference 弱引用 key）
	 * @return 该进程的 3 种类型列表（不可变）
	 */
	public List<ResourceLocation> selectForProcess(int processIndex, int processCount,
			Level level, List<ResourceLocation> allTypes, Object factoryKey) {
		if (allTypes == null || allTypes.isEmpty()) return List.of();
		final int typesPerProcess = 3;
		int poolSize = allTypes.size();
		// 候选 ≤ 3 时每进程都拿全部(避免 selectWeighted(count >= poolSize) 返回全列表)
		if (poolSize <= typesPerProcess) return List.copyOf(allTypes);

		int totalNeeded = processCount * typesPerProcess;
		List<ResourceLocation> allSelected = selectWeighted(totalNeeded, level, allTypes, factoryKey);
		int available = allSelected.size();
		if (available == 0) return List.of();
		if (available <= typesPerProcess) return allSelected;

		// 切片:每进程取 [start, start+3), 超出范围时 round-robin 回绕
		int start = processIndex * typesPerProcess;
		List<ResourceLocation> result = new ArrayList<>(typesPerProcess);
		for (int i = 0; i < typesPerProcess; i++) {
			int idx = (start + i) % available;
			result.add(allSelected.get(idx));
		}
		return List.copyOf(result);
	}

	/**
	 * 累积权重二分查找 + 位图去重
	 * <br/>
	 * 读取累积权重数组（由 {@link #rebuildWeightsIfNeeded} 维护），用 long[] 位图追踪已选索引。
	 * 重复 count 次：生成 [0, totalCumulativeWeight) 随机数，二分查找第一个 cw[i] > random 的索引；
	 * 若已选则顺序向后扫描下一个未选索引。复杂度 O(count × log N)。
	 */
	private List<ResourceLocation> doSelectWeighted(int count, List<ResourceLocation> allTypes) {
		int poolSize = allTypes.size();
		double[] cw;
		boolean useExternalWeights = (types.length == poolSize);
		if (useExternalWeights) {
			// 检查 allTypes 是否与 types 完全一致（顺序与元素）
			for (int i = 0; i < poolSize; i++) {
				if (!allTypes.get(i).equals(types[i])) {
					useExternalWeights = false;
					break;
				}
			}
		}
		if (useExternalWeights) {
			cw = cumulativeWeight;
		} else {
			// allTypes 与 types 不匹配（如类型缓存刚更新但 WeightedTypeSelector 未同步）— 用均匀分布
			cw = buildUniformCumulative(poolSize);
		}

		// 位图去重
		long[] bitmap = new long[(poolSize + 63) >>> 6];
		List<ResourceLocation> selected = new ArrayList<>(count);
		ThreadLocalRandom random = ThreadLocalRandom.current();
		double maxWeight = cw.length > 0 ? cw[cw.length - 1] : 0.0;
		if (maxWeight <= 0.0) return selected;

		int picked = 0;
		int scanAttempts = 0;
		int maxAttempts = count * 4;
		while (picked < count && scanAttempts < maxAttempts) {
			scanAttempts++;
			double r = random.nextDouble(maxWeight);
			int idx = binarySearchCeil(cw, r);
			if (idx < 0 || idx >= poolSize) continue;
			if (isBitSet(bitmap, idx)) {
				// 已选，向后扫描下一个未选
				int next = findNextUnset(bitmap, idx + 1, poolSize);
				if (next < 0) next = findNextUnset(bitmap, 0, idx);
				if (next < 0) break;
				idx = next;
				if (isBitSet(bitmap, idx)) continue;
			}
			setBit(bitmap, idx);
			selected.add(allTypes.get(idx));
			picked++;
		}
		return selected;
	}

	/** 二分查找第一个 cw[i] > r 的索引（左闭右开区间） */
	private static int binarySearchCeil(double[] cw, double r) {
		int lo = 0, hi = cw.length - 1;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (cw[mid] <= r) lo = mid + 1;
			else hi = mid;
		}
		return cw[lo] > r ? lo : -1;
	}

	/** 从 start 开始扫描位图，找到第一个未设置的位 */
	private static int findNextUnset(long[] bitmap, int start, int poolSize) {
		for (int i = start; i < poolSize; i++) {
			if (!isBitSet(bitmap, i)) return i;
		}
		return -1;
	}

	private static boolean isBitSet(long[] bitmap, int idx) {
		return (bitmap[idx >>> 6] & (1L << (idx & 63))) != 0L;
	}

	private static void setBit(long[] bitmap, int idx) {
		bitmap[idx >>> 6] |= (1L << (idx & 63));
	}

	private static double[] buildUniformCumulative(int size) {
		double[] cw = new double[size];
		for (int i = 0; i < size; i++) cw[i] = i + 1;
		return cw;
	}

	/**
	 * 获取指定类型列表对应的权重数组
	 * <br/>
	 * 用于与 {@link WeightedAllocation#allocateByWeight} 配合使用。
	 * 类型不在权重表中时返回 1.0（默认权重）。
	 *
	 * @param typesList 类型列表
	 * @return 权重数组（长度等于 typesList.size()）
	 */
	public double[] getWeightsFor(List<ResourceLocation> typesList) {
		double[] result = new double[typesList.size()];
		for (int i = 0; i < typesList.size(); i++) {
			Integer idx = typeIndex.get(typesList.get(i));
			result[i] = (idx != null && idx < weight.length) ? weight[idx] : 1.0;
		}
		return result;
	}

	/**
	 * 记录实际产出（每 tick 累加到 pendingOutputs，flush 时合并入 recentEMA）
	 * <br/>
	 * 由 {@link MyriadCreationsHandler} 在产物成功插入后调用。
	 * 修复 #14：synchronized 与 {@link #rebuildWeightsIfNeeded} 一致，保证可见性。
	 *
	 * @param type   蜜蜂类型
	 * @param amount 产出数量
	 */
	public synchronized void recordOutput(ResourceLocation type, int amount) {
		if (type == null || amount <= 0) return;
		Integer idx = typeIndex.get(type);
		if (idx == null || idx >= pendingOutputs.length) return;
		pendingOutputs[idx] += amount;
	}

	/**
	 * 批量记录产出（便利方法） — 修复 #14：synchronized 与 {@link #recordOutput} 一致
	 *
	 * @param allocation 类型→数量映射
	 */
	public synchronized void recordOutputs(Map<ResourceLocation, Integer> allocation) {
		if (allocation == null || allocation.isEmpty()) return;
		for (Map.Entry<ResourceLocation, Integer> e : allocation.entrySet()) {
			ResourceLocation type = e.getKey();
			Integer amt = e.getValue();
			if (amt != null && amt > 0) recordOutput(type, amt);
		}
	}

	/**
	 * 失效所有缓存（配置重载时调用）
	 */
	public synchronized void invalidate() {
		tickCache.clear();
		lastRebuildTick = -1L;
		lastFlushTick = -1L;
		lastCleanupTick = -1L;
	}

	private void logDegrade(Level level, String msg, Exception e) {
		if (level == null) return;
		degradeThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
			ProductiveBeesGenesis.LOGGER.warn(msg + (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), e);
		});
	}

	/** 工厂级调用计数缓存条目（Task 8 + Bug 2 修复）：WeakReference 持有工厂，GC 后 matches 返回 false */
	private static final class CachedSelection {

		final WeakReference<Object> factoryRef;

		/** 创建时的调用计数（Bug 2 修复：替代 cachedTick） */
		long cachedCallCount = -1L;

		int cachedCount = -1;

		int cachedVersion = -1;

		List<ResourceLocation> selected = List.of();

		CachedSelection(Object factory) {
			this.factoryRef = new WeakReference<>(factory);
		}

		/**
		 * 匹配检查 — 工厂实例已被 GC 时返回 false（强制重新选型，同时新条目会覆盖旧引用）
		 * <p>
		 * Bug 2 修复：缓存有效期基于调用计数差值，{@code currentCallCount - cachedCallCount < CALL_COUNTER_REFRESH_INTERVAL} 时命中。
		 */
		boolean matches(int count, long currentCallCount, int version, Object factoryKey) {
			if (factoryRef.get() != factoryKey) return false;
			if (currentCallCount - cachedCallCount >= CALL_COUNTER_REFRESH_INTERVAL) return false;
			return cachedCount == count && cachedVersion == version;
		}
	}
}
