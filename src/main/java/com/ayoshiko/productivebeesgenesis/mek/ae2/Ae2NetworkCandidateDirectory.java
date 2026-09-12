package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 AE 网络共享的输入候选基础目录。
 * <p>
 * 同一 {@code KeyCounter} 只枚举一次并缓存不依赖宿主配置的基础分类：蜜脾类键与普通物品键。
 * 标签表达式、SMELTING 配方和本机可处理性仍由宿主本地执行，避免跨机器错误复用。
 * <p>
 * 网络键和库存快照身份均使用弱引用，值只持有候选 {@link AEItemKey}；当 AE2 释放网络与
 * 库存快照后，静态表不会阻止它们被回收。服务端停止时还会主动清空全部目录。
 */
final class Ae2NetworkCandidateDirectory {

	private static final long REFRESH_INTERVAL_TICKS = 10L;
	private static final ReferenceQueue<Object> STALE_NETWORKS = new ReferenceQueue<>();
	private static final ConcurrentHashMap<IdentityWeakReference, Directory> DIRECTORIES =
			new ConcurrentHashMap<>();

	private Ae2NetworkCandidateDirectory() {
	}

	/**
	 * 返回当前网络的共享基础目录；到期或库存快照身份变化时由本次调用重建。
	 * <p>
	 * 首个访问者负责重建，后续同网络宿主只读取不可变快照。刷新周期从首次访问刻起算，
	 * 因而不同网络天然错峰，同时保证新类型最多延迟半秒被发现。
	 *
	 * @param networkIdentity MEStorage 或 IGrid 身份
	 * @param inventorySource AE2 缓存库存对象身份
	 * @param gameTick 当前游戏刻
	 * @param entries AE2 缓存库存条目
	 * @return 当前网络的不可变基础候选快照
	 */
	static Snapshot get(Object networkIdentity, Object inventorySource, long gameTick,
			Iterable<? extends Object2LongMap.Entry<?>> entries) {
		if (networkIdentity == null || inventorySource == null || entries == null) return Snapshot.EMPTY;
		Directory directory = directoryFor(networkIdentity);
		synchronized (directory) {
			if (directory.needsRefresh(inventorySource, gameTick)) {
				directory.rebuild(inventorySource, gameTick, entries);
			}
			return directory.snapshot;
		}
	}

	/** 清空全部网络目录；仅在服务端停止或测试隔离时调用。 */
	static void clearAll() {
		DIRECTORIES.clear();
		while (STALE_NETWORKS.poll() != null) {
			// 清空引用队列。
		}
	}

	static void resetForTest() {
		clearAll();
	}

	static int directoryCountForTest() {
		reapStaleNetworks();
		return DIRECTORIES.size();
	}

	private static Directory directoryFor(Object networkIdentity) {
		reapStaleNetworks();
		IdentityWeakReference lookup = new IdentityWeakReference(networkIdentity);
		Directory existing = DIRECTORIES.get(lookup);
		if (existing != null) return existing;
		IdentityWeakReference stored = new IdentityWeakReference(networkIdentity, STALE_NETWORKS);
		Directory created = new Directory();
		Directory raced = DIRECTORIES.putIfAbsent(stored, created);
		return raced == null ? created : raced;
	}

	private static void reapStaleNetworks() {
		IdentityWeakReference stale;
		while ((stale = (IdentityWeakReference) STALE_NETWORKS.poll()) != null) {
			DIRECTORIES.remove(stale);
		}
	}

	record Snapshot(List<AEItemKey> combKeys, List<AEItemKey> ordinaryKeys, long generation) {
		private static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), 0L);
	}

	/** 纯目录刷新状态，供不加载 Minecraft 注册表的单元测试验证共享与失效语义。 */
	static final class RefreshState {
		private Object inventorySource;
		private long refreshTick = Long.MIN_VALUE;
		private long generation;

		boolean refresh(Object source, long gameTick) {
			if (source == null) return false;
			boolean due = inventorySource != source
					|| refreshTick == Long.MIN_VALUE
					|| gameTick < refreshTick
					|| gameTick - refreshTick >= REFRESH_INTERVAL_TICKS;
			if (!due) return false;
			inventorySource = source;
			refreshTick = gameTick;
			generation++;
			return true;
		}

		long generation() {
			return generation;
		}
	}

	private static final class Directory {
		private WeakReference<Object> inventorySource = new WeakReference<>(null);
		private long refreshTick = Long.MIN_VALUE;
		private long generation;
		private Snapshot snapshot = Snapshot.EMPTY;

		private boolean needsRefresh(Object source, long gameTick) {
			return inventorySource.get() != source
					|| refreshTick == Long.MIN_VALUE
					|| gameTick < refreshTick
					|| gameTick - refreshTick >= REFRESH_INTERVAL_TICKS;
		}

		private void rebuild(Object source, long gameTick,
				Iterable<? extends Object2LongMap.Entry<?>> entries) {
			java.util.ArrayList<AEItemKey> combs = new java.util.ArrayList<>();
			java.util.ArrayList<AEItemKey> ordinary = new java.util.ArrayList<>();
			for (Object2LongMap.Entry<?> entry : entries) {
				if (!(entry.getKey() instanceof AEItemKey key)) continue;
				if (CombFuzzyMatcher.isCombItem(key)) combs.add(key);
				else ordinary.add(key);
			}
			inventorySource = new WeakReference<>(source);
			refreshTick = gameTick;
			generation++;
			snapshot = new Snapshot(List.copyOf(combs), List.copyOf(ordinary), generation);
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
