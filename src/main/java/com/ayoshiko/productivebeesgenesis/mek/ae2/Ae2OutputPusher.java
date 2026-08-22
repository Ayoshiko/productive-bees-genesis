package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
	 * AE2 输出推送器 — 将离心机输出槽物品通过 {@link StorageHelper#poweredInsert} 推送到 AE2 网络。
	 * <p>
	 * <b>推送流程</b>：集成检查 → 空输出短路 → 获取 MEStorage → 扫描输出槽按 AEItemKey 分组 →
	 * 批量 poweredInsert → 按比例清空槽位。
	 * <p>
	 * <b>容错策略</b>：只按 AE 实际接收量扣除；失败时物品留在原槽并进入短退避。
	 * <p>
	 * <b>性能优化</b>：同 key 批量合并、空输出短路、AEItemKey 缓存、{@link ReusableBuffers} 跨 tick 复用、
	 * insert 三层耗时钳制（Spark 报告实证：单次 insert 在含 EnderDrives WAL 的网络上触发主线程
	 * fsync 5-10ms）——单机预算 + 慢 insert 检测联动指数退避（含相位抖动）+
	 * {@link Ae2GlobalInsertBudget} 全服预算（防多机同 tick 集中 insert 尖峰）。
	 * 预算<b>只统计慢 insert 的超出耗时</b>：健康网络累计恒 0，推送吞吐不受任何限制。
	 */
public final class Ae2OutputPusher {

	/** 异常累计计数器 — 用于日志显示总次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong PUSH_EXCEPTION_COUNTER = new AtomicLong(0);

	/** 模块2.2：物品推送失败计数器 — 用于日志显示近5分钟累计触发次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong itemPushFailureCount = new AtomicLong(0);

	/** 模块2.2：流体推送失败计数器 — 预留供 Ae2FluidPusher 使用，当前模块未直接使用 */
	private static final AtomicLong fluidPushFailureCount = new AtomicLong(0);

	/** 模块2.4：单次推送物品数硬上限 — 超过此值强制回送 ME 网络，避免输出槽持续积压（与原版物品栈上限对齐） */

	/** 每台机器每游戏刻最多提交的不同物品键数，限制大型两页库存的 AE 网络尖峰。 */
	private static final int MAX_ITEM_KEYS_PER_TICK = 32;
	/**
	 * 单次推送剩余 key 的时间预算（纳秒）— 时间维度保护，与 key 数量限制互补。
	 * <p>
	 * Spark 依据：两份报告均显示 insert 触发的网络遍历是唯一热点，数量限制（32 key）
	 * 无法感知单次 insert 成本差异 — 病态网络 32 key × 10ms = 320ms/tick。
	 * 预算耗尽后剩余 key 顺延（复用 firstDeferredKey 轮转机制），物品留原槽无损。
	 * <p>
	 * <b>只统计慢 insert 的超出耗时</b>：与 {@link Ae2GlobalInsertBudget} 同一策略，
	 * 健康网络（单次 &lt; {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS}）累计恒 0，
	 * 32 key 满速推送不受预算限制；病态网络立即钳制。
	 */
	private static final long INSERT_TIME_BUDGET_NANOS = 1_000_000L;
	/**
	 * 连续零接收中止阈值 — 满存储专项：insert 返回 0 说明目标网络拒绝该 key，
	 * 网络状态在同 tick 内不会变化，后续 key 几乎必然同样被拒（分区存储除外，故取 3 次保守值）。
	 * 连续达到此值后 {@link DirectItemPushSession} 短路剩余推送，避免满存储下
	 * 每 tick 最多 32 次完整网络遍历（病态网络单次 5-10ms → 单机 160-320ms/tick）。
	 */
	private static final int CONSECUTIVE_ZERO_ACCEPT_LIMIT = 3;

	/**
	 * 懒加载 Holder — AE2 未安装时本类初始化不触发 {@link BaseActionSource} 类解析（Issue #8）
	 * <br/>
	 * 原静态字段 {@code ACTION_SOURCE = new BaseActionSource() {}} 在 &lt;clinit&gt; 执行，
	 * 先于任何方法体守卫，AE2 未安装时首次调用 pushOutputs 即 NoClassDefFoundError。
	 * Holder 类仅在首次访问 INSTANCE 时初始化（JVM 类加载机制保证线程安全），
	 * 而所有访问点均位于 isOutputPushEnabled 守卫之后的 AE2 路径。
	 */
	private static final class ActionSourceHolder {
		/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
		static final IActionSource INSTANCE = new BaseActionSource() {};
	}

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

		// 1.2 同 gameTick 去重 — JDTE/JDT 加速器在同一真实 tick 内多次调用时仅首次执行完整推送。
		//     注：曾设计 TPS 自适应跳过（TPS<5 时停推），因会伤害满载产出/推送效率已移除，
		//     卡顿保护改由慢 insert 预算 + 指数退避承担（只钳制病态网络，不误伤正常推送）。
		//     null level 守卫：仅在 tile 初始化阶段 getAe2Level() 返回 null，直接返回安全
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		long gameTick = level.getGameTime();
		if (!pushState.tryStartItemPush(gameTick)) return;

		// 1.3 退避检查 — 使用缓存的 pushState（消除2次冗余 getAe2StateHolder）
		long pushCounter = pushState.incrementItemPushCallCounter();
		Ae2PushBackoff itemBackoff = pushState.getItemBackoff();
		long nowNanos = System.nanoTime();
		if (itemBackoff.shouldSkip(nowNanos)) return;
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff = getOrCreateOutputKeyBackoff(holder);

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
		buffers.entryPoolCursor = 0;
		int flatSlotCount = Math.max(0, processes) * AeItemKeyCache.SLOTS_PER_PROCESS;
		int scanStart = RoundRobinSlotTraversal.normalize(buffers.outputSlotScanCursor, flatSlotCount);
		buffers.outputSlotScanCursor = RoundRobinSlotTraversal.advance(scanStart, flatSlotCount);
		for (int offset = 0; offset < flatSlotCount; offset++) {
			int flatIndex = RoundRobinSlotTraversal.index(scanStart, offset, flatSlotCount);
			int process = flatIndex / AeItemKeyCache.SLOTS_PER_PROCESS;
			int slotIdx = flatIndex % AeItemKeyCache.SLOTS_PER_PROCESS;
			collectSlot(buffers, process, slotIdx, outputSlot(host, process, slotIdx), keyCache);
		}

		if (entries.isEmpty()) return;
		// 9. 少量槽位时直接逐槽推送，避免 Map 开销
		if (!Ae2OutputMergePolicy.shouldMergeEntries(entries.size())) {
			int pushedItems = 0;
			int attemptedEntries = 0;
			SlotEntry firstAttemptedEntry = null;
			// Spark 优化：insert 耗时预算 — 病态网络单次遍历 5-10ms，预算耗尽即停止本轮，
			// 剩余槽位留原槽由下 tick outputSlotScanCursor 轮转重扫，物品无损
			long spentInsertNanos = 0L;
			boolean slowInsertDetected = false;
			int heldEntries = 0;
			for (SlotEntry entry : entries) {
				// 离心机优先：hold 物品（蜂箱蜜脾）跳过 AE 推送，保留给离心机；
				// 判定经 processability 跨 tick 缓存加速（拓扑/配方变化时失效）
				if (host.productivebeesgenesis$shouldHoldForCentrifuge(entry.stack)) {
					heldEntries++;
					continue;
				}
				// 全服预算：多台机器共享同一病态网络（EnderDrives fsync）时钳制同 tick insert 总量；
				// 预算判断前置 — 耗尽时 break 跳过后续所有 keyBackoff 查找
				if (spentInsertNanos >= INSERT_TIME_BUDGET_NANOS
						|| Ae2GlobalInsertBudget.isExhausted(gameTick)) break;
				if (keyBackoff.shouldSkip(entry.key, nowNanos)) continue;
				if (firstAttemptedEntry == null) firstAttemptedEntry = entry;
				attemptedEntries++;
				long insertStart = System.nanoTime();
				int pushed = tryPushSlotDirect(entry, meStorage, ActionSourceHolder.INSTANCE);
				long insertCost = System.nanoTime() - insertStart;
				boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
				if (slowInsert) {
					// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
					spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
					slowInsertDetected = true;
				}
				Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
				if (pushed > 0) {
					// Slot changes reset the tile-wide backoff. Preserve a key-local delay for
					// successful but pathological external-storage traversals.
					if (slowInsert) keyBackoff.recordFailure(entry.key, System.nanoTime());
					else keyBackoff.recordSuccess(entry.key);
				} else {
					keyBackoff.recordFailure(entry.key,
							slowInsert ? System.nanoTime() : nowNanos);
				}
				pushedItems = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(
						pushedItems, pushed));
			}
			if (pushedItems > 0) {
				host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			}
			// 慢 insert 优先于成功复位判定：病态网络（含 ProjectExpansion 转换接口等昂贵
			// 外部存储）insert 仍会成功（返回>0），若先 recordSuccess 再 recordFailure，
			// 指数每轮被清零、窗口恒卡 50ms，稳态 = 每 50ms 一次 5-10ms 完整网络遍历
			// （Observable 实证单机 6ms/tick）。慢 insert 禁止复位，指数累积至 1s 封顶；
			// 网络恢复（insert 变快）后一次健康成功即复位，正常吞吐不受影响。
			if (slowInsertDetected) {
				itemBackoff.recordFailure(System.nanoTime());
				logSlowInsertBackoff(itemBackoff);
			} else if (pushedItems > 0) {
				itemBackoff.recordSuccess();
			} else if (attemptedEntries > 0) {
				// 完全失败 — 记录退避 + 诊断 + 首次失败兜底回送（取首个 key 作为代表）
				handleCompleteFailure(itemBackoff,
						firstAttemptedEntry.key, firstAttemptedEntry.count);
			} else if (heldEntries > 0) {
				// 全 hold 空转退避：输出槽全为蜜脾时避免每刻重复扫描+判定（加速场景 mspt）。
				// 槽位任何变化（直连转移/新产物/玩家操作）触发 onOutputSlotContentsChanged
				// → itemBackoff.reset()，非蜜脾物品零延迟恢复推送。
				itemBackoff.recordFailure(nowNanos);
			}
			return;
		}

		// 10. 批量合并：按 AEItemKey 分组（复用 ConcurrentHashMap，clear 而非新建）
		Map<AEItemKey, List<SlotEntry>> keyToEntries = buffers.keyToEntries;
		Object2LongLinkedOpenHashMap<AEItemKey> keyToTotalCount = buffers.keyToTotalCount;
		for (List<SlotEntry> grouped : buffers.keyEntryListPool) grouped.clear();
		keyToEntries.clear();
		buffers.keyEntryListPoolCursor = 0;
		keyToTotalCount.clear();
		for (SlotEntry entry : entries) {
			List<SlotEntry> grouped = keyToEntries.get(entry.key);
			if (grouped == null) {
				if (buffers.keyEntryListPoolCursor < buffers.keyEntryListPool.size()) {
					grouped = buffers.keyEntryListPool.get(buffers.keyEntryListPoolCursor++);
				} else {
					grouped = new ArrayList<>();
					buffers.keyEntryListPool.add(grouped);
					buffers.keyEntryListPoolCursor++;
				}
				keyToEntries.put(entry.key, grouped);
			}
			grouped.add(entry);
			keyToTotalCount.put(entry.key, SaturatingMath.saturatingAdd(
					keyToTotalCount.getLong(entry.key), entry.count));
		}

		// 11. 对每个 key 调用一次 poweredInsert，按比例清空槽位
		int pushedItems = 0;
		AEItemKey firstDeferredKey = null;
		AEItemKey firstAttemptedKey = null;
		long firstAttemptedAmount = 0L;
		int attemptedKeys = 0;
		// Spark 优化：insert 耗时预算 — 与 key 数量限制同位检查，预算耗尽时当前 key 未尝试
		// 即成为 firstDeferredKey，复用既有 cursor 轮转恢复逻辑（无饥饿）
		long spentInsertNanos = 0L;
		boolean slowInsertDetected = false;
		int heldKeys = 0;
		for (Object2LongMap.Entry<AEItemKey> keyEntry : keyToTotalCount.object2LongEntrySet()) {
			if (attemptedKeys >= MAX_ITEM_KEYS_PER_TICK || spentInsertNanos >= INSERT_TIME_BUDGET_NANOS
					|| Ae2GlobalInsertBudget.isExhausted(gameTick)) {
				if (firstDeferredKey == null) firstDeferredKey = keyEntry.getKey();
				break;
			}
			AEItemKey key = keyEntry.getKey();
			long totalCount = keyEntry.getLongValue();
			// 离心机优先：hold key 整组跳过（key 级判定 — 同 key 占 N 槽只判定一次，
			// 满蜜脾 102 槽场景从 102 次判定降为 1 次；processability 缓存跨 tick 命中）。
			// hold key 不计入 attemptedKeys 也不设为 deferred（非预算顺延，是功能路由）。
			if (host.productivebeesgenesis$shouldHoldForCentrifuge(
					keyToEntries.get(key).get(0).stack)) {
				heldKeys++;
				continue;
			}
			if (keyBackoff.shouldSkip(key, nowNanos)) {
				if (firstDeferredKey == null) firstDeferredKey = key;
				continue;
			}
			if (firstAttemptedKey == null) {
				firstAttemptedKey = key;
				firstAttemptedAmount = totalCount;
			}
			long insertStart = System.nanoTime();
			int pushed = pushBatchKey(key, totalCount, keyToEntries.get(key),
					meStorage, ActionSourceHolder.INSTANCE);
			long insertCost = System.nanoTime() - insertStart;
			boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
			if (slowInsert) {
				// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
				spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
				slowInsertDetected = true;
			}
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
			if (pushed > 0) {
				if (slowInsert) keyBackoff.recordFailure(key, System.nanoTime());
				else keyBackoff.recordSuccess(key);
			} else {
				keyBackoff.recordFailure(key, slowInsert ? System.nanoTime() : nowNanos);
			}
			pushedItems = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(
					pushedItems, pushed));
			attemptedKeys++;
		}
		if (firstDeferredKey == null) {
			// Every key was attempted. Rotate one physical slot so duplicate stacks share priority over time.
			buffers.outputSlotScanCursor = RoundRobinSlotTraversal.advance(scanStart, flatSlotCount);
		} else {
			// Resume at the first physical occurrence of the first deferred key. Advancing merely by the
			// number of keys is incorrect when one key occupies several slots and can starve later pages.
			List<SlotEntry> deferredEntries = keyToEntries.get(firstDeferredKey);
			SlotEntry deferred = deferredEntries.get(0);
			buffers.outputSlotScanCursor = deferred.process * AeItemKeyCache.SLOTS_PER_PROCESS
					+ deferred.slotIdx;
		}

		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
		}
		// 慢 insert 优先于成功复位判定（与逐槽路径同语义）：病态网络 insert 仍会成功，
		// 先 recordSuccess 会每轮清零指数，窗口恒卡 50ms（每 50ms 一次 5-10ms 网络遍历）。
		// 慢 insert 禁止复位，指数累积至 1s 封顶；网络恢复（insert 变快）后一次成功即复位。
		if (slowInsertDetected) {
			itemBackoff.recordFailure(System.nanoTime());
			logSlowInsertBackoff(itemBackoff);
		} else if (pushedItems > 0) {
			itemBackoff.recordSuccess();
		} else if (firstAttemptedKey != null) {
			// 完全失败 — 记录退避 + 诊断 + 首次失败兜底回送（取首个 key 作为代表）
			handleCompleteFailure(itemBackoff, firstAttemptedKey, firstAttemptedAmount);
		} else if (heldKeys > 0) {
			// 全 hold 空转退避（与逐槽路径同语义）：输出槽全为离心机优先蜜脾时，
			// 避免每刻重复扫描+分组+判定；槽位变化即 reset，非蜜脾零延迟恢复
			itemBackoff.recordFailure(nowNanos);
		}
	}

	/**
	 * 推送单个物品栈到 AE2 网络（蜂箱输出缓冲区直推用）
	 * <br/>
	 * 复用 {@link #pushOutputs} 的开关、节点和存储守卫；失败时由调用方保留原栈。
	 *
	 * @param host  输出宿主
	 * @param stack 待推送的物品栈（不修改原栈，返回实际接收数量由调用方扣除）
	 * @return 实际推送数量；0 表示未推送或完全失败
	 */
	public static int pushItemStack(IAe2OutputHostBase host, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		DirectItemPushSession session = prepareDirectItemPush(host);
		return session == null ? 0 : session.applyAsInt(stack);
	}

	/** Resolves the AE target once so a bounded buffer drain does not repeat host/grid lookups per group. */
	@Nullable
	public static DirectItemPushSession prepareDirectItemPush(IAe2OutputHostBase host) {
		if (!host.productivebeesgenesis$isOutputPushEnabled()) return null;
		if (!host.productivebeesgenesis$isAeItemOutputEnabled()) return null;
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return null;
		Ae2PushStateHolder pushState = holder.getPushState();
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return null;
		long gameTick = level.getGameTime();
		if (pushState.getCachedNodeState(host) != Ae2GridNodeManager.STATE_ONLINE) return null;
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return null;
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return null;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return null;
		ReusableBuffers buffers = getReusableBuffers(holder, host);
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff = getOrCreateOutputKeyBackoff(holder);
		if (buffers.directItemPushSession == null) {
			buffers.directItemPushSession = new DirectItemPushSession(meStorage, keyBackoff, gameTick);
		} else {
			buffers.directItemPushSession.reset(meStorage, keyBackoff, gameTick);
		}
		return buffers.directItemPushSession;
	}

	/** Prepared, immutable insert target used for one bounded direct-output batch.
	 * <p>
	 * 满存储专项四重保护（玩家反馈：ME 磁盘满时单机 24-50ms/tick）：
	 * insert 返回 0 时 AE2 仍完整遍历网络（每个存储单元尝试后拒绝），
	 * 缓冲直推（32 组/次）与生成物直推（32 次/tick）在满存储下每 tick
	 * 触发最多 64 次完整网络遍历。会话内短路消除该浪费：
	 * <ol>
	 *   <li>耗时预算 — 累计慢 insert 超出耗时超 {@link #INSERT_TIME_BUDGET_NANOS} 后短路
	 *       （健康网络累计恒 0，满速推送不受限）</li>
	 *   <li>连续零接收中止 — 满存储时网络状态同 tick 不变，连续
	 *       {@link #CONSECUTIVE_ZERO_ACCEPT_LIMIT} 次 0 接收后短路剩余推送</li>
	 *   <li>慢 insert 检测 — 单次超 {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS} 标记，
	 *       供调用方联动整体退避（部分成功也不复位）</li>
	 *   <li>全服预算 — {@link Ae2GlobalInsertBudget} 跨机器钳制同 tick 慢 insert 总量，
	 *       多机退避到期对齐产生的集中 fsync 尖峰（物品留原槽无损顺延）</li>
	 * </ol>
	 */
	public static final class DirectItemPushSession implements ToIntFunction<ItemStack> {
		private MEStorage meStorage;
		private Ae2KeyBackoffRegistry<AEItemKey> keyBackoff;
		private long nowNanos;
		/** 会话创建时的游戏刻 — 用于全服 insert 预算的 tick 归属 */
		private long gameTick;
		private int attemptedCount;
		private int deferredCount;
		/** 本会话累计 insert 耗时（纳秒） */
		private long spentInsertNanos;
		/** 连续零接收计数 — 任一成功 insert 即清零 */
		private int zeroAcceptStreak;
		/** 是否检测到慢 insert */
		private boolean slowInsertDetected;

		private DirectItemPushSession(MEStorage meStorage, Ae2KeyBackoffRegistry<AEItemKey> keyBackoff,
				long gameTick) {
			reset(meStorage, keyBackoff, gameTick);
		}

		private void reset(MEStorage meStorage, Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, long gameTick) {
			this.meStorage = meStorage;
			this.keyBackoff = keyBackoff;
			this.nowNanos = System.nanoTime();
			this.gameTick = gameTick;
			this.attemptedCount = 0;
			this.deferredCount = 0;
			this.spentInsertNanos = 0L;
			this.zeroAcceptStreak = 0;
			this.slowInsertDetected = false;
		}

		public int attemptedCount() { return attemptedCount; }
		public int deferredCount() { return deferredCount; }

		/**
		 * 是否应触发调用方整体退避 — 慢 insert（网络遍历昂贵）或连续零接收（满存储）。
		 * <br/>
		 * 调用方据此调用 {@link Ae2PushBackoff#recordFailure(long)}，
		 * 即使本轮部分成功也保持退避（修复半满网络下"塞进 1 个物品就复位退避"的抖动漏洞）。
		 */
		public boolean shouldTriggerBackoff() {
			return slowInsertDetected || zeroAcceptStreak >= CONSECUTIVE_ZERO_ACCEPT_LIMIT;
		}

		/** 记录单次 insert 耗时到单机预算与全服预算（成功与异常路径共用） */
		private void recordInsertCost(long insertCost) {
			if (Ae2GlobalInsertBudget.isSlowOperation(insertCost)) {
				// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
				spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
				slowInsertDetected = true;
			}
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
		}

		@Override
		public int applyAsInt(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return 0;
			// 满存储/预算耗尽短路：不再发起 insert（即完整网络遍历），物品由调用方保留
			if (spentInsertNanos >= INSERT_TIME_BUDGET_NANOS
					|| zeroAcceptStreak >= CONSECUTIVE_ZERO_ACCEPT_LIMIT
					|| Ae2GlobalInsertBudget.isExhausted(gameTick)) {
				deferredCount++;
				return 0;
			}
			AEItemKey key = AEItemKey.of(stack);
			if (key == null) return 0;
			if (keyBackoff != null && keyBackoff.shouldSkip(key, nowNanos)) {
				deferredCount++;
				return 0;
			}
			attemptedCount++;
			long inserted;
			long insertStart = System.nanoTime();
			try {
				inserted = meStorage.insert(key, stack.getCount(), Actionable.MODULATE, ActionSourceHolder.INSTANCE);
			} catch (Exception e) {
				// 抛异常的 insert 恰恰最昂贵（病态网络的 fsync/转换接口），同样入账预算防止每 tick 重复全量遍历
				recordInsertCost(System.nanoTime() - insertStart);
				zeroAcceptStreak++;
				if (keyBackoff != null) keyBackoff.recordFailure(key, System.nanoTime());
				handlePushException(e, 0, 0, stack, stack.getCount());
				return 0;
			}
			long insertCost = System.nanoTime() - insertStart;
			boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
			recordInsertCost(insertCost);
			if (inserted <= 0) {
				zeroAcceptStreak++;
				if (keyBackoff != null) keyBackoff.recordFailure(key, System.nanoTime());
				LogThrottle.warnWithCooldown("ae2_buffer_push_backoff", 60_000L,
						"AE2 缓冲区物品推送失败 item={}, count={}", key, stack.getCount());
				return 0;
			}
			zeroAcceptStreak = 0;
			if (keyBackoff != null) {
				if (slowInsert) keyBackoff.recordFailure(key, System.nanoTime());
				else keyBackoff.recordSuccess(key);
			}
			return SaturatingMath.saturatingToInt(
					SaturatingMath.clampToRequest(inserted, stack.getCount()));
		}
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
	 * 慢 insert 退避日志（节流 5 分钟）— 病态网络诊断入口。
	 * <p>
	 * insert 成功但耗时超 {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS} 说明网络含
	 * 昂贵外部存储（如 ProjectExpansion 转换接口触发 ProjectE EMC 全量查询），
	 * 推送仍会完成但频率被指数退避限制。玩家可据此定位 ME 网络侧的性能问题。
	 */
	private static void logSlowInsertBackoff(Ae2PushBackoff itemBackoff) {
		LogThrottle.warnWithCooldown("ae2_slow_insert_backoff", 300_000L,
				"AE2 网络 insert 耗时异常（>{}ms），推送频率已指数退避（当前指数 {}）— "
						+ "请检查 ME 网络中的昂贵外部存储（转换接口/EnderDrives 等）",
				Ae2GlobalInsertBudget.SLOW_INSERT_NANOS / 1_000_000,
				itemBackoff.getBackoffExponent());
	}

	@SuppressWarnings("unchecked")
	private static Ae2KeyBackoffRegistry<AEItemKey> getOrCreateOutputKeyBackoff(
			Ae2OutputStateHolder holder) {
		Object cached = holder.getPushState().getOutputKeyBackoffRegistry();
		if (cached instanceof Ae2KeyBackoffRegistry<?> registry) {
			return (Ae2KeyBackoffRegistry<AEItemKey>) registry;
		}
		Ae2KeyBackoffRegistry<AEItemKey> registry = new Ae2KeyBackoffRegistry<>();
		holder.getPushState().setOutputKeyBackoffRegistry(registry);
		return registry;
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
	private static void collectSlot(ReusableBuffers buffers,
									int process, int slotIdx, @Nullable IInventorySlot slot,
									@Nullable AeItemKeyCache cache) {
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

		SlotEntry entry;
		if (buffers.entryPoolCursor < buffers.entryPool.size()) {
			entry = buffers.entryPool.get(buffers.entryPoolCursor++);
		} else {
			entry = new SlotEntry();
			buffers.entryPool.add(entry);
			buffers.entryPoolCursor++;
		}
		entry.set(slot, stack, key, stack.getCount(), process, slotIdx);
		buffers.entries.add(entry);
	}

	@Nullable
	private static IInventorySlot outputSlot(IAe2OutputHostBase host, int process, int slotIdx) {
		return switch (slotIdx) {
			case 0 -> host.primaryOutputSlot(process);
			case 1 -> host.secondaryOutputSlot(process);
			case 2 -> host.tertiaryOutputSlot(process);
			default -> null;
		};
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
			inserted = SaturatingMath.clampToRequest(
					meStorage.insert(entry.key, originalCount, Actionable.MODULATE, actionSource),
					originalCount);
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
			long inserted = SaturatingMath.clampToRequest(
					meStorage.insert(key, totalCount, Actionable.MODULATE, actionSource), totalCount);
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
		IInventorySlot slot;
		ItemStack stack;
		AEItemKey key;
		int count;
		int process;
		int slotIdx;

		SlotEntry() {
		}

		void set(IInventorySlot slot, ItemStack stack, AEItemKey key, int count, int process, int slotIdx) {
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
		/** Reused synchronous direct-insert session for generated items and the apiary overflow buffer. */
		DirectItemPushSession directItemPushSession;
		/**
		 * 懒初始化的能量适配器 — container 引用在宿主生命周期内固定不变
		 * <p>
		 * volatile 保证多线程可见性，配合 {@link #getEnergyAdapter} 的 double-checked locking
		 * 确保仅创建一个实例。
		 */
		private volatile MekEnergyToAeSource energyAdapter;

		/** 复用的槽位条目列表 — 容量自动增长到峰值后零扩容 */
		final List<SlotEntry> entries = new ArrayList<>();
		final List<SlotEntry> entryPool = new ArrayList<>();
		int entryPoolCursor;
		int outputSlotScanCursor;

		/** 复用的 key → 槽位列表映射 — 由 {@link Ae2OutputMergePolicy} 决定是否启用 */
		final Map<AEItemKey, List<SlotEntry>> keyToEntries = new LinkedHashMap<>();
		final List<List<SlotEntry>> keyEntryListPool = new ArrayList<>();
		int keyEntryListPoolCursor;

		/** 复用的 key → 总数量映射 — 由 {@link Ae2OutputMergePolicy} 决定是否启用 */
		final Object2LongLinkedOpenHashMap<AEItemKey> keyToTotalCount =
				new Object2LongLinkedOpenHashMap<>();

		/** 拉取列表缓冲区 — 复用避免每 tick 分配（供 Ae2InputPuller 使用） */
		final List<Ae2InputPuller.PullEntry> pullList = new ArrayList<>();
		final List<Ae2InputPuller.PullEntry> pullEntryPool = new ArrayList<>();
		int pullEntryPoolCursor;
		final Set<AEItemKey> pullKeys = new HashSet<>();

		/** Bounded wraparound prefix for the AE2 input cursor scan. */
		final List<AEItemKey> scanPrefixKeys = new ArrayList<>();

		/** 游标扫描选中键缓冲区 — 复用避免每 tick 分配（供 Ae2InputPuller 游标扫描使用） */
		final List<AEItemKey> scanSelectedKeys = new ArrayList<>();
		final PullCandidateAmounts scanCandidateAmounts = new PullCandidateAmounts();
		/**
		 * Cached comb keys observed in the AE2 inventory. The current KeyCounter is
		 * consulted for amounts during every pull; this list only avoids repeatedly
		 * enumerating a large network inventory while accelerated machines catch up.
		 */
		final List<AEItemKey> scanCandidateKeys = new ArrayList<>();
		volatile Object scanCandidateSource;
		volatile long scanCandidateRefreshTick = Long.MIN_VALUE;

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

		boolean needsScanCandidateRefresh(Object source, long gameTick, long intervalTicks) {
			return scanCandidateSource != source
					|| scanCandidateRefreshTick == Long.MIN_VALUE
					|| gameTick < scanCandidateRefreshTick
					|| gameTick - scanCandidateRefreshTick >= Math.max(1L, intervalTicks);
		}

		void markScanCandidateRefresh(Object source, long gameTick) {
			scanCandidateSource = source;
			scanCandidateRefreshTick = gameTick;
		}

		void invalidateScanCandidateCache() {
			// Grid callbacks may run off the server thread. Only publish invalidation
			// markers here; the next server tick owns and clears the ArrayList.
			scanCandidateSource = null;
			scanCandidateRefreshTick = Long.MIN_VALUE;
		}

		/** 借用游标扫描选中键缓冲区（调用方使用后应 clear，跨 tick 复用避免每 tick 分配） */
		List<AEItemKey> borrowScanSelectedKeys() {
			return scanSelectedKeys;
		}

		PullCandidateAmounts borrowScanCandidateAmounts() {
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

	/** Reusable primitive side table for amounts computed while scanning AE candidates. */
	static final class PullCandidateAmounts {
		private final Object2IntOpenHashMap<AEItemKey> amounts = new Object2IntOpenHashMap<>(16);

		void clear() {
			amounts.clear();
		}

		void put(AEItemKey key, int amount) {
			amounts.put(key, amount);
		}

		int get(AEItemKey key) {
			return amounts.getInt(key);
		}
	}
}
