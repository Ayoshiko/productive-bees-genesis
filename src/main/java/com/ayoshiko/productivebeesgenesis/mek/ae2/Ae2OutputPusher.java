package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
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

import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
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
 * <b>容错策略</b>：推送失败时不阻塞，由 Ejector 兜底；首次失败触发 {@link Ae2LeftoverReturner} 回送。
 * <p>
 * <b>性能优化</b>：同 key 批量合并、空输出短路、AEItemKey 缓存、{@link ReusableBuffers} 跨 tick 复用。
 */
public final class Ae2OutputPusher {

	/** 异常累计计数器 — 用于日志显示总次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong PUSH_EXCEPTION_COUNTER = new AtomicLong(0);

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

		// 1.2 TPS 自适应 — 服务器严重卡顿（TPS<10，对应 avgMspt>100ms）时跳过推送，
		//     避免加剧卡顿（与 Ae2FluidPusher 对称）。由 MEK Ejector 兜底输出。
		//     null level 守卫：仅在 tile 初始化阶段 getAe2Level() 返回 null，直接返回安全
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		double currentTps = ServerTickTimeMonitor.getInstance().getTps(level.getGameTime());
		if (currentTps < 10.0) {
			return;
		}

		// 1.3 退避检查 — 使用缓存的 pushState（消除2次冗余 getAe2StateHolder）
		long pushCounter = pushState.incrementItemPushCallCounter();
		Ae2PushBackoff itemBackoff = pushState.getItemBackoff();
		if (itemBackoff.shouldSkip(System.nanoTime())) return;

		// 1.4 批量推送短路 — 使用缓存的 holder 和 pushState（消除3次冗余 getAe2StateHolder）
		TickAccelTracker tracker = holder.getTickAccelTracker();
		int M = (tracker != null) ? tracker.getMultiplier() : 1;
		if (pushCounter - pushState.getLastItemPushCounter() < M) return;
		pushState.updateLastItemPushCounter(pushCounter);

		// 2. 空输出短路：标志位由 OutputSlotFlagManager/MekCentrifugeSlotManager O(1) 维护，
		//    AE2 推送清空槽位后 onAe2PushComplete 已调用 updateOutputSlotFlags() 保证同步
		if (!host.productivebeesgenesis$hasOutputItems()) return;

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
		IEnergySource energySource = buffers.getEnergyAdapter(host.productivebeesgenesis$getAe2EnergySource());

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
				pushedItems += tryPushSlotDirect(entry, energySource, meStorage, ACTION_SOURCE);
			}
			if (pushedItems > 0) {
				host.productivebeesgenesis$onAe2PushComplete(pushedItems);
				itemBackoff.recordSuccess();
			} else {
				// 完全失败 — 记录退避 + 诊断 + 首次失败兜底回送（取首个 key 作为代表）
				SlotEntry first = entries.get(0);
				handleCompleteFailure(host, itemBackoff, grid, storageService, meStorage,
						energySource, first.key, first.count);
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
					energySource, meStorage, ACTION_SOURCE);
		}

		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			itemBackoff.recordSuccess();
		} else if (firstKeyEntry != null) {
			// 完全失败 — 记录退避 + 诊断 + 首次失败兜底回送（取首个 key 作为代表）
			handleCompleteFailure(host, itemBackoff, grid, storageService, meStorage,
					energySource, firstKeyEntry.getKey(), firstKeyEntry.getValue());
		}
	}

	/**
	 * 完全失败处理 — 退避（nanoTime 墙钟）+ 节流日志 + 深度诊断 + 首次失败兜底回送。
	 * Task 3: 首次失败立即 30s 长退避；Task 4: 空存储检测升级到 30s。
	 */
	private static void handleCompleteFailure(IAe2OutputHostBase host, Ae2PushBackoff itemBackoff,
			IGrid grid, IStorageService storageService, MEStorage meStorage,
			IEnergySource energySource, AEItemKey itemKey, long requestedAmount) {
		// 在 recordFailure 之前判断首次失败（此时 backoffExponent 仍为 0）
		boolean firstFailure = (itemBackoff.getBackoffExponent() == 0);
		// Task 3: 256× 加速下首次失败立即 30s 长退避，跳过 1s→2s→4s→8s→16s 渐进过程
		// 原理：Grid 不稳定时渐进退避的前 5 次失败（共 31s）全部无效重试，加剧 TPS 负载
		if (firstFailure) {
			itemBackoff.recordFailureAggressive(System.nanoTime());
			LogThrottle.warn("ae2_output_long_backoff",
					"AE2 物品推送首次失败，触发 30s 长退避 item={}, count={}", itemKey, requestedAmount);
		} else {
			itemBackoff.recordFailure(System.nanoTime());
			LogThrottle.warn("ae2_output_backoff",
					"AE2 物品输出推送完全失败，进入指数退避 item={}, count={}", itemKey, requestedAmount);
		}
		// 首次失败时触发兜底回送，避免输出槽积压导致产出链停滞
		if (firstFailure) {
			returnOutputSlotsToMeOrDrop(host, meStorage, energySource);
		}
	}

	/**
	 * 输出兜底 — 遍历所有输出槽，通过 {@link Ae2LeftoverReturner} 回送到 ME 网络。
	 * 仅首次失败时调用，回插输入槽让下一轮加工消耗，避免永久积压。
	 */
	private static void returnOutputSlotsToMeOrDrop(IAe2OutputHostBase host,
			MEStorage meStorage, IEnergySource energySource) {
		Level level = host.productivebeesgenesis$getAe2Level();
		BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();
		if (level == null || pos == null) return;

		// 收集输入槽列表（回插目标）
		int processes = host.processes();
		List<IInventorySlot> inputSlots = new ArrayList<>(processes);
		for (int i = 0; i < processes; i++) {
			IInventorySlot inputSlot = host.inputSlot(i);
			if (inputSlot != null) inputSlots.add(inputSlot);
		}

		// 获取 AEItemKey 缓存
		Object cacheObj = host.productivebeesgenesis$getAeItemKeyCache();
		AeItemKeyCache keyCache = cacheObj instanceof AeItemKeyCache ? (AeItemKeyCache) cacheObj : null;

		// 遍历所有输出槽（primary/secondary/tertiary × processes）
		for (int i = 0; i < processes; i++) {
			for (int slotType = 0; slotType < 3; slotType++) {
				IInventorySlot outputSlot = getOutputSlot(host, i, slotType);
				if (outputSlot == null) continue;
				ItemStack stack = outputSlot.getStack();
				if (stack.isEmpty()) continue;

				// 获取 AEItemKey（优先使用缓存）
				AEItemKey key;
				if (keyCache != null) {
					key = keyCache.get(i * AeItemKeyCache.SLOTS_PER_PROCESS + slotType, stack);
				} else {
					key = AEItemKey.of(stack);
				}
				if (key == null) continue;

				// 调用 Ae2LeftoverReturner 回送（returnBackoff 传 null，输出侧仅由 itemBackoff 控制）
			// 注意：returnLeftoverToMe 不修改 leftover 栈的 count（内部用局部 remaining 跟踪）
			// M4-2 修复：根据返回值决定是否清空输出槽，避免部分回送失败时物品消失
			int leftoverRemaining = Ae2LeftoverReturner.returnLeftoverToMe(meStorage, key, stack,
					ACTION_SOURCE, null, energySource, level, pos, inputSlots);
			if (leftoverRemaining <= 0) {
				// 全部回送成功，清空输出槽
				outputSlot.setStack(ItemStack.EMPTY);
			} else {
				// 部分回送失败，保留未回送部分在输出槽，避免物品消失
				// 下一轮 pushOutputs 会再次尝试推送或回送
				ItemStack remainingStack = stack.copy();
				remainingStack.setCount(leftoverRemaining);
				outputSlot.setStack(remainingStack);
			}
			}
		}
	}

	/** 获取输出槽：slotType 0=primary, 1=secondary, 2=tertiary */
	private static IInventorySlot getOutputSlot(IAe2OutputHostBase host, int process, int slotType) {
		return switch (slotType) {
			case 0 -> host.primaryOutputSlot(process);
			case 1 -> host.secondaryOutputSlot(process);
			case 2 -> host.tertiaryOutputSlot(process);
			default -> null;
		};
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
	private static int tryPushSlotDirect(SlotEntry entry, IEnergySource energySource,
			MEStorage meStorage, IActionSource actionSource) {
		IInventorySlot slot = entry.slot;
		int originalCount = entry.count;
		long inserted = 0;
		try {
			inserted = StorageHelper.poweredInsert(
				energySource, meStorage, entry.key, originalCount, actionSource, Actionable.MODULATE);
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
			IEnergySource energySource, MEStorage meStorage,
			IActionSource actionSource) {
		try {
			long inserted = StorageHelper.poweredInsert(
				energySource, meStorage, key, totalCount, actionSource, Actionable.MODULATE);
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
		final Map<AEItemKey, List<SlotEntry>> keyToEntries = new ConcurrentHashMap<>();

		/** 复用的 key → 总数量映射 — 仅在 entries.size() > BATCH_MERGE_THRESHOLD 时使用 */
		final Map<AEItemKey, Long> keyToTotalCount = new ConcurrentHashMap<>();

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
