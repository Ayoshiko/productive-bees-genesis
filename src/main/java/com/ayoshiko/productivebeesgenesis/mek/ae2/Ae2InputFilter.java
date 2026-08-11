package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
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
	private volatile AEItemKey[] resolvedDirectKeys = new AEItemKey[DEFAULT_CAPACITY];
	private volatile long[] directAmounts = new long[DEFAULT_CAPACITY];
	/** Client-side network stock snapshot; this is never persisted or trusted for pulls. */
	private volatile long[] directVisibleAmounts = new long[DEFAULT_CAPACITY];
	private volatile boolean[] directUnlimited = new boolean[DEFAULT_CAPACITY];
	/** Immutable direct-entry snapshot; invalidated only by configuration changes. */
	private volatile List<DirectEntry> directEntriesCache;

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
	 * 在指定位置设置过滤条目（位置固定模式的核心操作）
	 * <br/>
	 * V15：直接覆盖目标位置，不做交换或去重（位置固定语义）。
	 * 精确模式下根据 isBlock 添加 #block 后缀；非精确模式仅存储 beeType。
	 *
	 * @param index   目标位置（0-based），自动扩容
	 * @param beeType 蜜蜂类型 ID
	 * @param isBlock 是否为蜜脾块（仅 preciseMode 时区分）
	 */
	public synchronized void setEntryAt(int index, ResourceLocation beeType, boolean isBlock) {
		if (beeType == null || index < 0 || index >= MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String entry = preciseMode ? Ae2FilterEntrySupport.formatEntry(beeType, isBlock) : beeType.toString();
		String[] arr = slots.clone(); // CopyOnWrite
		AEItemKey[] keys = resolvedDirectKeys.clone();
		arr[index] = entry;
		keys[index] = null;
		long[] amounts = directAmounts.clone();
		amounts[index] = DEFAULT_DIRECT_AMOUNT;
		long[] visible = directVisibleAmounts.clone();
		visible[index] = 0L;
		boolean[] unlimited = directUnlimited.clone();
		unlimited[index] = false;
		resolvedDirectKeys = keys;
		directAmounts = amounts;
		directVisibleAmounts = visible;
		directUnlimited = unlimited;
		slots = arr; // volatile 发布
		invalidateDirectEntries();
	}

	/**
	 * 移除指定位置的过滤条目
	 * <br/>
	 * V15：仅清空该位，不移位！保证其他条目位置不变。
	 *
	 * @param index 目标位置
	 */
	public synchronized void removeEntryAt(int index) {
		AEItemKey[] keys = resolvedDirectKeys.clone();
		if (index < 0 || index >= slots.length) return;
		if (slots[index] == null) return;
		String[] arr = slots.clone();
		keys[index] = null;
		long[] amounts = directAmounts.clone();
		amounts[index] = 0L;
		long[] visible = directVisibleAmounts.clone();
		visible[index] = 0L;
		boolean[] unlimited = directUnlimited.clone();
		unlimited[index] = false;
		resolvedDirectKeys = keys;
		directAmounts = amounts;
		directVisibleAmounts = visible;
		directUnlimited = unlimited;
		arr[index] = null; // 仅清该位，不移位
		slots = arr;
		invalidateDirectEntries();
	}

	/**
	 * 获取指定位置的条目信息
	 *
	 * @param index 位置
	 * @return 条目信息，或 null（位置越界或空槽位）
	 */
	public EntryInfo getEntryAt(int index) {
		if (index < 0 || index >= slots.length || slots[index] == null) return null;
		return Ae2FilterEntrySupport.parseEntry(slots[index]);
	}

	/** 清空所有过滤条目（保留容量，全部置 null） */
	public synchronized void clearEntries() {
		AEItemKey[] keys = resolvedDirectKeys.clone();
		String[] arr = slots.clone();
		Arrays.fill(arr, null);
		Arrays.fill(keys, null);
		long[] amounts = directAmounts.clone();
		Arrays.fill(amounts, 0L);
		long[] visible = directVisibleAmounts.clone();
		Arrays.fill(visible, 0L);
		boolean[] unlimited = directUnlimited.clone();
		Arrays.fill(unlimited, false);
		resolvedDirectKeys = keys;
		directAmounts = amounts;
		directVisibleAmounts = visible;
		directUnlimited = unlimited;
		slots = arr;
		invalidateDirectEntries();
	}

	/**
	 * 判断指定蜜蜂类型是否允许拉取
	 * <br/>
	 * 根据 filterMode 和 preciseMode 判断：
	 * <ul>
	 *   <li>DISABLED：所有蜜脾都允许（返回 true）</li>
	 *   <li>WHITELIST：仅在过滤列表中的蜜蜂类型允许</li>
	 *   <li>BLACKLIST：仅在过滤列表中的蜜蜂类型禁止</li>
	 * </ul>
	 * preciseMode=true 时，蜜脾和蜜脾块分别匹配；false 时共享同一过滤条目。
	 *
	 * @param beeType 蜜蜂类型 ID
	 * @param isBlock 是否为蜜脾块
	 * @return true 表示允许拉取
	 */
	public boolean isAllowed(ResourceLocation beeType, boolean isBlock) {
		if (beeType == null) return true;
		FilterMode mode = filterMode;
		boolean precise = preciseMode;
		String[] arr = slots; // volatile read
		boolean matched = false;
		for (String configured : arr) {
			if (Ae2FilterEntrySupport.matchesFuzzyEntry(configured, beeType, isBlock, precise)) {
				matched = true;
				break;
			}
		}
		return switch (mode) {
			case WHITELIST -> matched;
			case BLACKLIST -> !matched;
			case DISABLED -> true;
		};
	}

	/**
	 * 扩容到至少 minCapacity — 新位置自动为 null
	 *
	 * @param minCapacity 最小需要的容量
	 */
	public synchronized void ensureCapacity(int minCapacity) {
		if (minCapacity > MAX_FILTER_SLOTS) return;
		if (slots.length >= minCapacity) return;
		String[] newArr = new String[minCapacity];
		System.arraycopy(slots, 0, newArr, 0, slots.length);
		AEItemKey[] newKeys = new AEItemKey[minCapacity];
		System.arraycopy(resolvedDirectKeys, 0, newKeys, 0, resolvedDirectKeys.length);
		long[] newAmounts = new long[minCapacity];
		System.arraycopy(directAmounts, 0, newAmounts, 0, directAmounts.length);
		long[] newVisible = new long[minCapacity];
		System.arraycopy(directVisibleAmounts, 0, newVisible, 0, directVisibleAmounts.length);
		boolean[] newUnlimited = new boolean[minCapacity];
		System.arraycopy(directUnlimited, 0, newUnlimited, 0, directUnlimited.length);
		slots = newArr;
		resolvedDirectKeys = newKeys;
		directAmounts = newAmounts;
		directVisibleAmounts = newVisible;
		directUnlimited = newUnlimited;
		invalidateDirectEntries();
	}

	/**
	 * 直接按 index 设置原始字符串条目（用于网络同步和升级数据恢复）
	 * <br/>
	 * 保留 #block 后缀，不做 preciseMode 转换。
	 *
	 * @param index    目标位置
	 * @param rawEntry 原始条目字符串（null/空白视为清空该位）
	 */
	public synchronized void setEntryAtIndex(int index, String rawEntry) {
		if (index < 0 || index >= MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String[] arr = slots.clone();
		AEItemKey[] keys = resolvedDirectKeys.clone();
		arr[index] = (rawEntry == null || rawEntry.isBlank()) ? null : rawEntry;
		keys[index] = null;
		long[] amounts = directAmounts.clone();
		amounts[index] = isDirectFingerprint(arr[index]) ? DEFAULT_DIRECT_AMOUNT : 0L;
		long[] visible = directVisibleAmounts.clone();
		visible[index] = 0L;
		boolean[] unlimited = directUnlimited.clone();
		unlimited[index] = false;
		resolvedDirectKeys = keys;
		directAmounts = amounts;
		directVisibleAmounts = visible;
		directUnlimited = unlimited;
		slots = arr;
		invalidateDirectEntries();
	}

	/** 返回当前数组容量 */
	public int getCapacity() {
		return slots.length;
	}

	/**
	 * 返回所有非空条目（index + entry 字符串），用于网络同步
	 *
	 * @return 非空条目列表
	 */
	public List<IndexedEntry> getNonEmptyEntries() {
		List<IndexedEntry> result = new ArrayList<>();
		String[] arr = slots; // volatile 读
		for (int i = 0; i < arr.length; i++) {
			if (arr[i] != null) {
				result.add(new IndexedEntry(i, arr[i]));
			}
		}
		return result;
	}

	/** Stores a component-aware fingerprint supplied by {@link Ae2ItemFingerprint}. */
	public synchronized void setDirectEntryFingerprintAt(int index, String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank() || index < 0 || index >= MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String[] arr = slots.clone();
		AEItemKey[] keys = resolvedDirectKeys.clone();
		arr[index] = DIRECT_ENTRY_PREFIX + fingerprint;
		keys[index] = null;
		long[] amounts = directAmounts.clone();
		amounts[index] = DEFAULT_DIRECT_AMOUNT;
		long[] visible = directVisibleAmounts.clone();
		visible[index] = 0L;
		boolean[] unlimited = directUnlimited.clone();
		unlimited[index] = false;
		resolvedDirectKeys = keys;
		directAmounts = amounts;
		directVisibleAmounts = visible;
		directUnlimited = unlimited;
		slots = arr;
		invalidateDirectEntries();
	}

	public AEItemKey getResolvedDirectKey(int index) {
		return index >= 0 && index < resolvedDirectKeys.length ? resolvedDirectKeys[index] : null;
	}

	public synchronized void resolveDirectKey(int index, AEItemKey key) {
		if (index < 0 || index >= resolvedDirectKeys.length || !isDirectEntry(index) || key == null) return;
		AEItemKey[] keys = resolvedDirectKeys.clone();
		keys[index] = key;
		resolvedDirectKeys = keys;
		invalidateDirectEntries();
	}

	public long getDirectAmountAt(int index) {
		return index >= 0 && index < directAmounts.length ? directAmounts[index] : 0L;
	}

	public synchronized void setDirectAmountAt(int index, long amount) {
		if (index < 0 || index >= directAmounts.length) return;
		long[] amounts = directAmounts.clone();
		amounts[index] = Math.max(0L, Math.min(getMaxDirectAmount(), amount));
		directAmounts = amounts;
		invalidateDirectEntries();
	}

	/** Returns the last server-provided visible AE stock for a direct entry. */
	public long getDirectVisibleAmountAt(int index) {
		return index >= 0 && index < directVisibleAmounts.length ? directVisibleAmounts[index] : 0L;
	}

	/** Updates the client stock snapshot without changing the configured request amount. */
	public synchronized void setDirectVisibleAmountAt(int index, long amount) {
		if (index < 0 || index >= directVisibleAmounts.length) return;
		long[] visible = directVisibleAmounts.clone();
		visible[index] = Math.max(0L, amount);
		directVisibleAmounts = visible;
	}

	/** 是否将指定直连条目标记为无限提供。 */
	public boolean isDirectUnlimitedAt(int index) {
		return index >= 0 && index < directUnlimited.length && directUnlimited[index];
	}

	/** 切换指定直连条目的无限提供状态；非直连条目不会改变。 */
	public synchronized void toggleDirectUnlimitedAt(int index) {
		if (index < 0 || index >= slots.length || !isDirectFingerprint(slots[index])) return;
		boolean[] unlimited = directUnlimited.clone();
		unlimited[index] = !unlimited[index];
		directUnlimited = unlimited;
		invalidateDirectEntries();
	}

	/** 批量设置全部直连条目的单次拉取数量；返回实际修改的条目数 */
	public synchronized int setAllDirectAmounts(long amount) {
		long clamped = Math.max(0L, Math.min(getMaxDirectAmount(), amount));
		String[] arr = slots;
		long[] amounts = directAmounts.clone();
		int changed = 0;
		for (int i = 0; i < arr.length; i++) {
			if (isDirectFingerprint(arr[i]) && amounts[i] != clamped) {
				amounts[i] = clamped;
				changed++;
			}
		}
		if (changed > 0) {
			directAmounts = amounts;
			invalidateDirectEntries();
		}
		return changed;
	}

	/** 批量设置全部直连条目的无限提供状态；返回实际修改的条目数 */
	public synchronized int setAllDirectUnlimited(boolean unlimited) {
		String[] arr = slots;
		boolean[] flags = directUnlimited.clone();
		int changed = 0;
		for (int i = 0; i < arr.length; i++) {
			if (isDirectFingerprint(arr[i]) && flags[i] != unlimited) {
				flags[i] = unlimited;
				changed++;
			}
		}
		if (changed > 0) {
			directUnlimited = flags;
			invalidateDirectEntries();
		}
		return changed;
	}

	public boolean isAllowed(AEItemKey key) {
		return isAllowed(key, false);
	}

	/**
	 * Direct entries match an exact AE key while NBT matching is enabled. With NBT
	 * ignored they retain bee type and, when precise mode is enabled, block form.
	 */
	public boolean isAllowed(AEItemKey key, boolean ignoreNbt) {
		return isAllowed(key, ignoreNbt, null);
	}

	public boolean isAllowed(AEItemKey key, boolean ignoreNbt, HolderLookup.Provider registries) {
		if (key == null) return false;
		FilterMode mode = filterMode;
		boolean precise = preciseMode;
		if (mode == FilterMode.DISABLED) return true;
		String[] arr = slots;
		AEItemKey[] keys = resolvedDirectKeys;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(key);
		boolean isBlock = CombFuzzyMatcher.isCombBlock(key);
		for (int i = 0; i < arr.length; i++) {
			String entry = arr[i];
			boolean matches = false;
			if (isDirectFingerprint(entry)) {
				AEItemKey configured = i < keys.length ? keys[i] : null;
				if (configured == null && registries != null) {
					configured = Ae2ItemFingerprint.decode(
							entry.substring(DIRECT_ENTRY_PREFIX.length()), registries);
				}
				matches = Ae2FilterEntrySupport.matchesDirectEntry(entry, configured, key, beeType, isBlock,
						ignoreNbt, precise);
			} else if (beeType != null) {
				matches = Ae2FilterEntrySupport.matchesFuzzyEntry(entry, beeType, isBlock, precise);
			}
			if (!matches) continue;
			return mode == FilterMode.WHITELIST;
		}
		return switch (mode) {
			case WHITELIST -> false;
			case BLACKLIST -> true;
			case DISABLED -> true;
		};
	}

	public boolean isDirectEntry(int index) {
		return index >= 0 && index < slots.length && isDirectFingerprint(slots[index]);
	}

	public boolean hasDirectEntries() {
		return !getDirectEntries().isEmpty();
	}

	/** Returns true when at least one exact entry uses live network stock. */
	public boolean hasNetworkStockEntries() {
		String[] arr = slots;
		boolean[] unlimited = directUnlimited;
		for (int i = 0; i < arr.length; i++) {
			if (isDirectFingerprint(arr[i]) && unlimited[i]) return true;
		}
		return false;
	}

	/** True when at least one direct entry has unlimited provide enabled. */
	public boolean hasUnlimitedEntries() {
		String[] arr = slots;
		boolean[] unlimited = directUnlimited;
		for (int i = 0; i < arr.length; i++) {
			if (isDirectFingerprint(arr[i]) && unlimited[i]) return true;
		}
		return false;
	}

	public boolean hasFuzzyEntries() {
		for (String entry : slots) if (entry != null && !isDirectFingerprint(entry)) return true;
		return false;
	}

	/** True when every configured entry has opted into exact network-stock mode. */
	public boolean hasOnlyNetworkStockEntries() {
		String[] arr = slots;
		boolean[] unlimited = directUnlimited;
		boolean found = false;
		for (int i = 0; i < arr.length; i++) {
			if (arr[i] == null) continue;
			if (!isDirectFingerprint(arr[i]) || !unlimited[i]) return false;
			found = true;
		}
		return found;
	}

	public List<DirectEntry> getDirectEntries() {
		List<DirectEntry> cached = directEntriesCache;
		if (cached != null) return cached;
		synchronized (this) {
			cached = directEntriesCache;
			if (cached != null) return cached;
			List<DirectEntry> result = new ArrayList<>();
			String[] arr = slots;
			AEItemKey[] keys = resolvedDirectKeys;
			for (int i = 0; i < arr.length; i++) {
				if (isDirectFingerprint(arr[i])) {
					result.add(new DirectEntry(i, arr[i].substring(DIRECT_ENTRY_PREFIX.length()), keys[i],
							directAmounts[i], directUnlimited[i]));
				}
			}
			List<DirectEntry> snapshot = List.copyOf(result);
			directEntriesCache = snapshot;
			return snapshot;
		}
	}

	/** Replaces a client-side synchronized snapshot with one set of array publications. */
	public synchronized void replaceClientSnapshot(FilterMode mode, boolean precise,
			List<Integer> indices, List<String> entries, List<Long> amounts,
			List<Long> visibleAmounts, List<Boolean> unlimitedFlags) {
		Ae2InputFilterSnapshot.Snapshot snapshot = Ae2InputFilterSnapshot.build(
				mode, precise, indices, entries, amounts, visibleAmounts, unlimitedFlags, slots.length);
		filterMode = snapshot.mode();
		preciseMode = snapshot.precise();
		resolvedDirectKeys = snapshot.keys();
		directAmounts = snapshot.amounts();
		directVisibleAmounts = snapshot.visible();
		directUnlimited = snapshot.unlimited();
		slots = snapshot.slots();
		invalidateDirectEntries();
	}

	private void invalidateDirectEntries() {
		directEntriesCache = null;
	}

	/**
	 * Returns true when the key matches at least one configured entry (direct or
	 * fuzzy), regardless of whitelist/blacklist mode. The puller uses this to rank
	 * marked entries ahead of unmarked ones ("mark first" AE2LT semantics).
	 */
	public boolean matchesAnyEntry(AEItemKey key, boolean ignoreNbt) {
		if (key == null) return false;
		String[] arr = slots;
		AEItemKey[] keys = resolvedDirectKeys;
		boolean precise = preciseMode;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(key);
		boolean isBlock = CombFuzzyMatcher.isCombBlock(key);
		for (int i = 0; i < arr.length; i++) {
			String entry = arr[i];
			if (entry == null || entry.isBlank()) continue;
			if (isDirectFingerprint(entry)) {
				AEItemKey configured = i < keys.length ? keys[i] : null;
				if (Ae2FilterEntrySupport.matchesDirectEntry(entry, configured, key, beeType, isBlock,
						ignoreNbt, precise)) return true;
			} else if (beeType != null && Ae2FilterEntrySupport.matchesFuzzyEntry(entry, beeType, isBlock, precise)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the pull cap for a configured key, using the same NBT/precise matching
	 * rules as {@link #isAllowed(AEItemKey, boolean)}. Duplicate matching entries are
	 * summed; any network-stock entry uses the current visible stock instead.
	 */
	public long getDirectPullLimit(AEItemKey key, long visibleStock, boolean ignoreNbt) {
		return getDirectPullLimit(key, visibleStock, ignoreNbt, null);
	}

	public long getDirectPullLimit(AEItemKey key, long visibleStock, boolean ignoreNbt,
			HolderLookup.Provider registries) {
		if (key == null) return -1L;
		String[] arr = slots;
		AEItemKey[] keys = resolvedDirectKeys;
		long[] amounts = directAmounts;
		boolean[] unlimited = directUnlimited;
		boolean precise = preciseMode;
		ResourceLocation candidateBeeType = CombFuzzyMatcher.getBeeType(key);
		boolean candidateBlock = CombFuzzyMatcher.isCombBlock(key);
		boolean found = false;
		boolean liveStock = false;
		long requested = 0L;
		for (int i = 0; i < arr.length; i++) {
			if (!isDirectFingerprint(arr[i])) continue;
			AEItemKey configured = i < keys.length ? keys[i] : null;
			if (configured == null && registries != null) {
				configured = Ae2ItemFingerprint.decode(
						arr[i].substring(DIRECT_ENTRY_PREFIX.length()), registries);
			}
			if (!Ae2FilterEntrySupport.matchesDirectEntry(arr[i], configured, key, candidateBeeType,
					candidateBlock, ignoreNbt, precise)) continue;
			found = true;
			if (unlimited[i]) {
				liveStock = true;
				continue;
			}
			requested = Ae2PullAmountMath.addConfigured(requested, amounts[i]);
		}
		if (!found) return -1L;
		return Ae2PullAmountMath.effectiveLimit(requested, visibleStock, liveStock,
				getMaxDirectAmount());
	}

	/** Exact-key compatibility overload used by older integrations. */
	public long getDirectPullLimit(AEItemKey key, long visibleStock) {
		return getDirectPullLimit(key, visibleStock, false);
	}

	private static String formatEntry(ResourceLocation beeType, boolean isBlock) {
		return isBlock ? beeType.toString() + "#block" : beeType.toString();
	}

	/** 解析条目字符串为 EntryInfo */
	private static EntryInfo parseEntry(String entry) {
		if (isDirectFingerprint(entry)) {
			return new EntryInfo(null, false, entry.substring(DIRECT_ENTRY_PREFIX.length()));
		}
		if (entry.endsWith("#block")) {
			String beeTypeStr = entry.substring(0, entry.length() - 6);
			ResourceLocation beeType = ResourceLocation.tryParse(beeTypeStr);
			return new EntryInfo(beeType, true);
		} else {
			ResourceLocation beeType = ResourceLocation.tryParse(entry);
			return new EntryInfo(beeType, false);
		}
	}

	static boolean isDirectFingerprint(String entry) {
		return entry != null && entry.startsWith(DIRECT_ENTRY_PREFIX) && entry.length() > DIRECT_ENTRY_PREFIX.length();
	}

	/**
	 * 序列化到 NBT — 委托 {@link Ae2InputFilterNbtCodec#save}
	 *
	 * @param tag 目标 NBT 标签
	 */
	public void save(CompoundTag tag) {
		Ae2InputFilterNbtCodec.save(tag, filterMode, preciseMode, slots, directAmounts, directUnlimited);
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
		if (result.entriesPresent()) {
			// 一次性发布完整数组
			resolvedDirectKeys = new AEItemKey[result.slots().length];
			directAmounts = result.directAmounts();
			directVisibleAmounts = new long[result.slots().length];
			directUnlimited = result.directUnlimited();
			slots = result.slots();
		}
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

	public record DirectEntry(int index, String fingerprint, AEItemKey key, long amount, boolean networkStock) {}
}
