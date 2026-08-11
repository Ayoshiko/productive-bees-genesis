package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
	 * AE2 输出状态持有者 — 封装三个工厂类共有的 AE2 网格节点和缓存状态，消除约 90 行重复。
	 * <p>
	 * <b>线程安全</b>：volatile 字段保证可见性。<b>依赖隔离</b>：Object 类型避免强引用 AE2 类，
	 * 实际类型由 {@link Ae2GridNodeManager} 强制转换。
	 *
	 * @since 1.0.0
	 */
public final class Ae2OutputStateHolder {

	/** AE2 网格节点（IManagedGridNode，AE2 未安装时为 null） */
	private volatile Object ae2GridNode;

	/** AEItemKey 缓存（AeItemKeyCache，AE2 未安装时为 null） */
	private volatile Object aeItemKeyCache;

	/** 节点是否待创建（clearRemoved 时置 true，首个 server tick 时执行 connectNode） */
	private volatile boolean ae2NodePending;

	/** AE2 推送器复用缓冲区（ReusableBuffers）— Object 类型保持依赖隔离，volatile 保证可见性 */
	private volatile Object reusableBuffers;

	/** Task 21: AE2 流体推送批处理缓冲（Ae2PendingBatchBuffer）— 10 tick 累积 + AEFluidKey 合并 */
	private volatile Object pendingBatchBuffer;

	/**
	 * Task 24：按流体槽索引缓存的 AEFluidKey（Object 隔离 AE2 依赖）。
	 * <br/>
	 * {@code AEFluidKey.of(FluidStack)} 每次分配 AEFluidKey + FluidStack，
	 * 高并行工厂（多槽）在大量机器下每 tick 累积都会产生分配压力。
	 * 缓存键按 (Fluid 引用 + components hash) 失效，流体类型不变时直接复用。
	 */
	private volatile Object[] fluidPushKeyCache;
	private volatile Object[] fluidPushKeyFluidRefs;
	private volatile int[] fluidPushKeyComponentsHashes;

	// ===== Task 12：AE2 网格/存储缓存（gridChanged 回调失效，减少 256× 下高频 AE2 API 查询） =====
	/** 缓存的 AE2 网格（IGrid）/ 存储服务（IStorageService）/ ME 存储（MEStorage）。
	 *  字段类型为 Object 保持依赖隔离；由 {@link Ae2GridNodeManager#onGridChanged} 失效，
	 *  {@link Ae2GridNodeManager#getCachedGrid} 等方法未命中时查询并回填。volatile 保证跨线程可见性。 */
	private volatile Object cachedGrid;
	private volatile Object cachedStorage;
	private volatile Object cachedMeStorage;
	/** Per-game-tick direct-input stock cache; Object keeps AE2 optional at class load time. */
	private volatile Object inputInventoryViewCache;

	/** AE2 全局配置缓存（输出/流体/输入/能量注入开关，墙钟 5 秒刷新） */
	private final Ae2ConfigCache configCache = new Ae2ConfigCache();

	// ===== per-tile AE2 输出开关（与全局配置 AND 关系） =====
	/** per-tile AE2 物品输出开关（默认 true，与全局配置 AND 关系） */
	private volatile boolean aeItemOutputEnabled = true;

	/** per-tile AE2 流体输出开关（默认 true，与全局配置 AND 关系） */
	private volatile boolean aeFluidOutputEnabled = true;

	// ===== per-tile AE2 输入拉取开关与状态（与全局配置 AND 关系） =====
	/** per-tile AE2 输入拉取开关（默认 false，与全局配置 AND 关系） */
	private volatile boolean aeItemInputEnabled = false;

	/** per-tile NBT 忽略开关（默认 true） — 拉取场景下聚合所有种类更实用，故默认开启忽略 NBT 差异 */
	private volatile boolean aeInputNbtIgnore = true;

	/** 拉取触发间隔默认初始值 — 实际拉取间隔由全局配置 {@code mekCentrifugeAeInputIntervalTicks} 决定（默认 20） */
	private static final long PULL_INTERVAL_DEFAULT = 20L;

	/** 上次拉取游戏刻（AtomicLong，初始 -PULL_INTERVAL_DEFAULT 保证首次 tick 即可触发） */
	private final AtomicLong lastPullTick = new AtomicLong(-PULL_INTERVAL_DEFAULT);

	/**
	 * Task 12：内部调用计数器 — 替代 getGameTime 作为节流依据。
	 * JDTE Time Accelerator 不修改 {@code level.getGameTime()} 但多次调用 tick，导致基于 getGameTime 的节流失效。
	 * <p>
	 * 节流公式：{@code pullCallCounter - lastPullCounter >= effectiveInterval}（{@code effectiveInterval = max(intervalTicks, M)}）
	 * 才触发拉取。volatile 保证可见性，服务端单线程无需 CAS。
	 */
	private volatile long pullCallCounter = 0L;

	/** 上次实际拉取时的 pullCallCounter 值（初始 Long.MIN_VALUE/2 保证首次调用即可触发） */
	private volatile long lastPullCounter = Long.MIN_VALUE / 2;
	/**
	 * AE2LT-style input pull cooldown: success shortens the next interval
	 * (1 tick with an unlimited entry, 5 otherwise), failures back off.
	 * Advanced via pullCallCounter so acceleration mods still converge.
	 */
	private final Ae2InputCooldown inputPullCooldown = new Ae2InputCooldown();

	/**
	 * 加速倍率检测器 — 跟踪同一游戏刻内被调用的次数作为加速倍率 M。
	 * <br/>
	 * 兼容所有加速模组（JDT、加速火把、IF:Souls、JDTE、EAEP 等），无需检测具体模组。
	 * 用于自适应节流 AE2 输入拉取逻辑：间隔缩短 ×M、速率放大 ×M×processes。
	 * <p>
	 * final 字段在构造时初始化，避免每次 onUpdateServer 重新分配。
	 */
	private final TickAccelTracker tickAccelTracker = new TickAccelTracker();

	/** per-tile 输入过滤器实例（懒初始化，避免 AE2 未加载时创建） */
	private volatile Ae2InputFilter aeInputFilter;

	/** 类型轮转索引（用于 N > processCount 时按周期轮转拉取类型） */
	private volatile int typeRotationIndex = 0;

	/** 上次候选扫描的最后一个 AEItemKey；Object 保持 AE2 可选隔离。 */
	private volatile Object inputCandidateCursor;

	/** 离心机 per-tile 电力熔炼炉配方兼容开关（默认 false，与全局总开关 AND 关系） */
	private volatile boolean smeltingCompatEnabled = false;

	/** 离心机新产物优先直接写入 AE；默认关闭以保留本地输出链路。 */
	private volatile boolean centrifugeDirectAeOutputEnabled = false;

	// ===== AE2 推送退避和计数器状态（Task 2 新增，封装到独立类以控制主类行数 ≤ 500） =====
	/** 推送退避和计数器状态（per-tile 独立，封装 fluid/item backoff 与计数器） */
	private final Ae2PushStateHolder pushState = new Ae2PushStateHolder();

	// ===== 基础 getter/setter =====
	public Object getAe2GridNode() { return ae2GridNode; }
	public void setAe2GridNode(Object node) { this.ae2GridNode = node; }
	public Object getAeItemKeyCache() { return aeItemKeyCache; }
	public void setAeItemKeyCache(Object cache) { this.aeItemKeyCache = cache; }
	public boolean isAe2NodePending() { return ae2NodePending; }
	public void setAe2NodePending(boolean pending) { this.ae2NodePending = pending; }

	/**
	 * 清空所有状态（方块销毁时调用）
	 * <br/>
	 * 重置节点、缓存和待创建标志，防止方块重建后残留旧状态。
	 */
	public void clear() {
		ae2GridNode = null;
		aeItemKeyCache = null;
		ae2NodePending = false;
		reusableBuffers = null;
		// Task 12：清空 AE2 网格/存储缓存，避免方块重建后残留旧网格引用
		cachedGrid = null;
		cachedStorage = null;
		cachedMeStorage = null;
		inputInventoryViewCache = null;
		// 重置配置缓存为初始默认值，确保方块重建后不会残留旧配置
		configCache.reset();
		// 重置 per-tile 开关为默认值（与字段声明一致，参考 Mek-Energistics 默认全开）
		aeItemOutputEnabled = true;
		aeFluidOutputEnabled = true;
		// 重置 per-tile 输入拉取状态为默认值（与字段声明一致）
		aeItemInputEnabled = false;
		aeInputNbtIgnore = true;
		lastPullTick.set(-PULL_INTERVAL_DEFAULT);
		// Task 12：重置内部调用计数器，方块重建后从初始状态开始节流
		pullCallCounter = 0L;
		lastPullCounter = Long.MIN_VALUE / 2;
		inputPullCooldown.reset();
		// 修复 #4：重置加速倍率检测器，方块重建后从初始状态重新统计 multiplier
		tickAccelTracker.reset();
		// 重置过滤器实例，方块重建后通过懒初始化重新创建
		aeInputFilter = null;
		// 重置类型轮转索引，方块重建后从 0 开始轮转
		typeRotationIndex = 0;
		inputCandidateCursor = null;
		// Task 21：清空 PendingBatchBuffer（若存在），避免方块重建后残留旧累积量
		if (pendingBatchBuffer instanceof Ae2PendingBatchBuffer batchBuffer) {
			batchBuffer.reset();
		}
		pendingBatchBuffer = null;
		// Task 24：释放按槽 AEFluidKey 缓存，方块重建后从空缓存重新填充
		fluidPushKeyCache = null;
		fluidPushKeyFluidRefs = null;
		fluidPushKeyComponentsHashes = null;
		// 熔炉兼容开关重置为默认关闭（与字段声明一致）
		smeltingCompatEnabled = false;
		centrifugeDirectAeOutputEnabled = false;
		// Task 2：重置 AE2 推送退避和计数器状态（fluid/item backoff + 计数器全部归零）
		pushState.reset();
	}

	// ===== 离心机熔炉配方兼容开关（per-tile） =====

	/** 获取 per-tile 熔炉配方兼容开关 */
	public boolean isSmeltingCompatEnabled() { return smeltingCompatEnabled; }

	/** 设置 per-tile 熔炉配方兼容开关 */
	public void setSmeltingCompatEnabled(boolean enabled) { this.smeltingCompatEnabled = enabled; }

	/** 取反 per-tile 熔炉配方兼容开关 */
	public void toggleSmeltingCompatEnabled() { this.smeltingCompatEnabled = !this.smeltingCompatEnabled; }

	public boolean isCentrifugeDirectAeOutputEnabled() { return centrifugeDirectAeOutputEnabled; }
	public void setCentrifugeDirectAeOutputEnabled(boolean enabled) { centrifugeDirectAeOutputEnabled = enabled; }
	public void toggleCentrifugeDirectAeOutputEnabled() {
		centrifugeDirectAeOutputEnabled = !centrifugeDirectAeOutputEnabled;
	}

	// ===== 按槽 AEFluidKey 缓存（Task 24） =====

	/** 获取槽位缓存的 AEFluidKey（Object 类型，实际为 appeng.api.stacks.AEFluidKey） */
	public Object getCachedFluidPushKey(int index) {
		Object[] keys = fluidPushKeyCache;
		return keys != null && index < keys.length ? keys[index] : null;
	}

	/** 获取槽位缓存对应的 Fluid 引用（用于失效判断） */
	public Object getCachedFluidPushKeyFluid(int index) {
		Object[] fluids = fluidPushKeyFluidRefs;
		return fluids != null && index < fluids.length ? fluids[index] : null;
	}

	/** 获取槽位缓存对应的 components hash（用于失效判断） */
	public int getCachedFluidPushKeyComponentsHash(int index) {
		int[] hashes = fluidPushKeyComponentsHashes;
		return hashes != null && index < hashes.length ? hashes[index] : 0;
	}

	/** 更新槽位 AEFluidKey 缓存（数组不足时扩容） */
	public void setCachedFluidPushKey(int index, Object key, Object fluid, int componentsHash) {
		Object[] keys = fluidPushKeyCache;
		Object[] fluids = fluidPushKeyFluidRefs;
		int[] hashes = fluidPushKeyComponentsHashes;
		if (keys == null || index >= keys.length) {
			int length = Math.max(index + 1, 16);
			keys = Arrays.copyOf(keys != null ? keys : new Object[0], length);
			fluids = Arrays.copyOf(fluids != null ? fluids : new Object[0], length);
			hashes = Arrays.copyOf(hashes != null ? hashes : new int[0], length);
			fluidPushKeyCache = keys;
			fluidPushKeyFluidRefs = fluids;
			fluidPushKeyComponentsHashes = hashes;
		}
		keys[index] = key;
		fluids[index] = fluid;
		hashes[index] = componentsHash;
	}

	// ===== ReusableBuffers / 网格缓存访问 =====
	public Object getReusableBuffers() { return reusableBuffers; }
	public void setReusableBuffers(Object buffers) { this.reusableBuffers = buffers; }

	/** 失效 AE2 网格/存储缓存（gridChanged 回调触发） */
	public void onGridChanged() {
		cachedGrid = null;
		cachedStorage = null;
		cachedMeStorage = null;
		inputInventoryViewCache = null;
		// 模块2.1：同步失效 grid node 状态缓存，确保下次 getCachedNodeState 重新查询
		pushState.invalidateNodeStateCache();
	}

	public Object getCachedGrid() { return cachedGrid; }
	public void setCachedGrid(Object grid) { this.cachedGrid = grid; }
	public Object getCachedStorage() { return cachedStorage; }
	public void setCachedStorage(Object storage) { this.cachedStorage = storage; }
	public Object getCachedMeStorage() { return cachedMeStorage; }
	public void setCachedMeStorage(Object meStorage) { this.cachedMeStorage = meStorage; }
	public Object getInputInventoryViewCache() { return inputInventoryViewCache; }
	public void setInputInventoryViewCache(Object cache) { this.inputInventoryViewCache = cache; }

	// ===== AE2 配置缓存方法 =====

	/**
	 * 检查配置缓存是否过期 — wall clock(System.currentTimeMillis)避免 JDTE 加速下 getGameTime 不变导致永不过期。
	 * @param currentTick 已弃用,仅保留签名以避免破坏 ABI
	 * @return true 表示缓存已过期,需要刷新
	 */
	public boolean isConfigCacheStale(long currentTick) {
		return configCache.isStale();
	}

	/**
	 * 刷新 AE2 配置缓存 — 每 CONFIG_REFRESH_INTERVAL_MS ms 刷新一次。
	 * AtomicLong + CAS 保证「检查时间戳 + 写入新值 + 加载配置」原子性;配置字段 null 时按原方法默认值回退。
	 * wall clock 避免 JDTE 加速下 getGameTime 在同一 gameTick 内不变导致缓存永不过期。
	 * @param currentTick 已弃用,仅保留签名以避免破坏 ABI
	 */
	public void refreshConfigCache(long currentTick) {
		configCache.refresh();
	}

	// ===== 配置缓存 getter =====
	public boolean isCachedOutputPushEnabled() { return configCache.isOutputPushEnabled(); }
	public boolean isCachedFluidPushEnabled() { return configCache.isFluidPushEnabled(); }
	public boolean isCachedPreferAppliedFluxOverAeEnergy() { return configCache.isPreferAppliedFluxOverAeEnergy(); }
	public boolean isCachedInputPullEnabled() { return configCache.isInputPullEnabled(); }
	public int getCachedInputRatePerTick() { return configCache.getInputRatePerTick(); }
	public int getCachedInputIntervalTicks() { return configCache.getInputIntervalTicks(); }

	// ===== per-tile AE2 输出开关方法 =====
	public boolean isAeItemOutputEnabled() { return aeItemOutputEnabled; }
	public void setAeItemOutputEnabled(boolean enabled) { this.aeItemOutputEnabled = enabled; }
	public boolean isAeFluidOutputEnabled() { return aeFluidOutputEnabled; }
	public void setAeFluidOutputEnabled(boolean enabled) { this.aeFluidOutputEnabled = enabled; }

	// ===== per-tile AE2 输入拉取开关方法 =====

	/**
	 * 判断输入拉取是否启用（全局 AND per-tile）。
	 * <br/>
	 * 全局开关由配置缓存 {@link #cachedInputPullEnabled} 提供（每 100 tick 刷新），
	 * per-tile 开关由 {@link #aeItemInputEnabled} 提供，两者同时为 true 时才允许拉取。
	 *
	 * @return true 表示输入拉取已启用
	 */
	public boolean isInputPullEnabled() {
		return configCache.isInputPullEnabled() && aeItemInputEnabled;
	}

	/** 获取 per-tile AE2 输入拉取开关 */
	public boolean isAeItemInputEnabled() { return aeItemInputEnabled; }
	public void setAeItemInputEnabled(boolean enabled) { this.aeItemInputEnabled = enabled; }
	public void toggleAeItemInputEnabled() { this.aeItemInputEnabled = !this.aeItemInputEnabled; }

	/** 获取/设置/取反 per-tile NBT 忽略开关 */
	public boolean isAeInputNbtIgnore() { return aeInputNbtIgnore; }
	public void setAeInputNbtIgnore(boolean ignore) { this.aeInputNbtIgnore = ignore; }
	public void toggleAeInputNbtIgnore() { this.aeInputNbtIgnore = !this.aeInputNbtIgnore; }

	public long getLastPullTick() { return lastPullTick.get(); }
	public void updateLastPullTick(long tick) { lastPullTick.set(tick); }

	/** Task 12：递增内部调用计数器（替代 getGameTime，兼容 JDTE 加速） */
	public long incrementPullCallCounter() { return ++pullCallCounter; }
	public long getPullCallCounter() { return pullCallCounter; }
	public long getLastPullCounter() { return lastPullCounter; }
	public void updateLastPullCounter(long counter) { lastPullCounter = counter; }

	/** Current input pull cooldown in pull-call counts (AE2LT-style adaptive). */
	public int getInputPullCooldownTicks() { return inputPullCooldown.current(); }
	public void onInputPullSuccess(boolean unlimited) { inputPullCooldown.onSuccess(unlimited); }

	/** Supply-aware success overload: lengthens the interval when a normal pull missed its quota. */
	public void onInputPullSuccess(boolean unlimited, long pulledAmount, long expectedQuota) {
		inputPullCooldown.onSuccess(unlimited, pulledAmount, expectedQuota);
	}
	public void onInputPullFail(boolean unlimited) { inputPullCooldown.onFail(unlimited); }

	// ===== Task 21: PendingBatchBuffer 访问方法（AE2 流体推送批处理缓冲） =====

	/**
	 * 获取 AE2 流体推送批处理缓冲（懒初始化）
	 * <br/>
	 * 字段类型为 Object 保持 AE2 依赖隔离，调用方需 instanceof 检查后强转为
	 * {@link Ae2PendingBatchBuffer}。AE2 未安装时返回 null。
	 *
	 * @return Ae2PendingBatchBuffer 实例，或 null（未初始化或 AE2 未安装）
	 */
	public Object getPendingBatchBuffer() { return pendingBatchBuffer; }

	/**
	 * 设置 AE2 流体推送批处理缓冲
	 *
	 * @param buffer Ae2PendingBatchBuffer 实例（实际类型），可为 null
	 */
	public void setPendingBatchBuffer(Object buffer) { this.pendingBatchBuffer = buffer; }

	// ===== AE2 推送状态访问方法（Task 2 新增） =====

	/**
	 * 获取推送退避和计数器状态（per-tile 独立）
	 * <br/>
	 * 外部访问示例：{@code stateHolder.getPushState().getFluidBackoff()}
	 *
	 * @return Ae2PushStateHolder 实例（永不为 null）
	 */
	public Ae2PushStateHolder getPushState() { return pushState; }

	/**
	 * 获取加速倍率检测器
	 * <br/>
	 * 用于跟踪方块实体在同一游戏刻内被调用的次数作为加速倍率 M。
	 * 调用方需在 onUpdateServer 入口处调用 tracker.onTick(level)。
	 *
	 * @return TickAccelTracker 实例（永不为 null）
	 */
	public TickAccelTracker getTickAccelTracker() {
		return tickAccelTracker;
	}

	/**
	 * 获取并递增类型轮转索引
	 * <br/>
	 * 用于 N > processCount 时按周期轮转拉取类型，确保所有类型都有机会被拉取。
	 * <p>
	 * Task 10：去除 {@code synchronized} — 服务端单线程执行无需锁，{@link #typeRotationIndex}
	 * 已声明为 {@code volatile} 保证可见性。256× 加速下每 tick 多次调用无锁开销。
	 *
	 * @param processCount 进程数（每次轮转处理的类型数）
	 * @param total 可用类型总数
	 * @return 当前轮转起始索引（范围 [0, total)）
	 */
	public int getAndIncrementTypeRotation(int processCount, int total) {
		if (total <= 0) return 0;
		int current = typeRotationIndex % total;
		typeRotationIndex = (current + processCount) % total;
		return current;
	}

	public Object getInputCandidateCursor() { return inputCandidateCursor; }
	public void setInputCandidateCursor(Object cursor) { inputCandidateCursor = cursor; }

	/** 获取输入过滤器实例(懒初始化,DCL 保证多线程下仅创建一个) */
	public Ae2InputFilter getOrCreateInputFilter() {
		Ae2InputFilter local = aeInputFilter;
		if (local == null) {
			synchronized (this) {
				local = aeInputFilter;
				if (local == null) {
					local = new Ae2InputFilter();
					aeInputFilter = local;
				}
			}
		}
		return local;
	}

	/** 包私有 — 仅读取现有过滤器（不创建），供 NBT 编解码使用 */
	Ae2InputFilter getAeInputFilter() {
		return aeInputFilter;
	}

	/**
	 * 保存 per-tile 状态到 NBT
	 * <br/>
	 * 使用 productivebeesgenesis_ 前缀避免与其他模组 NBT 键冲突。
	 *
	 * @param tag 目标 NBT 标签
	 */
	public void savePerTileState(CompoundTag tag) {
		Ae2PerTileStateNbtCodec.save(this, tag);
	}

	/**
	 * 从 NBT 加载 per-tile 状态
	 * <br/>
	 * 注意：getBoolean 在键不存在时返回 false，但物品/流体输出默认值均为 true，
	 * 故对两个键均使用 contains 检查回退默认值 true。
	 *
	 * @param tag 源 NBT 标签
	 */
	public void loadPerTileState(CompoundTag tag) {
		Ae2PerTileStateNbtCodec.load(this, tag);
	}
}
