package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/** Per-machine, per-game-tick view of stock that AE2 can actually extract. */
public final class Ae2NetworkInventoryView {

	private Ae2NetworkInventoryView() {
	}

	/**
	 * Reads the already aggregated AE2 counter without probing every storage
	 * provider. This is used for GUI stock display; pull decisions use
	 * {@link #visibleAmount} when a simulated extract is required.
	 */
	public static long cachedAmount(KeyCounter cachedInventory, AEItemKey key, long cap) {
		if (cachedInventory == null || key == null || cap <= 0L) return 0L;
		return Ae2VisibleStockMath.merge(cachedInventory.get(key), 0L, cap);
	}

	public static long visibleAmount(Ae2OutputStateHolder holder, long gameTick,
			KeyCounter cachedInventory, MEStorage network, AEItemKey key, long cap,
			IActionSource source) {
		if (holder == null || cachedInventory == null || key == null || cap <= 0L) return 0L;

		TickCache cache = getTickCache(holder, gameTick, network);
		long visible = cache.amounts.getLong(key);
		if (visible < 0L) {
			long reported = Math.max(0L, cachedInventory.get(key));
			// 上报量已覆盖本次上限 → 本方法最终返回 min(max(上报, 实时), cap) = cap，
			// 实时探针不可能改变结果。跳过探针即为「零语义变化的纯节省」；
			// 只有上报量不足（占位/无限存储的典型场景）时探针才有意义。
			if (reported >= cap) return cap;
			long simulated = 0L;
			// AE2LT probes configured keys even when the cached counter is positive:
			// special/infinite storage may report a finite placeholder that is lower than
			// the amount the network can actually extract. The per-tick cache above keeps
			// GUI sync and input pulling from repeating this probe in the same game tick.
			// 昂贵网络下探针本身可能比抽取还贵（megacells 大宗盘线性扫压缩链），
			// 由 Ae2StockProbePolicy 决定是否值得探；健康网络永远照常探测。
			if (network != null && cache.probePolicy.shouldProbe(gameTick, key)) {
				long probeStart = System.nanoTime();
				try {
					simulated = liveExtractableAmount(network, key, Long.MAX_VALUE, source);
				} catch (LinkageError | RuntimeException ignored) {
					// The cached inventory is still a safe fallback for incompatible external storages.
				}
				cache.probePolicy.record(gameTick, key, System.nanoTime() - probeStart, reported, simulated);
			} else if (network != null && cache.probePolicy.isExpensiveNetwork()) {
				// 降级是静默的，玩家只会看到「机器变慢」；开发者模式下周期性提示昂贵存储元件方向
				Ae2ExpensiveNetworkLog.probeDowngraded(cache.probePolicy.averageCostNanos());
			}
			// Cache the uncapped result: GUI and pulling may request different caps in one tick.
			visible = Ae2VisibleStockMath.merge(reported, simulated, Long.MAX_VALUE);
			cache.amounts.put(key, visible);
		}
		return Ae2VisibleStockMath.merge(visible, 0L, cap);
	}

	/**
	 * 保留下限校验专用的实时可提取量查询，按 {@code (key, gameTick)} 记忆化。
	 * <p>
	 * <b>为什么必须记忆化</b>：底层是一次 {@code MEStorage.extract(SIMULATE)}，会穿透网络上
	 * 每一个存储元件 —— omnicell 的 {@code Object2LongOpenHashMap} 查找、megacells 大宗盘的
	 * 压缩链线性扫描、neoecoae 无限存储的 SavedData 查询都在其中。spark BkTP3d9oSc 里这一条
	 * 调用链占服务端主线程 612ms（1.02%）。而时间加速模组（JDTE 等）会在同一个 game tick 内
	 * 反复调用 {@code onUpdateServer}，让同一个键在同刻被实时探测 N 次。
	 * <p>
	 * <b>为什么记忆化不削弱保留下限的安全性</b>：
	 * <ul>
	 *   <li>本机自己的消耗由 {@link #recordExtract} 从缓存值中精确扣减，因此同刻的后续请求
	 *       看到的是「已扣除本机已抽取量」的余量，不会越过保留线；</li>
	 *   <li>其它机器/设备在同刻的消耗，在旧实现里也只能在两次 extract 之间的窗口被看见 ——
	 *       AE2 的 KeyCounter 本身直到 tick 末才刷新。每 game tick 保留一次真实探测后，
	 *       暴露窗口与「每 game tick 拉取一次」的原始设计完全等价。</li>
	 * </ul>
	 * <b>cap 语义</b>：缓存同时记录探测时使用的 cap。只有「上次 cap ≥ 本次 cap」或
	 * 「上次结果小于上次 cap（说明已探到底，即真实总量）」时才复用，否则重新探测 ——
	 * 避免用小 cap 的截断结果去回答大 cap 的提问。
	 * <p>
	 * <b>上报量足够时直接短路</b>：本方法的全部结论都来自
	 * {@code min(cap, 实时可提取量)}。AE2 的 KeyCounter 只会「少报」（占位/无限存储把
	 * 真实可提取量报小，这正是探针存在的理由），因此一旦上报量已经 ≥ cap，
	 * 实时值必然也 ≥ cap，答案恒为 cap —— 此时探测只是白花一次全网络遍历
	 * （spark 报告中 {@code reserveProbeAmount → NetworkStorage.extract} 是拉取路径上
	 * 最大的一条外部调用链）。只有上报量不足（需要知道到底有多少余量）时才真正探测。
	 *
	 * @param cachedInventory AE2 已聚合的网络库存快照（只读）
	 * @param cap 本次请求的上限（{@code reserveFloor + amount}）
	 * @return 至多 {@code cap} 的实时可提取量
	 */
	static long reserveProbeAmount(Ae2OutputStateHolder holder, long gameTick, KeyCounter cachedInventory,
			MEStorage network, AEItemKey key, long cap, IActionSource source) {
		if (network == null || key == null || source == null || cap <= 0L) return 0L;
		if (cachedInventory != null && Math.max(0L, cachedInventory.get(key)) >= cap) return cap;
		if (holder == null) return liveExtractableAmount(network, key, cap, source);

		TickCache cache = getTickCache(holder, gameTick, network);
		long cached = cache.reserveAmounts.getLong(key);
		if (cached >= 0L) {
			long cachedCap = cache.reserveCaps.getLong(key);
			// cachedCap >= cap：上次用不小于本次的上限探过，截断后仍精确
			// cached < cachedCap：上次没被上限截断，cached 即真实可提取总量
			if (cachedCap >= cap || cached < cachedCap) {
				return Math.min(cap, cached);
			}
		}
		long probed = liveExtractableAmount(network, key, cap, source);
		cache.reserveAmounts.put(key, probed);
		cache.reserveCaps.put(key, cap);
		return probed;
	}

	/**
	 * Queries current extractable stock without using the per-machine or AE2 inventory cache.
	 * Callers enforcing a reserve floor must invoke this immediately before MODULATE.
	 */
	static long liveExtractableAmount(MEStorage network, AEItemKey key, long cap, IActionSource source) {
		if (network == null || key == null || source == null || cap <= 0L) return 0L;
		long extracted = network.extract(key, cap, Actionable.SIMULATE, source);
		return Math.min(cap, Math.max(0L, extracted));
	}

	/**
	 * Applies a committed ME extraction to the same-tick view. JDTE may invoke the
	 * puller multiple times while the world game time is unchanged; keeping this
	 * key's cached amount in sync prevents a later invocation from crossing a
	 * configured reserve floor without invalidating the whole per-tick map.
	 * <p>
	 * 两张表都要扣减：{@code amounts} 服务于候选可见量，{@code reserveAmounts} 服务于
	 * 保留下限校验，后者一旦漏扣就会让同刻的后续抽取越过保留线。
	 */
	public static void recordExtract(Ae2OutputStateHolder holder, long gameTick,
			MEStorage network, AEItemKey key, long extracted) {
		if (holder == null || network == null || key == null || extracted <= 0L) return;
		Object cached = holder.getInputInventoryViewCache();
		if (!(cached instanceof TickCache cache)
				|| cache.network != network || cache.gameTick != gameTick) return;
		long current = cache.amounts.getLong(key);
		if (current >= 0L) {
			cache.amounts.put(key, Math.max(0L, current - extracted));
		}
		long reserved = cache.reserveAmounts.getLong(key);
		if (reserved >= 0L) {
			long remaining = Math.max(0L, reserved - extracted);
			cache.reserveAmounts.put(key, remaining);
			// 上限随实际余量一起下调，避免「余量已探到底」被误判为「被上限截断」
			cache.reserveCaps.put(key, Math.max(remaining, cache.reserveCaps.getLong(key) - extracted));
		}
	}

	private static TickCache getTickCache(Ae2OutputStateHolder holder, long gameTick, MEStorage network) {
		Object cached = holder.getInputInventoryViewCache();
		if (cached instanceof TickCache tickCache && tickCache.network == network) {
			if (tickCache.gameTick != gameTick) {
				tickCache.gameTick = gameTick;
				tickCache.amounts.clear();
				tickCache.reserveAmounts.clear();
				tickCache.reserveCaps.clear();
			}
			return tickCache;
		}
		TickCache fresh = new TickCache(gameTick, network);
		holder.setInputInventoryViewCache(fresh);
		return fresh;
	}

	private static final class TickCache {
		private long gameTick;
		private final MEStorage network;
		private final Object2LongOpenHashMap<AEItemKey> amounts = new Object2LongOpenHashMap<>();
		/** 保留下限校验的同刻实时探测结果；与 {@link #amounts} 语义不同，不含 KeyCounter 上报量。 */
		private final Object2LongOpenHashMap<AEItemKey> reserveAmounts = new Object2LongOpenHashMap<>();
		/** 每条 {@link #reserveAmounts} 记录当时使用的探测上限，用于判断结果是否被截断。 */
		private final Object2LongOpenHashMap<AEItemKey> reserveCaps = new Object2LongOpenHashMap<>();
		/**
		 * 探针策略与本网络同生命周期：amounts 每 tick 清空，但「哪些键值得探针」
		 * 是网络级知识，必须跨 tick 保留；换网络时随新 TickCache 一起重建。
		 */
		private final Ae2StockProbePolicy<AEItemKey> probePolicy = new Ae2StockProbePolicy<>();

		private TickCache(long gameTick, MEStorage network) {
			this.gameTick = gameTick;
			this.network = network;
			amounts.defaultReturnValue(-1L);
			reserveAmounts.defaultReturnValue(-1L);
			reserveCaps.defaultReturnValue(-1L);
		}
	}
}
