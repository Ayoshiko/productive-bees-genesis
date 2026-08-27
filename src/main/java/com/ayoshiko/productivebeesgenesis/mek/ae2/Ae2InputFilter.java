package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
	 * AE2 输入过滤器 — 蜜脾种类的白名单/黑名单数据模型（位置固定 + 精确模式）
	 * <br/>
	 * 提供 per-tile 的蜜蜂类型过滤能力，独立于全局配置，由 {@link Ae2OutputStateHolder} 持有引用。
	 * <p>
	 * <b>V15 变更</b>：
	 * <ul>
	 *   <li>filterEntries 从 {@code List<String>} 改为固定大小 {@code String[]} 数组，
	 *       支持"空槽位"表达，实现真正的"位置固定"语义（参考 AE2 GenericStackInv）</li>
	 *   <li>removeEntryAt 仅清空目标位，不再移位，保证其他条目位置不变</li>
	 *   <li>新增 ensureCapacity / setEntryAtIndex / getCapacity / getNonEmptyEntries / IndexedEntry</li>
	 *   <li>序列化改为带 index 的 CompoundTag 列表，向后兼容旧 StringTag 紧凑格式</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：filterMode/preciseMode/slots 均使用 volatile 发布，
	 * 每次修改都 clone→set→publish（CopyOnWrite 语义），遍历弱一致。
	 * 写方法使用 synchronized 互斥，防止并发 clone→modify→publish 丢失修改。
	 * 设计上保证 AE2 拉取 tick 线程与 GUI 配置线程并发访问安全。
	 *
	 * @since 1.0.0
	 */
public final class Ae2InputFilter {

	/** 过滤模式枚举 */
	public enum FilterMode {
		/** 禁用过滤 */
		DISABLED,
		/** 白名单：仅拉取列表中的种类 */
		WHITELIST,
		/** 黑名单：拉取列表外的种类 */
		BLACKLIST
	}

	/** Default persistent capacity; the client may paginate it with a different column count. */
	private static final int DEFAULT_CAPACITY = 36;

	/** 过滤槽位最大数量（合法索引 0..MAX_FILTER_SLOTS-1，排他上限，防止恶意 index 导致 OOM） */
	static final int MAX_FILTER_SLOTS = 1024;
	static final String DIRECT_ENTRY_PREFIX = "@";
	/** Default per-entry pull request before the amount editor is used. */
	public static final long DEFAULT_DIRECT_AMOUNT = 64L;
	/** Fallback used while the server configuration is still unavailable. */
	public static final long MAX_DIRECT_AMOUNT = 8_192L;
	/** Reserve values are stock floors, so they are not tied to the per-pull rate. */
	public static final long MAX_DIRECT_RESERVE_AMOUNT = Long.MAX_VALUE;

	public static long clampDirectReserveAmount(long amount) {
		return Math.max(0L, Math.min(MAX_DIRECT_RESERVE_AMOUNT, amount));
	}
	/** Sentinel returned by the allocation-free pull-candidate decision path. */
	static final long PULL_DISALLOWED = Ae2FilterPullPolicy.PULL_DISALLOWED;

	/**
	 * Returns the configured per-pull cap. The same value is used by the puller,
	 * amount editor, NBT persistence and network validation so a client cannot
	 * configure an amount that the server will silently clamp differently.
	 */
	public static long getMaxDirectAmount() {
		try {
			if (ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeAeInputRatePerTick != null) {
				return Math.max(1L, ModConfig.SERVER.mekCentrifugeAeInputRatePerTick.get());
			}
		} catch (LinkageError | RuntimeException ignored) {
			// Client screens can be constructed before the server config is attached.
		}
		return MAX_DIRECT_AMOUNT;
	}

	/** 默认持久化容量（供 NBT 编解码等内部模块使用） */
	static int getDefaultCapacity() {
		return DEFAULT_CAPACITY;
	}

	/** 最大槽位数（供 NBT 编解码等内部模块使用） */
	static int getMaxFilterSlots() {
		return MAX_FILTER_SLOTS;
	}

	/** 过滤模式（默认 DISABLED） */
	private volatile FilterMode filterMode = FilterMode.DISABLED;

	/**
	 * 精确模式 — true 时区分蜜脾和蜜脾块
	 * <br/>
	 * false（默认）：同种 beeType 的蜜脾和蜜脾块一起拉取（向后兼容）
	 * true：仅拉取精确匹配的物品类型（蜜脾标记不会拉取蜜脾块，反之亦然）
	 */
	private volatile boolean preciseMode = false;

	/**
	 * 过滤条目固定大小数组（位置固定模式）
	 * <br/>
	 * 使用 volatile 发布，每次修改都 clone→set→publish（CopyOnWrite 语义）。
	 * null 表示空槽位，非 null 字符串为条目。
	 * <p>
	 * 精确模式下，条目格式为 {@code beeType} 或 {@code beeType#block}（后缀 #block 表示蜜脾块）。
	 * 非精确模式下，条目仅为 {@code beeType}，蜜脾和蜜脾块共享同一过滤条目。
	 */
	private volatile String[] slots = new String[DEFAULT_CAPACITY];
	/**
	 * 直连条目解析缓存（fingerprint → AEItemKey）— 懒创建
	 * <br/>
	 * <b>禁止字段初始化器创建</b>：{@code new AEItemKey[...]} 是构造器 &lt;init&gt; 的一部分，
	 * anewarray 指令会立即解析组件类 AEItemKey — AE2 未安装时 new 本类即 NoClassDefFoundError
	 * （Issue #8：GUI tracker 的 getFilterMode 同步在无 AE2 环境触发构造）。
	 * 改为 null 起始 + {@link #ensureKeys()} 首次写入时创建；读取路径容忍 null。
	 */
	private volatile AEItemKey[] resolvedDirectKeys;
	private volatile long[] directAmounts = new long[DEFAULT_CAPACITY];
	/** Minimum AE2 network stock to keep for each exact entry in network-stock mode. */
	private volatile long[] directReserveAmounts = new long[DEFAULT_CAPACITY];
	/** Client-side network stock snapshot; this is never persisted or trusted for pulls. */
	private volatile long[] directVisibleAmounts = new long[DEFAULT_CAPACITY];
	private volatile boolean[] directUnlimited = new boolean[DEFAULT_CAPACITY];
	private volatile boolean[] directNetworkStock = new boolean[DEFAULT_CAPACITY];
	/** Filter-level network stock policy applied to every allowed comb candidate. */
	private volatile boolean globalNetworkStock;
	/** Default stock floor used by {@link #globalNetworkStock}. */
	private volatile long globalReserveAmount;
	/** Immutable direct-entry snapshot; invalidated only by configuration changes. */
	private volatile List<DirectEntry> directEntriesCache;
	/** Parsed fuzzy entries keyed by the copy-on-write slots array identity. */
	private volatile FuzzyEntriesCache fuzzyEntriesCache;

	/** 过滤器准入后对所有允许蜜脾生效的全局无限拉取开关。 */
	private volatile boolean unlimitedAllFallback = false;

	/**
	 * 懒创建 resolvedDirectKeys — 仅写入路径调用（GUI 配置/网络包/AE2 拉取解析，均处 AE2 环境）
	 * <br/>
	 * 调用方均为 synchronized 方法，无竞态；读取路径（getResolvedDirectKey 等）容忍 null 不经此方法。
	 */
	private AEItemKey[] ensureKeys() {
		AEItemKey[] local = resolvedDirectKeys;
		if (local == null) {
			local = new AEItemKey[Math.max(DEFAULT_CAPACITY, slots.length)];
			resolvedDirectKeys = local;
		}
		return local;
	}

	public FilterMode getFilterMode() {
		return filterMode;
	}

	public void setFilterMode(FilterMode mode) {
		this.filterMode = mode;
	}

	/** 循环切换过滤模式：DISABLED → WHITELIST → BLACKLIST → DISABLED */
	public synchronized void cycleFilterMode() {
		FilterMode[] modes = FilterMode.values();
		filterMode = modes[(filterMode.ordinal() + 1) % modes.length];
	}

	public boolean isPreciseMode() {
		return preciseMode;
	}

	public synchronized void setPreciseMode(boolean precise) {
		if (this.preciseMode == precise) return;
		this.preciseMode = precise;
		// Normalize old precise entries when returning to fuzzy mode.
		if (!precise) {
			String[] current = slots;
			String[] normalized = current.clone();
			boolean changed = false;
			for (int i = 0; i < normalized.length; i++) {
				String entry = normalized[i];
				if (entry != null && !isDirectFingerprint(entry) && entry.endsWith("#block")) {
					normalized[i] = entry.substring(0, entry.length() - 6);
					changed = true;
				}
			}
			if (changed) slots = normalized;
		}
		invalidateDirectEntries();
	}

	/** 切换精确模式 */
	public synchronized void togglePreciseMode() {
		setPreciseMode(!this.preciseMode);
	}

	/**
	 * Sets the filter entry at the given slot index (position-fixed semantics).
	 * Implementation moved to {@link Ae2InputFilterSlotOps#setEntry}.
	 */
	public synchronized void setEntryAt(int index, ResourceLocation beeType, boolean isBlock) {
		if (beeType == null || index < 0 || index >= MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String entry = preciseMode ? Ae2FilterEntrySupport.formatEntry(beeType, isBlock) : beeType.toString();
		publish(Ae2InputFilterSlotOps.setEntry(slots, ensureKeys(), directAmounts, directReserveAmounts,
				directVisibleAmounts, directUnlimited, directNetworkStock,
				index, entry, DEFAULT_DIRECT_AMOUNT));
	}

	/** Removes the entry at the given slot index (implementation moved to {@link Ae2InputFilterSlotOps#removeEntry}). */
	public synchronized void removeEntryAt(int index) {
		Ae2InputFilterSlotOps.StateArrays s = Ae2InputFilterSlotOps.removeEntry(slots, ensureKeys(), directAmounts,
				directReserveAmounts,
				directVisibleAmounts, directUnlimited, directNetworkStock, index);
		if (s != null) {
			publish(s);
		}
	}

	/** Returns the entry at the given slot index (implementation moved to {@link Ae2InputFilterQuerySupport#entryAt}). */
	public EntryInfo getEntryAt(int index) {
		return Ae2InputFilterQuerySupport.entryAt(slots, index);
	}

	/**
	 * Clears all filter entries, keeping the capacity (implementation moved to
	 * {@link Ae2InputFilterSlotOps#clearAll}).
	 */
	public synchronized void clearEntries() {
		publish(Ae2InputFilterSlotOps.clearAll(slots, ensureKeys(), directAmounts, directReserveAmounts, directVisibleAmounts,
			directUnlimited, directNetworkStock));
	}

	/** Whitelist/blacklist check for a bee type (implementation moved to {@link Ae2InputFilterQuerySupport#isAllowed}). */
	public boolean isAllowed(ResourceLocation beeType, boolean isBlock) {
		String[] currentSlots = slots;
		return Ae2InputFilterQuerySupport.isAllowed(
				beeType, isBlock, filterMode, preciseMode, getFuzzyEntries(currentSlots));
	}

	/**
	 * Grows the fixed-size arrays to at least minCapacity (filled with null/0/false).
	 * Implementation moved to {@link Ae2InputFilterSlotOps#grow}.
	 */
	public synchronized void ensureCapacity(int minCapacity) {
		if (minCapacity > MAX_FILTER_SLOTS) return;
		if (slots.length >= minCapacity) return;
		publish(Ae2InputFilterSlotOps.grow(slots, ensureKeys(), directAmounts, directReserveAmounts, directVisibleAmounts,
			directUnlimited, directNetworkStock,
			minCapacity));
	}

	/**
	 * 从原始字符串设置指定槽位的条目（实现已移至 {@link Ae2InputFilterSlotOps#setEntry}）。
	 */
	public synchronized void setEntryAtIndex(int index, String rawEntry) {
		if (index < 0 || index >= MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String entry = (rawEntry == null || rawEntry.isBlank()) ? null : rawEntry;
		long amount = isDirectFingerprint(entry) ? DEFAULT_DIRECT_AMOUNT : 0L;
		publish(Ae2InputFilterSlotOps.setEntry(slots, ensureKeys(), directAmounts, directReserveAmounts,
				directVisibleAmounts, directUnlimited, directNetworkStock,
				index, entry, amount));
	}

	/** 返回当前数组容量 */
	public int getCapacity() {
		return slots.length;
	}

	/**
	 * Non-empty slots as index+entry pairs (implementation moved to
	 * {@link Ae2InputFilterQuerySupport#nonEmptyEntries}).
	 */
	public List<IndexedEntry> getNonEmptyEntries() {
		return Ae2InputFilterQuerySupport.nonEmptyEntries(slots);
	}

	/**
	 * Stores a component-aware fingerprint supplied by {@link Ae2ItemFingerprint}
	 * (implementation moved to {@link Ae2InputFilterSlotOps#setEntry}).
	 */
	public synchronized void setDirectEntryFingerprintAt(int index, String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank() || index < 0 || index >= MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		publish(Ae2InputFilterSlotOps.setEntry(slots, ensureKeys(), directAmounts, directReserveAmounts,
				directVisibleAmounts, directUnlimited, directNetworkStock,
				index, DIRECT_ENTRY_PREFIX + fingerprint, DEFAULT_DIRECT_AMOUNT));
	}

	public AEItemKey getResolvedDirectKey(int index) {
		return Ae2InputFilterQuerySupport.resolvedDirectKey(resolvedDirectKeys, index);
	}

	public synchronized void resolveDirectKey(int index, AEItemKey key) {
		AEItemKey[] keys = ensureKeys();
		if (index < 0 || index >= keys.length || !isDirectEntry(index) || key == null) return;
		resolvedDirectKeys = Ae2InputFilterSlotOps.setKey(keys, index, key);
		invalidateDirectEntries();
	}

	public long getDirectAmountAt(int index) {
		return Ae2InputFilterQuerySupport.directAmountAt(directAmounts, index);
	}

	public synchronized void setDirectAmountAt(int index, long amount) {
		if (index < 0 || index >= directAmounts.length) return;
		directAmounts = Ae2InputFilterSlotOps.setAmount(directAmounts, index,
				Math.max(0L, Math.min(getMaxDirectAmount(), amount)));
		invalidateDirectEntries();
	}

	/** Returns the minimum AE2 network stock to keep for this exact entry. */
	public long getDirectReserveAmountAt(int index) {
		return Ae2InputFilterQuerySupport.directAmountAt(directReserveAmounts, index);
	}

	/** Updates the minimum network stock retained for this exact entry. */
	public synchronized void setDirectReserveAmountAt(int index, long amount) {
		if (index < 0 || index >= directReserveAmounts.length) return;
		directReserveAmounts = Ae2InputFilterSlotOps.setReserve(directReserveAmounts, index,
				clampDirectReserveAmount(amount));
		invalidateDirectEntries();
	}

	/** Returns the last server-provided visible AE stock for a direct entry. */
	public long getDirectVisibleAmountAt(int index) {
		return Ae2InputFilterQuerySupport.directVisibleAmountAt(directVisibleAmounts, index);
	}

	/** Updates the client stock snapshot without changing the configured request amount. */
	public synchronized void setDirectVisibleAmountAt(int index, long amount) {
		if (index < 0 || index >= directVisibleAmounts.length) return;
		directVisibleAmounts = Ae2InputFilterSlotOps.setVisible(directVisibleAmounts, index, amount);
	}

	/**
	 * Whether the direct entry at the index is unlimited (implementation moved to
	 * {@link Ae2InputFilterQuerySupport#isDirectUnlimitedAt}).
	 */
	public boolean isDirectUnlimitedAt(int index) {
		return Ae2InputFilterQuerySupport.isDirectUnlimitedAt(directUnlimited, index);
	}

	/**
	 * Toggles the unlimited flag of the direct entry at the index (implementation moved to
	 * {@link Ae2InputFilterSlotOps#toggleUnlimited}).
	 */
	public synchronized void toggleDirectUnlimitedAt(int index) {
		if (index < 0 || index >= slots.length || !isDirectFingerprint(slots[index])) return;
		directUnlimited = Ae2InputFilterSlotOps.toggleUnlimited(directUnlimited, index);
		invalidateDirectEntries();
	}

	/** Returns whether this exact entry uses the AE2 network-stock floor. */
	public boolean isDirectNetworkStockAt(int index) {
		return Ae2InputFilterQuerySupport.isDirectUnlimitedAt(directNetworkStock, index);
	}

	/** Toggles network-stock mode independently of the unlimited-pull mode. */
	public synchronized void toggleDirectNetworkStockAt(int index) {
		if (index < 0 || index >= slots.length || !isDirectFingerprint(slots[index])) return;
		directNetworkStock = Ae2InputFilterSlotOps.toggleNetworkStock(directNetworkStock, index);
		invalidateDirectEntries();
	}

	/** Sets all direct-entry request amounts (implementation moved to {@link Ae2InputFilterSlotOps#setAllAmounts}). */
	public synchronized int setAllDirectAmounts(long amount) {
		long clamped = Math.max(0L, Math.min(getMaxDirectAmount(), amount));
		Ae2InputFilterSlotOps.AmountChange change = Ae2InputFilterSlotOps.setAllAmounts(slots, directAmounts, clamped);
		if (change.changed() > 0) {
			directAmounts = change.amounts();
			invalidateDirectEntries();
		}
		return change.changed();
	}

	/** Sets the minimum retained network stock for all exact entries. */
	public synchronized int setAllDirectReserveAmounts(long amount) {
		long clamped = clampDirectReserveAmount(amount);
		Ae2InputFilterSlotOps.AmountChange change = Ae2InputFilterSlotOps.setAllReserves(slots,
				directReserveAmounts, clamped);
		if (change.changed() > 0) {
			directReserveAmounts = change.amounts();
			invalidateDirectEntries();
		}
		return change.changed();
	}

	/** Publishes a clone-modify result and invalidates the direct-entry cache (CopyOnWrite). */
	private void publish(Ae2InputFilterSlotOps.StateArrays s) {
		resolvedDirectKeys = s.keys();
		directAmounts = s.amounts();
		directReserveAmounts = s.reserves();
		directVisibleAmounts = s.visible();
		directUnlimited = s.unlimited();
		directNetworkStock = s.networkStock();
		slots = s.slots();
		invalidateDirectEntries();
	}

	/**
	 * Sets the unlimited flag of every direct entry (implementation moved to
	 * {@link Ae2InputFilterSlotOps#setAllUnlimited}).
	 */
	public synchronized int setAllDirectUnlimited(boolean unlimited) {
		Ae2InputFilterSlotOps.UnlimitedChange change = Ae2InputFilterSlotOps.setAllUnlimited(slots, directUnlimited,
			unlimited);
		if (change.changed() > 0) {
			directUnlimited = change.unlimited();
			invalidateDirectEntries();
		}
		return change.changed();
	}

	/** Sets network-stock mode for every exact entry. */
	public synchronized int setAllDirectNetworkStock(boolean networkStock) {
		Ae2InputFilterSlotOps.UnlimitedChange change = Ae2InputFilterSlotOps.setAllNetworkStock(slots,
				directNetworkStock, networkStock);
		if (change.changed() > 0) {
			directNetworkStock = change.unlimited();
			invalidateDirectEntries();
		}
		return change.changed();
	}

	public boolean isAllowed(AEItemKey key) {
		return isAllowed(key, false);
	}

	/**
	 * Direct entries match an exact AE key while NBT matching is enabled. With NBT
	 * ignored they retain bee type and, when precise mode is enabled, block form.
	 * (implementation moved to {@link Ae2InputFilterQuerySupport#isAllowed})
	 */
	public boolean isAllowed(AEItemKey key, boolean ignoreNbt) {
		return isAllowed(key, ignoreNbt, null);
	}

	public boolean isAllowed(AEItemKey key, boolean ignoreNbt, HolderLookup.Provider registries) {
		String[] currentSlots = slots;
		return Ae2InputFilterQuerySupport.isAllowed(key, filterMode, preciseMode, currentSlots,
				getFuzzyEntries(currentSlots), resolvedDirectKeys, ignoreNbt, registries);
	}

	public boolean isDirectEntry(int index) {
		return Ae2InputFilterQuerySupport.isDirectEntry(slots, index);
	}

	public boolean hasDirectEntries() {
		return !getDirectEntries().isEmpty();
	}

	/** Returns true when at least one exact entry uses live network stock. */
	public boolean hasNetworkStockEntries() {
		return Ae2InputFilterQuerySupport.hasNetworkStockEntries(slots, directNetworkStock);
	}

	/** Returns whether all filter-allowed combs use the default network stock floor. */
	public boolean isGlobalNetworkStock() {
		return globalNetworkStock;
	}

	/** Enables or disables the filter-level network stock policy. */
	public synchronized void setGlobalNetworkStock(boolean enabled) {
		globalNetworkStock = enabled;
		invalidateDirectEntries();
	}

	/** Toggles the filter-level network stock policy. */
	public synchronized void toggleGlobalNetworkStock() {
		setGlobalNetworkStock(!globalNetworkStock);
	}

	/** Returns the default stock floor for all allowed candidates. */
	public long getGlobalReserveAmount() {
		return globalReserveAmount;
	}

	/** Updates the default stock floor for all allowed candidates. */
	public synchronized void setGlobalReserveAmount(long amount) {
		globalReserveAmount = clampDirectReserveAmount(amount);
		invalidateDirectEntries();
	}

	/** True when global unlimited or at least one exact-entry unlimited flag is enabled. */
	public boolean hasUnlimitedEntries() {
		return unlimitedAllFallback || Ae2InputFilterQuerySupport.hasUnlimitedEntries(slots, directUnlimited);
	}

	/** Returns whether this specific key has opted into unlimited pulling. */
	public boolean isUnlimitedForKey(AEItemKey key, boolean ignoreNbt) {
		if (key == null || !CombFuzzyMatcher.isCombItem(key)) return false;
		if (unlimitedAllFallback) return true;
		ResourceLocation candidateBeeType = CombFuzzyMatcher.getBeeType(key);
		boolean candidateBlock = CombFuzzyMatcher.isCombBlock(key);
		String[] currentSlots = slots;
		AEItemKey[] currentKeys = resolvedDirectKeys;
		for (int i = 0; i < currentSlots.length; i++) {
			if (!Ae2InputFilter.isDirectFingerprint(currentSlots[i])
					|| i >= directUnlimited.length || !directUnlimited[i]) continue;
			AEItemKey configured = currentKeys != null && i < currentKeys.length ? currentKeys[i] : null;
			if (Ae2FilterEntrySupport.matchesDirectEntry(currentSlots[i], configured, key,
					candidateBeeType, candidateBlock, ignoreNbt, preciseMode)) return true;
		}
		return false;
	}

	public boolean hasFuzzyEntries() {
		String[] currentSlots = slots;
		for (Ae2InputFilterQuerySupport.FuzzyEntry entry : getFuzzyEntries(currentSlots)) {
			if (entry != null) return true;
		}
		return false;
	}

	public boolean isUnlimitedAllFallback() {
		return unlimitedAllFallback;
	}

	public synchronized void toggleUnlimitedAllFallback() {
		unlimitedAllFallback = !unlimitedAllFallback;
	}

	/** True when every configured entry has opted into exact network-stock mode. */
	public boolean hasOnlyNetworkStockEntries() {
		return Ae2InputFilterQuerySupport.hasOnlyNetworkStockEntries(slots, directNetworkStock);
	}

	public List<DirectEntry> getDirectEntries() {
		List<DirectEntry> cached = directEntriesCache;
		if (cached != null) return cached;
		synchronized (this) {
			cached = directEntriesCache;
			if (cached != null) return cached;
			List<DirectEntry> snapshot = List.copyOf(Ae2InputFilterQuerySupport.collectDirectEntries(
					slots, resolvedDirectKeys, directAmounts, directReserveAmounts, directUnlimited, directNetworkStock));
			directEntriesCache = snapshot;
			return snapshot;
		}
	}

	/** Replaces a client-side synchronized snapshot with one set of array publications. */
	public synchronized void replaceClientSnapshot(FilterMode mode, boolean precise,
			List<Integer> indices, List<String> entries, List<Long> amounts, List<Long> reserveAmounts,
			List<Long> visibleAmounts, List<Boolean> unlimitedFlags, List<Boolean> networkStockFlags,
			boolean unlimitedAllFallbackFlag, boolean globalNetworkStockFlag, long globalReserveAmountValue) {
		Ae2InputFilterSnapshot.Snapshot snapshot = Ae2InputFilterSnapshot.build(
				mode, precise, indices, entries, amounts, reserveAmounts, visibleAmounts, unlimitedFlags,
				networkStockFlags, slots.length);
		filterMode = snapshot.mode();
		preciseMode = snapshot.precise();
		resolvedDirectKeys = snapshot.keys();
		directAmounts = snapshot.amounts();
		directReserveAmounts = snapshot.reserves();
		directVisibleAmounts = snapshot.visible();
		directUnlimited = snapshot.unlimited();
		directNetworkStock = snapshot.networkStock();
		slots = snapshot.slots();
		unlimitedAllFallback = unlimitedAllFallbackFlag;
		globalNetworkStock = globalNetworkStockFlag;
		globalReserveAmount = clampDirectReserveAmount(globalReserveAmountValue);
		invalidateDirectEntries();
	}

	private void invalidateDirectEntries() {
		directEntriesCache = null;
	}

	private Ae2InputFilterQuerySupport.FuzzyEntry[] getFuzzyEntries(String[] currentSlots) {
		FuzzyEntriesCache cached = fuzzyEntriesCache;
		if (cached != null && cached.slots == currentSlots) return cached.entries;
		Ae2InputFilterQuerySupport.FuzzyEntry[] entries =
				Ae2InputFilterQuerySupport.compileFuzzyEntries(currentSlots);
		fuzzyEntriesCache = new FuzzyEntriesCache(currentSlots, entries);
		return entries;
	}

	private record FuzzyEntriesCache(String[] slots, Ae2InputFilterQuerySupport.FuzzyEntry[] entries) {
	}

	/**
	 * Returns true when the key matches at least one configured entry (direct or
	 * fuzzy), regardless of whitelist/blacklist mode. The puller uses this to rank
	 * marked entries ahead of unmarked ones ("mark first" AE2LT semantics).
	 * (implementation moved to {@link Ae2InputFilterQuerySupport#matchesAnyEntry})
	 */
	public boolean matchesAnyEntry(AEItemKey key, boolean ignoreNbt) {
		String[] currentSlots = slots;
		return Ae2InputFilterQuerySupport.matchesAnyEntry(key, currentSlots, getFuzzyEntries(currentSlots),
				resolvedDirectKeys, preciseMode, ignoreNbt);
	}

	/**
	 * Returns the pull cap for a configured key, using the same NBT/precise matching
	 * rules as {@link #isAllowed(AEItemKey, boolean)}. Duplicate matching entries are
	 * summed; any network-stock entry uses the current visible stock instead.
	 * Direct entries without an explicit stock override use the filter-level reserve
	 * when global stock mode is enabled.
	 * (implementation moved to {@link Ae2InputFilterQuerySupport#directPullLimit})
	 */
	public long getDirectPullLimit(AEItemKey key, long visibleStock, boolean ignoreNbt) {
		return getDirectPullLimit(key, visibleStock, ignoreNbt, null);
	}

	public long getDirectPullLimit(AEItemKey key, long visibleStock, boolean ignoreNbt,
			HolderLookup.Provider registries) {
		return Ae2InputFilterQuerySupport.directPullLimit(key, visibleStock, ignoreNbt, registries, slots, resolvedDirectKeys,
				directAmounts, directReserveAmounts, directUnlimited, directNetworkStock, preciseMode,
				globalNetworkStock, globalReserveAmount);
	}

	/** Returns admission and the effective direct pull limit from one filter-slot traversal. */
	long getPullLimitIfAllowed(AEItemKey key, long visibleStock, boolean ignoreNbt) {
		String[] currentSlots = slots;
		return Ae2InputFilterQuerySupport.pullLimitIfAllowed(key, visibleStock, ignoreNbt,
				filterMode, preciseMode, currentSlots, getFuzzyEntries(currentSlots), resolvedDirectKeys,
				directAmounts, directReserveAmounts, directUnlimited, directNetworkStock,
				unlimitedAllFallback, globalNetworkStock, globalReserveAmount);
	}

	/** Exact-key compatibility overload used by older integrations. */
	public long getDirectPullLimit(AEItemKey key, long visibleStock) {
		return getDirectPullLimit(key, visibleStock, false);
	}

	static boolean isDirectFingerprint(String entry) {
		return entry != null && entry.startsWith(DIRECT_ENTRY_PREFIX) && entry.length() > DIRECT_ENTRY_PREFIX.length();
	}

	/**
	 * 序列化到 NBT — 委托 {@link Ae2InputFilterNbtCodec#save}
	 *
	 * @param tag 目标 NBT 标签
	 */
	public synchronized void save(CompoundTag tag) {
		// synchronized：与 load/publish 的互斥保持一致，防止并发修改时
		// slots 与 directAmounts/directUnlimited 三组数组长度不一致导致撕裂读（AIOOBE）
		Ae2InputFilterNbtCodec.save(tag, filterMode, preciseMode, slots, directAmounts, directReserveAmounts,
				directUnlimited, directNetworkStock, unlimitedAllFallback, globalNetworkStock, globalReserveAmount);
	}

	/**
	 * 从 NBT 加载 — 委托 {@link Ae2InputFilterNbtCodec#load} 后一次性 volatile 发布
	 *
	 * @param tag 源 NBT 标签
	 */
	public synchronized void load(CompoundTag tag) {
		Ae2InputFilterNbtCodec.LoadResult result = Ae2InputFilterNbtCodec.load(tag);
		// 模式与精确标记始终应用（与历史行为一致）
		filterMode = result.filterMode();
		preciseMode = result.preciseMode();
		unlimitedAllFallback = result.unlimitedAllFallback();
		globalNetworkStock = result.globalNetworkStock();
		globalReserveAmount = result.globalReserveAmount();
		if (result.entriesPresent()) {
			// 一次性发布完整数组；resolvedDirectKeys 重置为未解析（null），
			// 避免 NBT 恢复路径触发 AEItemKey 数组创建（Issue #8 类加载安全）
			resolvedDirectKeys = null;
			directAmounts = result.directAmounts();
			directReserveAmounts = result.directReserveAmounts();
			directVisibleAmounts = new long[result.slots().length];
			directUnlimited = result.directUnlimited();
			directNetworkStock = result.directNetworkStock();
			slots = result.slots();
		}
		invalidateDirectEntries();
	}

	/** Resets every persisted setting when loading legacy data without a filter payload. */
	public synchronized void resetPersistentState() {
		filterMode = FilterMode.DISABLED;
		preciseMode = false;
		slots = new String[DEFAULT_CAPACITY];
		resolvedDirectKeys = null;
		directAmounts = new long[DEFAULT_CAPACITY];
		directReserveAmounts = new long[DEFAULT_CAPACITY];
		directVisibleAmounts = new long[DEFAULT_CAPACITY];
		directUnlimited = new boolean[DEFAULT_CAPACITY];
		directNetworkStock = new boolean[DEFAULT_CAPACITY];
		unlimitedAllFallback = false;
		globalNetworkStock = false;
		globalReserveAmount = 0L;
		fuzzyEntriesCache = null;
		invalidateDirectEntries();
	}

	/** 条目信息 — 解析后的过滤条目 */
	public static final class EntryInfo {
		public final ResourceLocation beeType;
		public final boolean isBlock;
		public final String directFingerprint;

		EntryInfo(ResourceLocation beeType, boolean isBlock) {
			this(beeType, isBlock, null);
		}

		EntryInfo(ResourceLocation beeType, boolean isBlock, String directFingerprint) {
			this.beeType = beeType;
			this.isBlock = isBlock;
			this.directFingerprint = directFingerprint;
		}
	}

	/** 带位置索引的条目记录 — 用于网络同步 */
	public record IndexedEntry(int index, String entry) {}

	public record DirectEntry(int index, String fingerprint, AEItemKey key, long amount, long reserveAmount,
			boolean unlimited, boolean networkStock) {}
}
