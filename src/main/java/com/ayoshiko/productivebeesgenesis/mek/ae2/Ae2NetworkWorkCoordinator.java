package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 ME 存储网络协调昂贵工作的共享调度器。
 * <p>
 * 健康网络不参与调度；检测到慢操作后，同一网络每刻只放行一个活跃宿主，
 * 并通过持久轮转游标避免固定 tick 顺序造成饥饿。不同网络拥有独立预算，
 * 不再因一套病态外部存储拖慢无关网络。
 * <p>
 * 网络键使用身份语义弱引用，状态值不反向持有网络或宿主；宿主只以 long 令牌登记，
 * 因而区块卸载和换网后不会被静态表强引用。
 */
final class Ae2NetworkWorkCoordinator {

	static final long HEALTHY_INSERT_NANOS = 150_000L;
	private static final long NETWORK_BUDGET_NANOS = 2_000_000L;
	private static final long ACTIVE_WORKER_WINDOW_TICKS = 1L;
	/**
	 * 宿主活跃表的清理周期（游戏刻）。
	 * <p>
	 * 原实现在<b>每次</b> {@code tryAcquire} 里都遍历整张宿主表做淘汰：一台机器每刻会取
	 * 多次令牌（物品直推、批量输出、流体推送各一次），N 台机器共享同一网络时就是每刻
	 * O(N²) 次 map 迭代。现在把淘汰从热路径摘出来，改为每 {@value} 刻扫一次；
	 * 「本刻谁活跃」由 {@link NetworkState#selectNextWorker(long)} 按活跃窗口过滤保证，
	 * 因此淘汰只是内存回收，延后执行不影响调度正确性。
	 */
	private static final long WORKER_REAP_INTERVAL_TICKS = 20L;
	private static final int EWMA_SHIFT = 3;
	private static final AtomicLong NEXT_WORKER_ID = new AtomicLong(1L);
	private static final ReferenceQueue<Object> STALE_NETWORKS = new ReferenceQueue<>();
	private static final ConcurrentHashMap<IdentityWeakReference, NetworkState> NETWORKS =
			new ConcurrentHashMap<>();

	private Ae2NetworkWorkCoordinator() {
	}

	static long createWorkerId() {
		long id = NEXT_WORKER_ID.getAndIncrement();
		return id > 0L ? id : NEXT_WORKER_ID.updateAndGet(current -> Math.max(1L, current));
	}

	/**
	 * 尝试取得当前网络本刻的工作令牌。
	 *
	 * @return 健康网络恒为 true；昂贵网络仅轮到的宿主且预算未耗尽时为 true
	 */
	static boolean tryAcquire(Object networkIdentity, long workerId, long gameTick) {
		return tryAcquireResolved(resolve(networkIdentity), workerId, gameTick);
	}

	/**
	 * 解析网络身份对应的状态句柄。
	 * <p>
	 * 返回的句柄只与这个网络对象绑定，宿主可长期缓存它，避免每次取令牌都分配
	 * {@link IdentityWeakReference} 并做一次弱键 map 查找（每台机器每刻要取多次令牌）。
	 *
	 * @param networkIdentity 网络标识（{@code MEStorage} 实例）
	 * @return 状态句柄；identity 为 null 时返回 null
	 */
	static Object resolve(Object networkIdentity) {
		return networkIdentity == null ? null : stateFor(networkIdentity);
	}

	/**
	 * 用已解析的状态句柄取令牌（{@link #resolve(Object)} 的配套入口）。
	 *
	 * @return 健康网络恒为 true；昂贵网络仅轮到的宿主且预算未耗尽时为 true
	 */
	static boolean tryAcquireResolved(Object networkState, long workerId, long gameTick) {
		if (!(networkState instanceof NetworkState state) || workerId <= 0L) return false;
		synchronized (state) {
			state.touchWorker(workerId, gameTick);
			if (!state.isExpensive()) return true;
			state.refreshBudget(gameTick);
			if (state.spentNanos >= NETWORK_BUDGET_NANOS) return false;
			if (state.turnTick != gameTick) {
				state.turnTick = gameTick;
				state.turnWorkerId = state.selectNextWorker(gameTick);
			}
			if (state.turnWorkerId != workerId) return false;
			state.cursorWorkerId = workerId;
			return true;
		}
	}

	/**
	 * 记录一次网络操作成本。
	 * <p>
	 * 预算与昂贵判定都使用调用方给出的健康阈值：insert 传 150µs，extract 传 5ms。
	 * 这样 0.2-0.4ms 的高频中等成本 insert 会触发网络内错峰，而健康大型网络常见的
	 * 0.6-3ms extract 不会被误判为病态。
	 */
	static void recordCost(Object networkIdentity, long gameTick, long costNanos, long thresholdNanos) {
		recordResolvedCost(resolve(networkIdentity), gameTick, costNanos, thresholdNanos);
	}

	/** 用已解析的状态句柄记录成本（{@link #resolve(Object)} 的配套入口）。 */
	static void recordResolvedCost(Object networkState, long gameTick, long costNanos, long thresholdNanos) {
		if (!(networkState instanceof NetworkState state) || costNanos < 0L) return;
		long healthThreshold = Math.max(0L, thresholdNanos);
		long budgetExcess = Math.max(0L, costNanos - healthThreshold);
		long healthExcess = budgetExcess;
		synchronized (state) {
			state.refreshBudget(gameTick);
			state.spentNanos = saturatingAdd(state.spentNanos, budgetExcess);
			if (!state.hasCostSample) {
				state.averageExcessNanos = healthExcess;
				state.hasCostSample = true;
			} else {
				state.averageExcessNanos += (healthExcess - state.averageExcessNanos) >> EWMA_SHIFT;
			}
		}
	}

	/** 主动移除宿主在旧网络上的活跃资格；网络状态本身仍由弱键自动回收。 */
	static void release(Object networkIdentity, long workerId) {
		if (networkIdentity == null || workerId <= 0L) return;
		NetworkState state = NETWORKS.get(new IdentityWeakReference(networkIdentity));
		if (state == null) return;
		synchronized (state) {
			state.workerLastSeen.remove(workerId);
			if (state.turnWorkerId == workerId) state.turnWorkerId = 0L;
		}
	}

	static long spentNanosForTest(Object networkIdentity, long gameTick) {
		NetworkState state = stateFor(networkIdentity);
		synchronized (state) {
			state.refreshBudget(gameTick);
			return state.spentNanos;
		}
	}

	/** 已登记的活跃宿主条目数；供测试验证周期性回收确实生效（内存不随历史机器数量增长）。 */
	static int workerCountForTest(Object networkIdentity) {
		if (networkIdentity == null) return 0;
		NetworkState state = stateFor(networkIdentity);
		return state.workerLastSeen.size();
	}

	/** 清空全部网络状态；仅在服务端停止或测试隔离时调用。 */
	static void clearAll() {
		NETWORKS.clear();
		while (STALE_NETWORKS.poll() != null) {
			// 清空引用队列。
		}
	}

	static void resetForTest() {
		clearAll();
	}

	private static NetworkState stateFor(Object networkIdentity) {
		reapStaleNetworks();
		IdentityWeakReference lookup = new IdentityWeakReference(networkIdentity);
		NetworkState existing = NETWORKS.get(lookup);
		if (existing != null) return existing;
		IdentityWeakReference stored = new IdentityWeakReference(networkIdentity, STALE_NETWORKS);
		NetworkState created = new NetworkState();
		NetworkState raced = NETWORKS.putIfAbsent(stored, created);
		return raced == null ? created : raced;
	}

	private static void reapStaleNetworks() {
		IdentityWeakReference stale;
		while ((stale = (IdentityWeakReference) STALE_NETWORKS.poll()) != null) {
			NETWORKS.remove(stale);
		}
	}

	private static long saturatingAdd(long left, long right) {
		if (right <= 0L) return left;
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static final class NetworkState {
		private final ConcurrentHashMap<Long, Long> workerLastSeen = new ConcurrentHashMap<>();
		private boolean hasCostSample;
		private long averageExcessNanos;
		private long budgetTick = Long.MIN_VALUE;
		private long spentNanos;
		private long turnTick = Long.MIN_VALUE;
		private long turnWorkerId;
		private long cursorWorkerId;
		/** 上次淘汰宿主表的游戏刻，用于把 O(宿主数) 的清理摊到每 N 刻一次。 */
		private long lastReapTick = Long.MIN_VALUE;

		private boolean isExpensive() {
			return averageExcessNanos >= 1L;
		}

		private void refreshBudget(long gameTick) {
			if (budgetTick == gameTick) return;
			budgetTick = gameTick;
			spentNanos = 0L;
		}

		/**
		 * 登记本刻活跃宿主。
		 * <p>
		 * 热路径只做一次 put；淘汰改为每 {@link #WORKER_REAP_INTERVAL_TICKS} 刻一次，
		 * 「本刻谁有资格被选中」由 {@link #selectNextWorker(long)} 的活跃窗口过滤保证，
		 * 因此淘汰延后不会让已消失的宿主拿到令牌。
		 */
		private void touchWorker(long workerId, long gameTick) {
			workerLastSeen.put(workerId, gameTick);
			// 初值必须单独判断：Long.MIN_VALUE 与 gameTick 相减会溢出成负数，
			// 直接比较大小会让淘汰永远不触发（宿主表随历史机器数量无界增长）。
			if (lastReapTick == Long.MIN_VALUE
					|| gameTick - lastReapTick >= WORKER_REAP_INTERVAL_TICKS) {
				lastReapTick = gameTick;
				reapInactiveWorkers(gameTick);
			}
		}

		/** 回收活跃窗口之外的宿主条目，防止宿主表随历史机器数量无界增长。 */
		private void reapInactiveWorkers(long gameTick) {
			long oldestActiveTick = gameTick - ACTIVE_WORKER_WINDOW_TICKS;
			workerLastSeen.values().removeIf(lastSeen -> lastSeen < oldestActiveTick);
		}

		/**
		 * 在**本刻活跃窗口内**的宿主中按游标轮转选出一个。
		 * <p>
		 * 必须按活跃窗口过滤：淘汰已改为周期性执行，若直接遍历整张表，已经停止调用
		 * （区块卸载/换网）的宿主仍可能被选中，造成本刻令牌空转、轮转被冻结。
		 */
		private long selectNextWorker(long gameTick) {
			long oldestActiveTick = gameTick - ACTIVE_WORKER_WINDOW_TICKS;
			long first = Long.MAX_VALUE;
			long afterCursor = Long.MAX_VALUE;
			for (Map.Entry<Long, Long> entry : workerLastSeen.entrySet()) {
				if (entry.getValue() < oldestActiveTick) continue;
				long workerId = entry.getKey();
				if (workerId < first) first = workerId;
				if (workerId > cursorWorkerId && workerId < afterCursor) afterCursor = workerId;
			}
			if (afterCursor != Long.MAX_VALUE) return afterCursor;
			return first == Long.MAX_VALUE ? 0L : first;
		}
	}

	private static final class IdentityWeakReference extends WeakReference<Object> {
		private final int identityHash;

		private IdentityWeakReference(Object referent) {
			super(referent);
			identityHash = System.identityHashCode(referent);
		}

		private IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
			super(referent, queue);
			identityHash = System.identityHashCode(referent);
		}

		@Override
		public int hashCode() {
			return identityHash;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof IdentityWeakReference reference)) return false;
			Object left = get();
			return left != null && left == reference.get();
		}
	}
}
