package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.energy.IEnergySource;
import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨 tick 复用的 AE2 推送/拉取缓冲区 — 由 {@link Ae2OutputStateHolder} 以 Object 字段持有
 * <p>
 * 从 {@code Ae2OutputPusher.ReusableBuffers} 提为顶层类（原文件 1102 行，超 500 行阈值）。
 * 职责单一：只做「对象池 + 缓存 + 版本标记」的容器，不含任何推送/拉取决策逻辑。
 * <p>
 * <b>生命周期</b>：与宿主方块实体一致，方块销毁时由 {@code Ae2OutputStateHolder.clear()} 释放。
 * <b>线程安全</b>：绝大多数字段仅服务端 tick 线程访问；能量适配器用 volatile +
 * double-checked locking，候选缓存版本标记用 volatile（网格回调可能在非 tick 线程置位）。
 */
final class Ae2PushBuffers {

	/** Reused synchronous direct-insert session for generated items and the apiary overflow buffer. */
	Ae2DirectItemPushSession directItemPushSession;

	/**
	 * 本机 insert 成本自适应记账器 — 与本缓冲区同生命周期（per-tile），
	 * 供合并路径/逐槽路径/直推会话/流体路径共享同一份 EWMA 与 tick 预算，
	 * 使「中等昂贵但极高频」的外部存储（ae2lt Matrix Port 等）也能被钳制。
	 */
	final Ae2InsertCostTracker insertCostTracker = new Ae2InsertCostTracker();

	/**
	 * 懒初始化的能量适配器 — container 引用在宿主生命周期内固定不变
	 * <p>
	 * volatile 保证多线程可见性，配合 {@link #getEnergyAdapter} 的 double-checked locking
	 * 确保仅创建一个实例。
	 */
	private volatile MekEnergyToAeSource energyAdapter;

	/** 复用的槽位条目列表 — 容量自动增长到峰值后零扩容 */
	final List<Ae2SlotEntry> entries = new ArrayList<>();
	final List<Ae2SlotEntry> entryPool = new ArrayList<>();
	int entryPoolCursor;
	int outputSlotScanCursor;

	/** 复用的 key → 槽位列表映射 — 由 {@link Ae2OutputMergePolicy} 决定是否启用 */
	final Map<AEItemKey, List<Ae2SlotEntry>> keyToEntries = new LinkedHashMap<>();
	final List<List<Ae2SlotEntry>> keyEntryListPool = new ArrayList<>();
	int keyEntryListPoolCursor;

	/** 复用的 key → 总数量映射 — 由 {@link Ae2OutputMergePolicy} 决定是否启用 */
	final Object2LongLinkedOpenHashMap<AEItemKey> keyToTotalCount = new Object2LongLinkedOpenHashMap<>();

	/** 拉取列表缓冲区 — 复用避免每 tick 分配（供 Ae2InputPuller 使用） */
	final List<Ae2InputPuller.PullEntry> pullList = new ArrayList<>();
	final List<Ae2InputPuller.PullEntry> pullEntryPool = new ArrayList<>();
	int pullEntryPoolCursor;
	final Set<AEItemKey> pullKeys = new HashSet<>();

	/** Bounded wraparound prefix for the AE2 input cursor scan. */
	final List<AEItemKey> scanPrefixKeys = new ArrayList<>();

	/** 游标扫描选中键缓冲区 — 复用避免每 tick 分配（供 Ae2InputPuller 游标扫描使用） */
	final List<AEItemKey> scanSelectedKeys = new ArrayList<>();
	final Ae2PullCandidateAmounts scanCandidateAmounts = new Ae2PullCandidateAmounts();
	/**
	 * Cached SMELTING keys observed in the AE2 inventory. Keeping this list separate
	 * lets the puller fill its bounded candidate window with SMELTING keys before
	 * falling back to combs without enumerating the network twice.
	 */
	final List<AEItemKey> scanSmeltingCandidateKeys = new ArrayList<>();
	/** Cached Productive Bees comb keys used as the lower-priority candidate group. */
	final List<AEItemKey> scanCandidateKeys = new ArrayList<>();
	volatile Object scanCandidateSource;
	volatile long scanCandidateRefreshTick = Long.MIN_VALUE;
	volatile long scanCandidateRecipeVersion = Long.MIN_VALUE;
	volatile boolean scanCandidateSmeltingEnabled;
	/** 标签表达式配置代号快照：变更后必须立即重建候选列表，而非等 10 tick 到期。 */
	volatile int scanCandidateTagGeneration = Integer.MIN_VALUE;
	/** Per-host Mekanism recipe lookup cache; released with the reusable buffers. */
	final Ae2SmeltingInputCache smeltingInputCache = new Ae2SmeltingInputCache();
	/** Per-host 标签过滤判定缓存；与配方缓存同生命周期。 */
	final Ae2TagFilterCache tagFilterCache = new Ae2TagFilterCache();

	/**
	 * Per-host 的 AEItemKey → SNBT 指纹缓存。
	 * <p>
	 * 输出账本与输入 pending 缓冲都用指纹做键，原实现每次都重新跑
	 * Codec 编码 + CompoundTag.toString（spark ejYMNQjDf7 中拉取侧 432ms + 推送侧 408ms）。
	 * 指纹只由不可变的 AEItemKey 决定，故可按 key 记忆化。
	 */
	final Ae2FingerprintCache fingerprintCache = new Ae2FingerprintCache();

	/** Per-input-slot capacity snapshot reused between pull planning and local insertion. */
	private long[] inputSlotCapacities = new long[16];

	/**
	 * 获取能量适配器（懒初始化，volatile + double-checked locking 保证线程安全）
	 * <br/>
	 * MekEnergyToAeSource 无状态，container 引用在宿主生命周期内固定不变，可安全复用。
	 * 物品推送和流体推送共享同一适配器实例。
	 *
	 * @param container 宿主的 Mekanism 能量容器
	 * @return 复用的能量适配器（包装为 AE2 {@link IEnergySource}）
	 */
	IEnergySource getEnergyAdapter(MachineEnergyContainer<?> container) {
		MekEnergyToAeSource local = energyAdapter;
		if (local == null) {
			synchronized (this) {
				local = energyAdapter;
				if (local == null) {
					local = new MekEnergyToAeSource(container);
					energyAdapter = local;
				}
			}
		}
		return local;
	}

	/** 借用拉取列表（调用方使用后应 clear，跨 tick 复用避免每 tick 分配） */
	List<Ae2InputPuller.PullEntry> borrowPullList() {
		return pullList;
	}

	void resetPullEntryPool() {
		pullEntryPoolCursor = 0;
	}

	Ae2InputPuller.PullEntry borrowPullEntry(AEItemKey key, int amount) {
		return borrowPullEntry(key, amount, false);
	}

	Ae2InputPuller.PullEntry borrowPullEntry(AEItemKey key, int amount, boolean unlimited) {
		Ae2InputPuller.PullEntry entry;
		if (pullEntryPoolCursor < pullEntryPool.size()) {
			entry = pullEntryPool.get(pullEntryPoolCursor++);
		} else {
			entry = new Ae2InputPuller.PullEntry(key, amount);
			pullEntryPool.add(entry);
			pullEntryPoolCursor++;
		}
		entry.reset(key, amount, unlimited);
		return entry;
	}

	/** Borrow the bounded cursor-wrap prefix scratch list. */
	List<AEItemKey> borrowScanPrefixKeys() {
		return scanPrefixKeys;
	}

	List<AEItemKey> borrowScanCandidateKeys() {
		return scanCandidateKeys;
	}

	List<AEItemKey> borrowScanSmeltingCandidateKeys() {
		return scanSmeltingCandidateKeys;
	}

	boolean needsScanCandidateRefresh(Object source, long gameTick, long intervalTicks,
			long recipeVersion, boolean smeltingEnabled, int tagGeneration) {
		return scanCandidateSource != source
				|| scanCandidateRefreshTick == Long.MIN_VALUE
				|| gameTick < scanCandidateRefreshTick
				|| gameTick - scanCandidateRefreshTick >= Math.max(1L, intervalTicks)
				|| scanCandidateRecipeVersion != recipeVersion
				|| scanCandidateSmeltingEnabled != smeltingEnabled
				|| scanCandidateTagGeneration != tagGeneration;
	}

	void markScanCandidateRefresh(Object source, long gameTick, long recipeVersion,
			boolean smeltingEnabled, int tagGeneration) {
		scanCandidateSource = source;
		scanCandidateRefreshTick = gameTick;
		scanCandidateRecipeVersion = recipeVersion;
		scanCandidateSmeltingEnabled = smeltingEnabled;
		scanCandidateTagGeneration = tagGeneration;
	}

	void invalidateScanCandidateCache() {
		// Grid callbacks may run off the server thread. Publish list invalidation
		// markers only; the recipe cache provides its own synchronized clear path.
		scanCandidateSource = null;
		scanCandidateRefreshTick = Long.MIN_VALUE;
		scanCandidateRecipeVersion = Long.MIN_VALUE;
		scanCandidateTagGeneration = Integer.MIN_VALUE;
		smeltingInputCache.clear();
		tagFilterCache.clear();
	}

	/** 借用游标扫描选中键缓冲区（调用方使用后应 clear，跨 tick 复用避免每 tick 分配） */
	List<AEItemKey> borrowScanSelectedKeys() {
		return scanSelectedKeys;
	}

	Ae2PullCandidateAmounts borrowScanCandidateAmounts() {
		return scanCandidateAmounts;
	}

	long[] borrowInputSlotCapacities(int requiredSize) {
		if (requiredSize > inputSlotCapacities.length) {
			int newLength = Math.max(requiredSize, inputSlotCapacities.length << 1);
			inputSlotCapacities = Arrays.copyOf(inputSlotCapacities, newLength);
		}
		return inputSlotCapacities;
	}

	Set<AEItemKey> borrowPullKeys() {
		return pullKeys;
	}
}
