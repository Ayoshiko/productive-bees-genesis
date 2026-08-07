package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.me.helpers.BaseActionSource;

import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * AE2 输出推送器 — 将离心机输出槽物品通过 {@link StorageHelper#poweredInsert} 推送到 AE2 网络。
 * <p>
 * <b>推送流程</b>：集成检查 → 空输出短路 → 获取 MEStorage → 扫描输出槽按 AEItemKey 分组 →
 * 批量 poweredInsert → 按比例清空槽位。
 * <p>
 * <b>容错策略</b>：只按 AE 实际接收量扣除；失败时物品留在原槽并进入短退避。
 * <p>
 * <b>性能优化</b>：同 key 批量合并、空输出短路、AEItemKey 缓存、{@link ReusableBuffers} 跨 tick 复用。
 */
public final class Ae2OutputPusher {

	/** 异常累计计数器 — 用于日志显示总次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong PUSH_EXCEPTION_COUNTER = new AtomicLong(0);

	/** 模块2.2：物品推送失败计数器 — 用于日志显示近5分钟累计触发次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong itemPushFailureCount = new AtomicLong(0);

	/** 模块2.2：流体推送失败计数器 — 预留供 Ae2FluidPusher 使用，当前模块未直接使用 */
	private static final AtomicLong fluidPushFailureCount = new AtomicLong(0);

	/** 模块2.4：单次推送物品数硬上限 — 超过此值强制回送 ME 网络，避免输出槽持续积压（与原版物品栈上限对齐） */

	/** 批量合并的最小槽位数阈值 — 非空槽位数 <= 此阈值时直接逐槽推送，避免 Map 分配开销 */
	private static final int BATCH_MERGE_THRESHOLD = 3;

	/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2OutputPusher() {}

	/**
	 * 推送宿主所有输出槽的物品到 AE2 网络
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。输出槽全空时通过 {@code hasOutputItems()} 短路。
	 * <p>
	 * 批量合并：按 AEItemKey 分组后对每个 key 调用一次 poweredInsert，减少昂贵操作调用次数。
	 * 对象复用：MekEnergyToAeAdapter、ArrayList、ConcurrentHashMap 由 {@link ReusableBuffers} 跨 tick 持有。
	 *
	 * @param host 输出宿主（离心机方块实体）
	 */
	public static void pushOutputs(IAe2OutputHostBase host) {
		// 1. 集成开关检查（由宿主决定配置源：离心机读 aeOutputEnabled，蜂箱读 apiaryAeOutputEnabled）
		//    注意：这两个接口方法可能被蜂箱子类覆盖，保持原调用方式（各内部调用1次 getAe2StateHolder）
		if (!host.productivebeesgenesis$isOutputPushEnabled()) return;
		// 1.1 per-tile 物品输出开关检查（与全局配置 AND 关系）
		if (!host.productivebeesgenesis$isAeItemOutputEnabled()) return;

		// Spark 优化：缓存 holder 和 pushState，消除后续 9 次冗余 getAe2StateHolder() 接口分发
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		Ae2PushStateHolder pushState = holder.getPushState();

		// 1.2 TPS 自适应 — 服务器严重卡顿（TPS<5，对应 avgMspt>200ms）时跳过推送，
		//     避免加剧卡顿（与 Ae2FluidPusher 对称）。由 MEK Ejector 兜底输出。
		//     null level 守卫：仅在 tile 初始化阶段 getAe2Level() 返回 null，直接返回安全
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		if (!pushState.tryStartItemPush(level.getGameTime())) return;

		// 1.3 退避检查 — 使用缓存的 pushState（消除2次冗余 getAe2StateHolder）
		long pushCounter = pushState.incrementItemPushCallCounter();
		Ae2PushBackoff itemBackoff = pushState.getItemBackoff();
		if (itemBackoff.shouldSkip(System.nanoTime())) return;

		// 同一游戏刻的重复调用已由 tryStartItemPush 合并；工厂每刻仅调用一次也不会被 M 误节流。
		pushState.updateLastItemPushCounter(pushCounter);

		// 2. 空输出短路：标志位由 OutputSlotFlagManager/MekCentrifugeSlotManager O(1) 维护，
		//    AE2 推送清空槽位后 onAe2PushComplete 已调用 updateOutputSlotFlags() 保证同步
		if (!host.productivebeesgenesis$hasOutputItems()) return;

		// 模块2.1：强检测 grid node 状态，仅当 ONLINE(3) 时继续推送
		// 状态 0/1/2: OFFLINE/NETWORK_BOOTING/MISSING_CHANNEL — 不进入 poweredInsert 路径，不触发退避
		// 使用 pushState 缓存（20 tick 刷新一次）避免每 tick 高频调用 getGridNodeState
		int nodeState = pushState.getCachedNodeState(host);
		if (nodeState != Ae2GridNodeManager.STATE_ONLINE) {
			return;
		}

		// 3. 获取已连接的网格（holder 感知重载，跳过冗余 getAe2StateHolder）
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return;

		// 4. 获取存储服务和 ME 存储（holder 感知重载，跳过冗余 getAe2StateHolder）
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;

		// 6. 获取复用缓冲区（holder 感知重载，跳过冗余 getAe2StateHolder）
		ReusableBuffers buffers = getReusableBuffers(holder, host);

		// 7. 获取 AEItemKey 缓存（Task 7：减少 AEItemKey.of(stack) 重复调用）
		Object cacheObj = host.productivebeesgenesis$getAeItemKeyCache();
		AeItemKeyCache keyCache = cacheObj instanceof AeItemKeyCache ? (AeItemKeyCache) cacheObj : null;

		// 8. 第一遍扫描：收集所有非空槽位（复用 ArrayList，clear 而非新建）
		int processes = host.processes();
		List<SlotEntry> entries = buffers.entries;
		entries.clear();
		for (int i = 0; i < processes; i++) {
			collectSlot(entries, i, 0, host.primaryOutputSlot(i), keyCache);
			collectSlot(entries, i, 1, host.secondaryOutputSlot(i), keyCache);
			collectSlot(entries, i, 2, host.tertiaryOutputSlot(i), keyCache);
		}

		if (entries.isEmpty()) return;

		// 9. 少量槽位时直接逐槽推送，避免 Map 开销
		if (entries.size() <= BATCH_MERGE_THRESHOLD) {
			int pushedItems = 0;
			for (SlotEntry entry : entries) {
				pushedItems += tryPushSlotDirect(entry, meStorage, ACTION_SOURCE);
			}
			if (pushedItems > 0) {
				host.productivebeesgenesis$onAe2PushComplete(pushedItems);
				itemBackoff.recordSuccess();
			} else {
				// 完全失败 — 记录退避 + 诊断 + 首次失败兜底回送（取首个 key 作为代表）
				SlotEntry first = entries.get(0);
				handleCompleteFailure(itemBackoff, first.key, first.count);
			}
			return;
		}

		// 10. 批量合并：按 AEItemKey 分组（复用 ConcurrentHashMap，clear 而非新建）
		Map<AEItemKey, List<SlotEntry>> keyToEntries = buffers.keyToEntries;
		Map<AEItemKey, Long> keyToTotalCount = buffers.keyToTotalCount;
		keyToEntries.clear();
		keyToTotalCount.clear();
		for (SlotEntry entry : entries) {
			// computeIfAbsent 中 lambda 可能新建子 ArrayList，复用收益有限故保持原逻辑
			keyToEntries.computeIfAbsent(entry.key, k -> new ArrayList<>()).add(entry);
			keyToTotalCount.merge(entry.key, (long) entry.count, Long::sum);
		}

		// 11. 对每个 key 调用一次 poweredInsert，按比例清空槽位
		int pushedItems = 0;
		Map.Entry<AEItemKey, Long> firstKeyEntry = null;
		for (Map.Entry<AEItemKey, Long> keyEntry : keyToTotalCount.entrySet()) {
			if (firstKeyEntry == null) firstKeyEntry = keyEntry;
			AEItemKey key = keyEntry.getKey();
			long totalCount = keyEntry.getValue();
			pushedItems += pushBatchKey(key, totalCount, keyToEntries.get(key),
					meStorage, ACTION_SOURCE);
		}

		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			itemBackoff.recordSuccess();
		} else if (firstKeyEntry != null) {
			// 完全失败 — 记录退避 + 诊断 + 首次失败兜底回送（取首个 key 作为代表）
			handleCompleteFailure(itemBackoff, firstKeyEntry.getKey(), firstKeyEntry.getValue());
		}
	}

	/**
	 * 推送单个物品栈到 AE2 网络（蜂箱输出缓冲区直推用）
	 * <br/>
	 * 复用 {@link #pushOutputs} 相同的守卫链（开关、TPS、退避、节点 ONLINE、网格/存储），
	 * 但使用独立的 M 边界计数器，避免与主输出推送互相抢占每个游戏刻的唯一推送名额。
	 * 缓冲区物品量很小（最多几十组），每次调用直接按 key 推送，不经过槽位收集。
	 *
	 * @param host  输出宿主
	 * @param stack 待推送的物品栈（不修改原栈，返回实际接收数量由调用方扣除）
	 * @return 实际推送数量；0 表示未推送或完全失败
	 */
	public static int pushItemStack(IAe2OutputHostBase host, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		if (!host.productivebeesgenesis$isOutputPushEnabled()) return 0;
		if (!host.productivebeesgenesis$isAeItemOutputEnabled()) return 0;
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return 0;
		Ae2PushStateHolder pushState = holder.getPushState();
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return 0;
		if (pushState.getCachedNodeState(host) != Ae2GridNodeManager.STATE_ONLINE) return 0;
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return 0;
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return 0;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return 0;
		AEItemKey key = AEItemKey.of(stack);
		if (key == null) return 0;
		long inserted;
		try {
			inserted = meStorage.insert(key, stack.getCount(), Actionable.MODULATE, ACTION_SOURCE);
		} catch (Exception e) {
			handlePushException(e, 0, 0, stack, stack.getCount());
			return 0;
		}
		if (inserted <= 0) {
			// Direct production failures fall back to local output slots without global backoff.
			LogThrottle.warnWithCooldown("ae2_buffer_push_backoff", 60_000L,
					"AE2 缓冲区物品推送失败 item={}, count={}", key, stack.getCount());
			return 0;
		}
		return SaturatingMath.saturatingToInt(inserted);
	}

	/**
	 * 完全失败处理：仅记录短指数退避和聚合日志，不搬运输出槽，也不暂停输入。
	 */
	private static void handleCompleteFailure(Ae2PushBackoff itemBackoff,
			AEItemKey itemKey, long requestedAmount) {
		long failureCount = itemPushFailureCount.incrementAndGet();
		itemBackoff.recordFailure(System.nanoTime());
		LogThrottle.warnWithCooldown("ae2_output_backoff", 300_000L,
				"AE2 物品输出推送完全失败，进入短退避 item={}, count={}, 近5分钟累计 {} 次",
				itemKey, requestedAmount, failureCount);
	}

	/**
	 * 获取宿主的复用缓冲区（懒初始化）
	 * <br/>
	 * 缓冲区存储在 {@link Ae2OutputStateHolder} 中，生命周期与宿主一致。
	 * 方块销毁时由 {@link Ae2OutputStateHolder#clear()} 自动释放。
	 * <p>
	 * 包级可见：供 {@link Ae2FluidPusher} 复用能量适配器，避免每 tick 创建临时对象。
	 */
	static ReusableBuffers getReusableBuffers(IAe2OutputHostBase host) {
		return getReusableBuffers(host.productivebeesgenesis$getAe2StateHolder(), host);
	}

	/**
	 * 获取宿主的复用缓冲区（holder 感知重载）
	 * <br/>
	 * Spark 优化：跳过冗余 {@code getAe2StateHolder()} 接口分发，直接使用调用方已缓存的 holder。
	 *
	 * @param holder 已缓存的 AE2 状态持有者
	 * @param host   输出宿主（保留参数以与 {@link Ae2GridNodeManager} 重载模式一致）
	 */
	static ReusableBuffers getReusableBuffers(Ae2OutputStateHolder holder, IAe2OutputHostBase host) {
		Object obj = holder.getReusableBuffers();
		if (obj instanceof ReusableBuffers buffers) return buffers;
		ReusableBuffers buffers = new ReusableBuffers();
		holder.setReusableBuffers(buffers);
		return buffers;
	}

	/**
	 * 收集非空槽位到列表
	 */
	private static void collectSlot(List<SlotEntry> entries, int process, int slotIdx,
									@Nullable IInventorySlot slot, @Nullable AeItemKeyCache cache) {
		if (slot == null) return;
		ItemStack stack = slot.getStack();
		if (stack.isEmpty()) return;

		AEItemKey key;
		if (cache != null) {
			key = cache.get(process * AeItemKeyCache.SLOTS_PER_PROCESS + slotIdx, stack);
		} else {
			key = AEItemKey.of(stack);
		}
		if (key == null) return;

		entries.add(new SlotEntry(slot, stack, key, stack.getCount(), process, slotIdx));
	}

	/**
	 * 直接推送单个槽位（少量槽位场景，避免 Map 开销）
	 */
	private static int tryPushSlotDirect(SlotEntry entry,
			MEStorage meStorage, IActionSource actionSource) {
		IInventorySlot slot = entry.slot;
		int originalCount = entry.count;
		long inserted = 0;
		try {
			inserted = meStorage.insert(entry.key, originalCount, Actionable.MODULATE, actionSource);
			if (inserted <= 0) {
				return 0;
			}

			if (inserted >= originalCount) {
				slot.setStack(ItemStack.EMPTY);
			} else {
				ItemStack current = slot.getStack();
				if (current.isEmpty()) return (int) inserted;
				current.shrink((int) inserted);
				slot.setStack(current);
			}
			return (int) inserted;
		} catch (Exception e) {
			handlePushException(e, entry.process, entry.slotIdx, entry.stack, originalCount);
			// v9-L4 修复：poweredInsert 已成功但 setStack 异常时，物品已进入 AE2 不可撤回。
			// 返回已插入量防止调用方重试导致物品复制；未插入时返回 0
			return SaturatingMath.saturatingToInt(inserted);
		}
	}

	/**
	 * 批量推送相同 key 的多个槽位
	 * <p>
	 * 对合并后的 totalCount 调用一次 poweredInsert，然后按顺序清空槽位。
	 * 部分成功时从第一个槽位开始依次清空，直到分配完 inserted 数量。
	 */
	private static int pushBatchKey(AEItemKey key, long totalCount, List<SlotEntry> slotEntries,
			MEStorage meStorage,
			IActionSource actionSource) {
		try {
			long inserted = meStorage.insert(key, totalCount, Actionable.MODULATE, actionSource);
			if (inserted <= 0) {
				return 0;
			}

			// 按顺序清空槽位
			long remaining = inserted;
			for (SlotEntry entry : slotEntries) {
				if (remaining <= 0) break;
				IInventorySlot slot = entry.slot;
				try {
					ItemStack current = slot.getStack();
					if (current.isEmpty()) continue;
					int toRemove = (int) Math.min(current.getCount(), remaining);
					if (toRemove <= 0) continue;
					if (toRemove >= current.getCount()) {
						slot.setStack(ItemStack.EMPTY);
					} else {
						current.shrink(toRemove);
						slot.setStack(current);
					}
					remaining -= toRemove;
				} catch (Exception e) {
					// 单个槽位清空异常不影响其他槽位
					handlePushException(e, entry.process, entry.slotIdx, entry.stack, entry.count);
				}
			}
			return SaturatingMath.saturatingToInt(inserted);
		} catch (Exception e) {
			// v9-L1 修复：外层 catch 仅在 poweredInsert 抛出时触发（内层循环异常已被内层 catch 处理），
			// 此时槽位尚未被修改，无需回滚。移除死回滚代码避免误导。
			SlotEntry first = slotEntries.get(0);
			handlePushException(e, first.process, first.slotIdx, first.stack, first.count);
			return 0;
		}
	}

	/**
	 * 异常处理：限流日志 + NPE→ERROR/其他→WARN + 恢复中断状态。
	 * <br/>
	 * M9 修复：原原子计数器节流（1+1024n 触发）在 256× 加速下单 tick 可达 1024 次异常，
	 * 导致每 tick 刷屏。改用 LogThrottle 时间维度节流（5 秒内同 key 仅首条）。
	 * NPE 用 error key，其他异常用 warn key，分别节流避免相互覆盖。
	 */
	private static void handlePushException(Exception e, int process, int slotIdx,
											ItemStack stack, int originalCount) {
		long count = PUSH_EXCEPTION_COUNTER.incrementAndGet();
		// M9: 时间维度节流替代计数器节流，避免高频刷屏
		if (e instanceof NullPointerException) {
			LogThrottle.error("ae2_push_npe_exception",
					"AE2 推送 NPE 异常 (累计 {} 次,5秒内仅首条输出) - process={}, slotIdx={}, item={}: {}",
					count, process, slotIdx, stack.getItem(), e.toString());
		} else {
			LogThrottle.warn("ae2_push_exception",
					"AE2 推送异常 (累计 {} 次,5秒内仅首条输出) - process={}, slotIdx={}, item={}, count={}: {}",
					count, process, slotIdx, stack.getItem(), originalCount, e.toString());
		}
		if (e instanceof InterruptedException) Thread.currentThread().interrupt();
	}

	/**
	 * 槽位条目 — 缓存扫描结果，避免重复读取
	 */
	private static final class SlotEntry {
		final IInventorySlot slot;
		final ItemStack stack;
		final AEItemKey key;
		final int count;
		final int process;
		final int slotIdx;

		SlotEntry(IInventorySlot slot, ItemStack stack, AEItemKey key, int count, int process, int slotIdx) {
			this.slot = slot;
			this.stack = stack;
			this.key = key;
			this.count = count;
			this.process = process;
			this.slotIdx = slotIdx;
		}
	}

	/**
	 * 跨 tick 复用的缓冲区 — 由 {@link Ae2OutputStateHolder} 持有。
	 * energyAdapter 使用 volatile + double-checked locking 保证线程安全。
	 */
	static final class ReusableBuffers {
		/**
		 * 懒初始化的能量适配器 — container 引用在宿主生命周期内固定不变
		 * <p>
		 * volatile 保证多线程可见性，配合 {@link #getEnergyAdapter} 的 double-checked locking
		 * 确保仅创建一个实例。
		 */
		private volatile MekEnergyToAeSource energyAdapter;

		/** 复用的槽位条目列表 — 容量自动增长到峰值后零扩容 */
		final List<SlotEntry> entries = new ArrayList<>();

		/** 复用的 key → 槽位列表映射 — 仅在 entries.size() > BATCH_MERGE_THRESHOLD 时使用 */
		final Map<AEItemKey, List<SlotEntry>> keyToEntries = new java.util.HashMap<>();

		/** 复用的 key → 总数量映射 — 仅在 entries.size() > BATCH_MERGE_THRESHOLD 时使用 */
		final Map<AEItemKey, Long> keyToTotalCount = new java.util.HashMap<>();

		/** 拉取列表缓冲区 — 复用避免每 tick 分配（供 Ae2InputPuller 使用） */
		final List<Ae2InputPuller.PullEntry> pullList = new ArrayList<>();

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
	}
}
