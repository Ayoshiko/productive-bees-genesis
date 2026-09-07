package com.ayoshiko.productivebeesgenesis.util;

import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端热路径专用的有界布尔记忆表 —— 无锁、无装箱、O(1) 淘汰、命中率不塌陷。
 * <p>
 * <b>替代对象</b>：{@code synchronized} + 访问顺序 {@link java.util.LinkedHashMap} 的
 * 「同步 LRU」组合。该组合在每 tick 数千次的判定路径上有三重固定成本：
 * <ol>
 *   <li>每次 get/put 都进出 monitor（即使只有 tick 线程真正竞争）；</li>
 *   <li>accessOrder=true 使<b>命中</b>也要重排双向链表节点（写内存）；</li>
 *   <li>值为 {@link Boolean} 对象，虽有缓存实例但仍多一次指针解引用。</li>
 * </ol>
 * spark BkTP3d9oSc 中 {@code Ae2SmeltingInputCache.contains} 自身 self 时间即达
 * 344ms（0.57%），其中大部分属于上述固定成本而非真正的配方查询。
 * <p>
 * <b>淘汰策略：两代分表</b>。写满 {@code maxEntries} 后把 primary 整体降为 victim
 * 并复用旧 victim 的数组作为新 primary；查询顺序为 primary → victim，命中 victim
 * 时提升回 primary。于是：
 * <ul>
 *   <li>淘汰是 O(1) 的「整代丢弃」，不需要链表也不需要逐条比较；</li>
 *   <li>常驻热键总能在降代后的第一次访问被提升回来，不会出现「满即整表清空」
 *       导致的命中率塌陷（清空后每个键都要重算）；</li>
 *   <li>驻留上界为 2 × {@code maxEntries}，内存仍然有界。</li>
 * </ul>
 * <p>
 * <b>失效与线程安全</b>：读写只允许服务端 tick 线程，因此内部容器不同步。
 * AE2 网格回调可能在其它线程要求失效，这类调用走 {@link #requestClear()} ——
 * 只递增一个 {@link AtomicLong}，真正的清表推迟到 tick 线程下一次
 * {@link #state} / {@link #remember} 时执行，从而完全避免热路径加锁。
 * <p>
 * <b>键的选择</b>：调用方应传入「决定判定结果的最小身份」（通常是
 * {@link net.minecraft.world.item.Item} 引用或轻量 record），不要直接用
 * {@code AEItemKey} —— 后者的 {@code equals} 会走
 * {@code ItemStack.isSameItemSameComponents}（装有 geckolib 时还被 mixin 包裹），
 * 单次比较即可贵到进入 spark 热点榜。
 * <p>
 * <b>用法</b>：查询与写入分两步，避免为 miss 分支捕获 lambda：
 * <pre>{@code
 * int state = memo.state(item);
 * if (state != BoundedBooleanMemo.STATE_UNKNOWN) return state == BoundedBooleanMemo.STATE_TRUE;
 * return memo.remember(item, expensiveQuery(item));
 * }</pre>
 *
 * @param <K> 记忆键类型，必须实现稳定的 {@code equals/hashCode}（Item 引用天然满足）
 */
public final class BoundedBooleanMemo<K> {

	/** 该键尚未记录过判定结果。 */
	public static final int STATE_UNKNOWN = -1;
	/** 该键已记录为 false。 */
	public static final int STATE_FALSE = 0;
	/** 该键已记录为 true。 */
	public static final int STATE_TRUE = 1;

	/** 未记录（fastutil 默认返回值）。 */
	private static final byte UNKNOWN = 0;
	private static final byte FALSE_VALUE = 1;
	private static final byte TRUE_VALUE = 2;

	private final int maxEntries;

	/** 当前代：新写入与命中提升都落在这里。 */
	private Object2ByteOpenHashMap<K> primary;

	/** 上一代：仍可命中，命中即提升回 primary；再次写满时整代丢弃。 */
	private Object2ByteOpenHashMap<K> victim;

	/** 跨线程失效请求计数（只增不减）。 */
	private final AtomicLong clearRequests = new AtomicLong();

	/** tick 线程已消费的失效请求数。 */
	private long observedClearRequests;

	/**
	 * @param maxEntries 单代条目上限（总驻留上界为其两倍），必须为正数
	 */
	public BoundedBooleanMemo(int maxEntries) {
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("maxEntries must be positive, got: " + maxEntries);
		}
		this.maxEntries = maxEntries;
		this.primary = newTable(maxEntries);
		this.victim = newTable(maxEntries);
	}

	/**
	 * 查询记忆状态。
	 *
	 * @param key 记忆键，null 视为未记录
	 * @return {@link #STATE_UNKNOWN} / {@link #STATE_FALSE} / {@link #STATE_TRUE}
	 */
	public int state(K key) {
		syncClearRequests();
		if (key == null) return STATE_UNKNOWN;
		byte cached = primary.getByte(key);
		if (cached != UNKNOWN) return cached == TRUE_VALUE ? STATE_TRUE : STATE_FALSE;
		cached = victim.getByte(key);
		if (cached == UNKNOWN) return STATE_UNKNOWN;
		// 上一代命中即提升回当前代：热键因此能跨越任意多次降代继续驻留
		primary.put(key, cached);
		rotateIfFull();
		return cached == TRUE_VALUE ? STATE_TRUE : STATE_FALSE;
	}

	/**
	 * 写入记忆结果；写满当前代时把它降为 victim 并复用旧 victim 数组作为新的当前代。
	 *
	 * @param key   记忆键，null 直接忽略
	 * @param value 判定结果
	 * @return 原样返回 {@code value}，便于在 return 语句里链式使用
	 */
	public boolean remember(K key, boolean value) {
		syncClearRequests();
		if (key == null) return value;
		primary.put(key, value ? TRUE_VALUE : FALSE_VALUE);
		rotateIfFull();
		return value;
	}

	/**
	 * 请求清空整表。可从任意线程调用（AE2 网格回调常在非 tick 线程）。
	 * <p>
	 * 只递增计数器，真正的清空由 tick 线程在下一次访问时执行，因此不引入锁，
	 * 也不会与正在读表的 tick 线程产生并发修改。
	 */
	public void requestClear() {
		clearRequests.incrementAndGet();
	}

	/** 立即清空（仅允许 tick 线程调用）。 */
	public void clearNow() {
		primary.clear();
		victim.clear();
		observedClearRequests = clearRequests.get();
	}

	/** 当前驻留条目数（两代之和，跨代重复键会各计一次），供诊断与测试使用。 */
	public int size() {
		syncClearRequests();
		return primary.size() + victim.size();
	}

	/** 单代上限，供测试断言容量边界。 */
	public int maxEntriesPerGeneration() {
		return maxEntries;
	}

	private void rotateIfFull() {
		if (primary.size() < maxEntries) return;
		// 复用旧 victim 的数组作为新 primary，避免每次降代都重新分配哈希表
		Object2ByteOpenHashMap<K> recycled = victim;
		recycled.clear();
		victim = primary;
		primary = recycled;
	}

	private void syncClearRequests() {
		long requested = clearRequests.get();
		if (observedClearRequests == requested) return;
		primary.clear();
		victim.clear();
		observedClearRequests = requested;
	}

	private static <K> Object2ByteOpenHashMap<K> newTable(int maxEntries) {
		Object2ByteOpenHashMap<K> table = new Object2ByteOpenHashMap<>(Math.min(maxEntries, 64));
		table.defaultReturnValue(UNKNOWN);
		return table;
	}
}
