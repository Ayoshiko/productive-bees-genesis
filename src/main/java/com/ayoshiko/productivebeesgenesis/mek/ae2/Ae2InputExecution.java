package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.inventory.BulkItemPullScope;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 服务器线程上的输入执行轮：公平车道规划、实时库存保留线和有凭据的精确交付。 */
final class Ae2InputExecution {
	private static final Comparator<PullEntry> PULL_ENTRY_ORDER = (a, b) -> {
		if (a.marked != b.marked) return Boolean.compare(b.marked, a.marked);
		if (a.smelting != b.smelting) return Boolean.compare(b.smelting, a.smelting);
		if (a.combBlock != b.combBlock) return Boolean.compare(b.combBlock, a.combBlock);
		return Long.compare(a.servedInWindow, b.servedInWindow);
	};
	private static final AtomicLong PULL_EXCEPTION_COUNTER = new AtomicLong();

	private Ae2InputExecution() {}

	record Result(long totalPulled, boolean unlimitedAttempted, boolean unlimitedSucceeded,
			boolean networkWorkDeferred) {}

	/** 预算等待是中性结果，不能扩大本机退避。 */
	record BatchResult(int insertedCount, long extractCostNanos,
			boolean slowExtract, boolean degraded, boolean healthy) {
		static BatchResult skipped() {
			return new BatchResult(0, 0L, false, false, false);
		}
	}

	static Result execute(IAe2InputHost host, Ae2OutputStateHolder holder, Ae2PushBuffers buffers,
			List<PullEntry> pullList, List<IInventorySlot> inputSlots, MEStorage meStorage,
			Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, Ae2InputFairnessScheduler fairness,
			long inputCapacity, long normalQuota, long perSlotQuota, IActionSource actionSource) {
		Level level = host.productivebeesgenesis$getAe2Level();
		long currentTick = level.getGameTime();
		BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();
		Ae2PushBackoff inputBackoff = holder.getPushState().getInputBackoff();
		int processCount = inputSlots.size();
		long nanoNow = System.nanoTime();
		// 准入、分类、无限提供与库存保留线均复用候选收集阶段的结果；熔炼条目优先于蜜脾。
		for (PullEntry entry : pullList) {
			entry.servedInWindow = fairness.served(entry.key);
		}
		if (pullList.size() > 1) pullList.sort(PULL_ENTRY_ORDER);

		long totalPulled = 0;
		long normalPulled = 0;
		boolean unlimitedAttempted = false;
		boolean unlimitedSucceeded = false;
		boolean slowExtractDetected = false;
		boolean degradedExtractDetected = false;
		boolean healthyExtractDetected = false;
		boolean networkWorkDeferred = false;
		long maxSlowExtractCost = 0L;
		int typeCount = pullList.size();
		int slotStart = holder.getPushState().getAndAdvanceInputSlotRotation(processCount);
		long[] inputSlotCapacities = buffers.borrowInputSlotCapacities(processCount);
		// 余量恢复结束后采集；执行中每次交付立即刷新该车道。
		Ae2InputLaneSnapshot laneSnapshot = buffers.inputLaneSnapshot();
		laneSnapshot.capture(inputSlots, processCount, host instanceof BlockEntity entity ? entity : null);
		// 公平轮先分配份额，补齐轮只在份额确实截断需求时借用余量。
		int laneBudget = Ae2InputLaneFairness.emptyLaneBudget(processCount, typeCount);
		long fairShare = Ae2InputLaneFairness.typeQuotaShare(normalQuota, typeCount);
		int passes = typeCount > 1 ? 2 : 1;
		boolean fairPassTruncated = false;
		// 预算跨公平轮与补齐轮累计，未提取的类型留待后续拉取。
		boolean extractBudgetExhausted = false;
		for (PullEntry entry : pullList) {
			entry.beginComponentMatchCache(processCount);
		}
		for (int pass = 0; pass < passes && totalPulled < inputCapacity && !holder.getPendingItemBuffer().isBlocked(); pass++) {
			boolean fairPass = pass == 0 && typeCount > 1;
			boolean protectLanes = typeCount > 1 && (fairPass
					|| hasUnassignedDemand(pullList, laneSnapshot, processCount));
			// 补齐轮只在公平轮确实被上限截断时才跑：否则会为每个类型重复一遍
			// 逐槽 SIMULATE 探测（processCount × typeCount 次），纯属浪费。
			if (!Ae2InputLaneFairness.shouldRunPass(pass, fairPassTruncated)) break;
			for (int typeOffset = 0; typeOffset < typeCount && totalPulled < inputCapacity && !holder.getPendingItemBuffer().isBlocked(); typeOffset++) {
				PullEntry entry = pullList.get(typeOffset);
				if (entry.remaining <= 0 || keyBackoff.shouldSkip(entry.key, nanoNow)) continue;
				if (entry.resourceDeferred) {
					networkWorkDeferred = true;
					continue;
				}

				// 在逐槽规划前检查累计耗时，同时考虑本机本刻已发生的输出成本。
				if (!buffers.extractBudget.canExtractNow(currentTick, typeCount,
						buffers.insertCostTracker.tileSpentThisTickNanos(currentTick))) {
					extractBudgetExhausted = true;
					break;
				}

				boolean indexed = laneSnapshot.isIndexed();
				long candidateLanes = laneSnapshot.candidateLanes(entry.key);
				if (indexed && candidateLanes == 0L) continue;

				// 每个候选只构造一个探针，并复用当前规划轮的完整组件匹配缓存。
				ItemStack slotProbe = entry.key.toStack(1);
				Item keyItem = entry.key.getItem();
				int typeSlotStart = laneSnapshot.preferredStart(entry.key, slotStart);
				long typeAvailable = 0L;
				int occupiedLanes = protectLanes ? laneSnapshot.occupiedLaneCount(entry.key) : 0;
				int newLaneBudget = Ae2InputLaneFairness.remainingLaneBudget(laneBudget, occupiedLanes);
				int newLanes = 0;
				boolean lanesSuppressed = false;
				if (!indexed) Arrays.fill(inputSlotCapacities, 0, processCount, 0L);
				long deliveryLanes = 0L;
				int slotOffset = 0;
				while (indexed ? candidateLanes != 0L : slotOffset < processCount) {
					int slotIdx;
					if (indexed) {
						long afterStart = candidateLanes & (-1L << typeSlotStart);
						slotIdx = Long.numberOfTrailingZeros(afterStart == 0L ? candidateLanes : afterStart);
						candidateLanes &= ~(1L << slotIdx);
					} else {
						slotIdx = (typeSlotStart + slotOffset++) % processCount;
					}
					boolean emptyLane = laneSnapshot.item(slotIdx) == null;
					if (protectLanes && emptyLane && newLanes >= newLaneBudget) {
						// 已用完份额的空槽不会被采用，无需再调用配方和槽位校验。
						inputSlotCapacities[slotIdx] = 0L;
						lanesSuppressed = true;
						continue;
					}
					long slotQuota = entry.unlimited ? Long.MAX_VALUE : perSlotQuota;
					long slotCapacity = Math.min(slotQuota,
							laneCapacity(laneSnapshot, slotIdx, keyItem, entry, slotProbe));
					if (slotCapacity > 0L && protectLanes && emptyLane) newLanes++;
					inputSlotCapacities[slotIdx] = slotCapacity;
					if (slotCapacity > 0L) {
						typeAvailable = SaturatingMath.saturatingAdd(typeAvailable, slotCapacity);
						if (indexed) deliveryLanes |= 1L << slotIdx;
					}
				}
				if (typeAvailable <= 0L) {
					// 车道被压制导致本类型这轮完全拿不到槽：补齐轮必须再给它一次机会
					if (lanesSuppressed) fairPassTruncated = true;
					// 槽校验可能依赖配对输出状态，只表示本轮无容量，不能对整个 key 退避。
					continue;
				}
				long entryQuota = entry.unlimited
						? inputCapacity - totalPulled
						: normalQuota - normalPulled;
				boolean quotaClipped = false;
				if (fairPass && !entry.unlimited && entryQuota > fairShare) {
					entryQuota = fairShare;
					quotaClipped = true;
				}
				int toPull = SaturatingMath.saturatingToInt(Math.min(
						typeAvailable, Math.min(entry.remaining, Math.max(0L, entryQuota))));
				if (toPull <= 0) {
					if (lanesSuppressed || quotaClipped) fairPassTruncated = true;
					continue;
				}
				if (!holder.tryAcquireNetworkExtract(meStorage, currentTick)) {
					networkWorkDeferred = true;
					break;
				}
				if (entry.unlimited) unlimitedAttempted = true;
				try {
					BatchResult batchResult = pullBatchForType(level, holder, entry.key, toPull,
							entry.reserveFloor, meStorage, actionSource,
						inputSlots, inputSlotCapacities, laneSnapshot, slotProbe, typeSlotStart, pos, keyBackoff,
						buffers.fingerprintCache, buffers.extractBudget, deliveryLanes);
					int pulled = batchResult.insertedCount();
					entry.remaining -= pulled;
					totalPulled += pulled;
					if (!entry.unlimited) normalPulled += pulled;
					if (entry.unlimited && pulled > 0) unlimitedSucceeded = true;
					if (pulled > 0) fairness.recordServed(entry.key, pulled);
					// 只有「确实被公平上限截断且本类型还想要更多」才需要补齐轮；
					// 否则补齐轮会为每个类型白跑一遍逐槽 SIMULATE 探测。
					if ((lanesSuppressed || quotaClipped) && entry.remaining > 0) {
						fairPassTruncated = true;
					}
					slowExtractDetected |= batchResult.slowExtract();
					degradedExtractDetected |= batchResult.degraded();
					healthyExtractDetected |= batchResult.healthy();
					maxSlowExtractCost = Math.max(maxSlowExtractCost, batchResult.extractCostNanos());
				} catch (LinkageError | RuntimeException e) {
					handlePullException(e, entry.key);
					if (holder.getPendingItemBuffer().isBlocked()) {
						var unresolved = holder.getPendingItemBuffer().transfer();
						unresolved.fingerprint();
						LogThrottle.error("ae2_input_transfer_unknown", "AE2 输入待核验 pos={} request={} target={} known={} offered={}: {}",
								pos, unresolved.request(), unresolved.destination(), unresolved.knownAmount(), unresolved.offeredAmount(), e.toString());
						host.productivebeesgenesis$markAe2StateChanged();
					}
					degradedExtractDetected = true;
				}
			}
			// 调用预算耗尽时，补齐轮也不再尝试。
			if (extractBudgetExhausted || networkWorkDeferred) break;
		}
		for (PullEntry entry : pullList) {
			entry.clearComponentMatchCache();
		}
		// Update the whole-tile storage backoff once per pull pass. A slow key wins over
		// a later healthy key in the same pass; otherwise a healthy pass clears the window
		// immediately, while leftover fallback remains a degraded result.
		if (inputBackoff != null) {
			if (slowExtractDetected && !degradedExtractDetected) {
				inputBackoff.recordSuccessWithDelay(System.nanoTime(),
						Ae2StorageHealth.successfulExtractDelay(maxSlowExtractCost));
			} else if (slowExtractDetected) {
				inputBackoff.recordSlowOperation(System.nanoTime(), maxSlowExtractCost);
			} else if (degradedExtractDetected) {
				inputBackoff.recordFailure(System.nanoTime());
			} else if (healthyExtractDetected) {
				inputBackoff.recordSuccess();
			}
		}

		return new Result(totalPulled, unlimitedAttempted, unlimitedSucceeded, networkWorkDeferred);
	}

	/** 暂缓的类型仍保留空槽份额，补齐轮不能把短暂退避变成永久挤占。 */
	static boolean hasUnassignedDemand(List<PullEntry> entries, Ae2InputLaneSnapshot lanes, int laneCount) {
		if (!lanes.hasEmptyLane()) return false;
		for (PullEntry entry : entries) {
			if (entry.remaining > 0 && lanes.occupiedLaneCount(entry.key) == 0) return true;
		}
		return false;
	}

	/** 规划复用车道快照；自定义槽继续完整模拟，交付后必须刷新已变车道。 */
	private static long laneCapacity(Ae2InputLaneSnapshot lanes, int slotIdx, Item keyItem,
			PullEntry entry, ItemStack probe) {
		if (entry == null || entry.key == null) return 0;
		IInventorySlot slot = lanes.slot(slotIdx);
		if (slot == null) return 0;
		if (!lanes.acceptsRequiredInput(slotIdx, probe)) return 0;
		// 廉价身份剪枝（与 getSlotRemainingCapacity 首判等价，但这里是纯数组读取）
		Item laneItem = lanes.item(slotIdx);
		if (laneItem != null && laneItem != keyItem) return 0;
		if (!(slot instanceof BasicInventorySlot basicSlot)) {
			return getSlotRemainingCapacity(slot, slotIdx, entry, probe);
		}
		if (laneItem == null) {
			// 空槽也必须按真实候选求上限：珍珠、工具和 MAX_STACK_SIZE 组件可能低于空栈上限。
			if (!entry.acceptsProbe(slotIdx, basicSlot, probe)) {
				return 0;
			}
			return Ae2InputLaneSnapshot.safeLimit(slot, probe);
		}
		// 已满车道不需要再比较组件；容量不能跨执行轮缓存。
		long limit = lanes.limit(slotIdx);
		int count = lanes.count(slotIdx);
		if (limit <= count) return 0;
		if (!entry.matchesComponents(slotIdx, lanes.stack(slotIdx), probe,
				lanes.patchSize(slotIdx), lanes.beeType(slotIdx))) return 0;
		if (!entry.acceptsProbe(slotIdx, basicSlot, probe)) {
			return 0;
		}
		return limit - count;
	}


	/** 非标准槽的精确模拟回退，保留完整组件与自动化权限。 */
	private static long getSlotRemainingCapacity(IInventorySlot slot, int slotIndex, PullEntry entry,
			ItemStack probe) {
		if (slot == null || entry == null || entry.key == null) return 0;
		AEItemKey key = entry.key;
		ItemStack stack = slot.getStack();
		try {
			// 物品不同必然无法插入，先做廉价身份判断，避免进入昂贵的组件比较。
			if (!stack.isEmpty() && stack.getItem() != key.getItem()) return 0;
			if (slot instanceof BasicInventorySlot basicSlot) {
				// BasicInventorySlot.insertItem 的 INTERNAL 语义由三步组成：组件匹配、
				// 上限判断、validator/canInsert 判断。这里直接复现，避免为每个候选类型
				// 创建模拟返回栈，并复用同一次 getLimit 结果。
				if (stack.isEmpty()) {
					if (!entry.acceptsProbe(slotIndex, basicSlot, probe)) {
						return 0;
					}
					return Math.max(0, slot.getLimit(probe));
				}
				// 非 Mekanism 标准槽的兜底路径：这里的栈不是快照的一部分，就地推导签名。
				// 该分支只服务自定义 IInventorySlot 实现，不在「每格一种蜜蜂」的热路径上。
				int lanePatchSize = stack.getComponentsPatch().size();
				if (!entry.matchesComponents(slotIndex, stack, probe, lanePatchSize,
						Ae2InputLaneSnapshot.singleBeeType(stack, stack.getItem(), lanePatchSize))) return 0;
				int limit = slot.getLimit(probe);
				// 先做「槽已满」的廉价判断（原顺序，避免多付一次 validator 调用）；
				// 槽校验也可能随配对输出变化，不跨拉取轮缓存其拒收结论。
				if (limit <= stack.getCount()) return 0;
				if (!entry.acceptsProbe(slotIndex, basicSlot, probe)) {
					return 0;
				}
				return (long) limit - stack.getCount();
			}
			// 保留非 BasicInventorySlot 实现的完整回退路径，兼容自定义 IInventorySlot。
			if (!slot.insertItem(probe, Action.SIMULATE, AutomationType.INTERNAL).isEmpty()) {
				// 自定义空槽也可能暂时拒收，本轮保留在 ME 中。
				return 0;
			}
			if (stack.isEmpty()) return Math.max(0, slot.getLimit(probe));
			int limit = slot.getLimit(stack);
			return Math.max(0, (long) limit - stack.getCount());
		} catch (RuntimeException e) {
			// 容量查询异常视为不可插入，节流日志便于排查（fail-safe 返回 0 跳过该槽）
			LogThrottle.warn("ae2_input_slot_capacity",
					"AE2 输入槽剩余容量查询异常, 视为不可插入: {}", e.toString());
			return 0;
		}
	}

	/** 单键抽取后只交付本轮正配额车道，保持圆环顺序；外部结果未知时保留交接记录。 */
	static BatchResult pullBatchForType(Level level, Ae2OutputStateHolder holder, AEItemKey key, int amount,
			long reserveFloor, MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, long[] inputSlotCapacities, Ae2InputLaneSnapshot laneSnapshot,
			ItemStack slotProbe, int slotStart, BlockPos pos,
			Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, Ae2FingerprintCache fingerprintCache,
			Ae2ExtractBudget extractBudget, long deliveryLanes) {
		long gameTick = level.getGameTime();
		// 抽取前预留 pending 身份位；数量仍由真实槽容量决定。指纹仅在类型位已满时编码。
		Ae2PendingItemBuffer pending = holder.getPendingItemBuffer();
		if (!pending.hasFreeEntrySlot()
				&& !pending.canRegister(fingerprintCache.get(key, level.registryAccess()))) {
			LogThrottle.warn("ae2_pending_item_capacity",
					"AE2 输入剩余物缓冲类型已满（{} 种），本轮跳过抽取（物品留在 ME 网络无损）key={}",
					Ae2PendingItemBuffer.MAX_ENTRIES, key);
			return BatchResult.skipped();
		}
		if (amount <= 0) return BatchResult.skipped();

		long reserveQueryCost = 0L;
		boolean slowReserveQuery = false;
		if (reserveFloor >= 0L) {
			// 保留线只接受执行前的实时模拟量；同刻其它机器或第三方可以改变库存。
			long queryCap = SaturatingMath.saturatingAdd(reserveFloor, amount);
			long queryStart = System.nanoTime();
			long liveExtractable;
			try {
				liveExtractable = Ae2NetworkInventoryView.liveExtractableAmount(
						meStorage, key, queryCap, actionSource);
			} catch (LinkageError | RuntimeException e) {
				reserveQueryCost = System.nanoTime() - queryStart;
				extractBudget.recordProbe(gameTick, reserveQueryCost);
				Ae2GlobalInsertBudget.recordCost(gameTick, reserveQueryCost,
						Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
				holder.recordNetworkExtractCost(meStorage, gameTick, reserveQueryCost,
						Ae2ExtractBudget.HEALTHY_EXTRACT_NANOS);
				if (keyBackoff != null) keyBackoff.recordFailure(key, System.nanoTime());
				LogThrottle.warn("ae2_reserve_query",
						"AE2 库存保留实时查询失败，本轮跳过抽取 key={}: {}", key, e.toString());
				return new BatchResult(0, reserveQueryCost,
						Ae2StorageHealth.isPathological(reserveQueryCost), true, false);
			}
			reserveQueryCost = System.nanoTime() - queryStart;
			extractBudget.recordProbe(gameTick, reserveQueryCost);
			Ae2GlobalInsertBudget.recordCost(gameTick, reserveQueryCost,
					Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
			holder.recordNetworkExtractCost(meStorage, gameTick, reserveQueryCost,
					Ae2ExtractBudget.HEALTHY_EXTRACT_NANOS);
			slowReserveQuery = Ae2StorageHealth.isPathological(reserveQueryCost);
			amount = Ae2FilterPullPolicy.reserveSafeRequest(amount, liveExtractable, reserveFloor);
			boolean reserveReached = amount <= 0;
			if (reserveReached) {
				// 256x 加速仍只执行一次完整 tick，但保留线稳态下会每个真实 tick 重试。
				// 按 key 墙钟退避可把空探针降到最高每秒一次；补货并成功抽取后立即清除。
				if ((reserveReached || slowReserveQuery) && keyBackoff != null) {
					keyBackoff.recordFailure(key, System.nanoTime());
				}
				return new BatchResult(0, reserveQueryCost, slowReserveQuery,
						slowReserveQuery, !slowReserveQuery);
			}
		}
		Ae2InputTransfer transfer = pending.transfer();
		transfer.beginExtract(() -> fingerprintCache.get(key, level.registryAccess()), amount);
		long extractStart = System.nanoTime();
		long extracted;
		try {
			extracted = BulkItemPullScope.extract(slotProbe,
					() -> meStorage.extract(key, transfer.offeredAmount(), Actionable.MODULATE, actionSource));
			transfer.extracted(extracted);
		} catch (LinkageError | RuntimeException error) {
			long extractCost = System.nanoTime() - extractStart;
			extractBudget.record(gameTick, extractCost);
			Ae2GlobalInsertBudget.recordCost(gameTick, extractCost,
					Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
			holder.recordNetworkExtractCost(meStorage, gameTick, extractCost,
					Ae2ExtractBudget.HEALTHY_EXTRACT_NANOS);
			throw error;
		}
		long extractCost = System.nanoTime() - extractStart;
		extractBudget.record(gameTick, extractCost);
		Ae2GlobalInsertBudget.recordCost(gameTick, extractCost,
				Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
		holder.recordNetworkExtractCost(meStorage, gameTick, extractCost,
			Ae2ExtractBudget.HEALTHY_EXTRACT_NANOS);
		long maxNetworkCost = Math.max(reserveQueryCost, extractCost);
		boolean slowNetworkOperation = slowReserveQuery || Ae2StorageHealth.isPathological(extractCost);
		if (extracted <= 0) {
			transfer.finish(pending, gameTick, false);
			if (keyBackoff != null) {
				keyBackoff.recordFailure(key, System.nanoTime());
			}
			return new BatchResult(0, maxNetworkCost, slowNetworkOperation,
					slowNetworkOperation, !slowNetworkOperation);
		}
		Ae2NetworkInventoryView.recordExtract(holder, gameTick, meStorage, key, extracted);
		int insertedCount = 0;
		int slotCount = inputSlots.size();
		boolean indexed = slotCount <= Long.SIZE;
		int slotOffset = 0;
		while (transfer.knownAmount() > 0 && (indexed ? deliveryLanes != 0L : slotOffset < slotCount)) {
			int slotIdx;
			if (indexed) {
				long afterStart = deliveryLanes & (-1L << slotStart);
				slotIdx = Long.numberOfTrailingZeros(afterStart == 0L ? deliveryLanes : afterStart);
				deliveryLanes &= ~(1L << slotIdx);
			} else {
				slotIdx = (slotStart + slotOffset++) % slotCount;
			}
			IInventorySlot slot = inputSlots.get(slotIdx);
			int quota = SaturatingMath.saturatingToInt(inputSlotCapacities[slotIdx]);
			if (slot == null || quota <= 0) continue;
			int amountForSlot = (int) Math.min(transfer.knownAmount(), quota);
			int inserted = Ae2LeftoverReturner.insertIntoSlot(transfer, key, slot, amountForSlot, slotIdx);
			insertedCount += inserted;
			if (inserted > 0 && laneSnapshot != null) {
				laneSnapshot.remember(key, slotIdx);
				laneSnapshot.refresh(slotIdx);
			}
		}
		if (insertedCount > 0 && keyBackoff != null) {
			keyBackoff.recordSuccess(key, System.nanoTime(),
					Ae2StorageHealth.successfulExtractDelay(maxNetworkCost));
		}
		boolean returnAttempted = false;
		if (transfer.knownAmount() > 0
				&& !holder.getPushState().getReturnBackoff().shouldSkip(System.nanoTime())) {
			returnAttempted = Ae2LeftoverReturner.returnLeftoverToMe(holder, meStorage, key, actionSource, gameTick);
		}
		boolean stranded = transfer.knownAmount() > 0;
		boolean returnFailed = stranded && returnAttempted;
		transfer.finish(pending, gameTick, false, 0, returnFailed);
		if (stranded && level.getBlockEntity(pos) != null) level.getBlockEntity(pos).setChanged();
		return new BatchResult(insertedCount, maxNetworkCost, slowNetworkOperation, returnFailed,
				!slowNetworkOperation && !returnFailed);
	}

	private static void handlePullException(Throwable e, AEItemKey key) {
		long count = PULL_EXCEPTION_COUNTER.incrementAndGet();
		if (e instanceof NullPointerException) {
			// NPE 异常：ERROR 级别，M9 改用 LogThrottle 节流避免 256× 加速刷屏
			LogThrottle.error("ae2_pull_npe",
					"AE2 拉取 NPE 异常 (累计 {} 次,5秒内仅首条输出) key={}: {}", count, key, e.toString());
		} else if (e instanceof LinkageError) {
			// LinkageError：ERROR 级别，M9 改用 LogThrottle 节流避免刷屏
			LogThrottle.error("ae2_pull_linkage",
					"AE2 拉取 LinkageError 异常 (累计 {} 次,5秒内仅首条输出) key={}: {}", count, key, e.toString());
		} else {
			// 其他异常：LogThrottle 5 秒节流 WARN
			LogThrottle.warn("ae2_input_pull",
					"AE2 拉取异常 (第 {} 次) key={}", count, key);
		}
		// InterruptedException 恢复中断状态
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 拉取条目 — 缓存扫描结果，包级可见供 {@link Ae2PushBuffers#pullList} 复用。
	 */
	static final class PullEntry {
		AEItemKey key;
		int remaining;
		boolean unlimited;
		/** Pre-computed "matches a configured entry" flag used by the marked-first sort. */
		boolean marked;
		/** Whether this entry is an ordinary Mekanism SMELTING input. */
		boolean smelting;
		boolean combBlock;
		/** 等待资源出口探测时只保留公平份额，不提取原料。 */
		boolean resourceDeferred;
		/** Effective AE2 stock floor, or -1 when no reserve policy applies. */
		long reserveFloor;
		long servedInWindow;

		/** 当前拉取轮次按槽位记忆的组件匹配结果，容量数值不在此缓存。 */
		private ItemStack[] componentMatchStacks = new ItemStack[0];
		private boolean[] componentMatchResults = new boolean[0];
		private int[] componentMatchGenerations = new int[0];
		private int componentMatchGeneration;
		/** Validator predicates may capture a slot or process index. Cache by slot identity. */
		private BasicInventorySlot[] validatorSlots = new BasicInventorySlot[0];
		private byte[] validatorStates = new byte[0];
		/** 本条目 key 的组件补丁数（每轮准备一次，供内层循环直接比较）。 */
		private int keyPatchSize;
		/** 本条目 key 的「唯一 bee_type」签名；不满足快径条件时为 null。 */
		private ResourceLocation keyBeeType;

		PullEntry(AEItemKey key, int amount) {
			reset(key, amount, false);
		}

		void reset(AEItemKey key, int amount, boolean unlimited) {
			this.key = key;
			this.remaining = amount;
			this.unlimited = unlimited;
			this.marked = false;
			this.smelting = false;
			this.combBlock = false;
			this.resourceDeferred = false;
			this.reserveFloor = -1L;
			this.servedInWindow = 0L;
			Arrays.fill(validatorSlots, null);
		}

		/** 开始一轮容量规划；数组按候选条目复用，避免每次比较分配临时映射。 */
		void beginComponentMatchCache(int slotCount) {
			if (slotCount > componentMatchStacks.length) {
				componentMatchStacks = Arrays.copyOf(componentMatchStacks, slotCount);
				componentMatchResults = Arrays.copyOf(componentMatchResults, slotCount);
				componentMatchGenerations = Arrays.copyOf(componentMatchGenerations, slotCount);
			}
			if (slotCount > validatorSlots.length) {
				validatorSlots = Arrays.copyOf(validatorSlots, slotCount);
				validatorStates = Arrays.copyOf(validatorStates, slotCount);
			}
			if (++componentMatchGeneration == 0) {
				Arrays.fill(componentMatchGenerations, 0);
				componentMatchGeneration = 1;
			}
			Arrays.fill(validatorSlots, null);
			prepareKeyComponents();
		}

		/** 一轮只解析一次完整组件补丁数；单 bee_type 快径与车道快照共用规则。 */
		private void prepareKeyComponents() {
			AEItemKey entryKey = this.key;
			if (entryKey == null) {
				keyPatchSize = 0;
				keyBeeType = null;
				return;
			}
			ItemStack keyStack = entryKey.getReadOnlyStack();
			int patchSize = keyStack.getComponentsPatch().size();
			keyPatchSize = patchSize;
			keyBeeType = Ae2InputLaneSnapshot.singleBeeType(keyStack, entryKey.getItem(), patchSize);
		}

		/**
		 * 同类槽位也可能绑定不同的 validator；只在同一规划轮内复用同一槽的结果。
		 */
		boolean acceptsProbe(int slotIndex, BasicInventorySlot slot, ItemStack probe) {
			if (slotIndex >= 0 && slotIndex < validatorSlots.length && validatorSlots[slotIndex] == slot) {
				return validatorStates[slotIndex] == 1;
			}
			boolean accepted = slot.isItemValidForInsertion(probe, AutomationType.INTERNAL);
			if (slotIndex >= 0 && slotIndex < validatorSlots.length) {
				validatorSlots[slotIndex] = slot;
				validatorStates[slotIndex] = (byte) (accepted ? 1 : 0);
			}
			return accepted;
		}

		/** 按「槽位当前栈对象 + 当前 PullEntry key」缓存昂贵的组件匹配。 */
		boolean matchesComponents(int slotIndex, ItemStack stack, ItemStack probe,
				int lanePatchSize, ResourceLocation laneBeeType) {
			// 记忆表优先：本轮（entry × 槽位）的结论只由「槽内栈对象 + 本条目 key」决定，
			// 补齐轮与前一轮重复访问同一槽位时可直接命中。原先记忆只在完整比较的末尾写入、
			// 且被前面的廉价快径挡住，导致蜜脾（走 bee_type 快径）每轮重算一遍。
			boolean cacheable = slotIndex >= 0 && slotIndex < componentMatchStacks.length;
			if (cacheable
					&& componentMatchGenerations[slotIndex] == componentMatchGeneration
					&& componentMatchStacks[slotIndex] == stack) {
				return componentMatchResults[slotIndex];
			}
			// 快径 1：双方都没有组件补丁 → 同 Item 的默认组件必然一致。普通矿物/原料走这里，
			// 避免进入 GeckoLib 包装后的完整 PatchedDataComponentMap 比较。
			if (lanePatchSize == 0 && keyPatchSize == 0) {
				return cacheComponentMatch(slotIndex, stack, cacheable, true);
			}
			// 单 bee_type 快径要求双方都是同一可配置蜜脾且仅携带该补丁。
			if (lanePatchSize == 1 && keyPatchSize == 1 && laneBeeType != null && keyBeeType != null) {
				return cacheComponentMatch(slotIndex, stack, cacheable,
						laneBeeType == keyBeeType || laneBeeType.equals(keyBeeType));
			}
			// 回退：多补丁（玩家重命名等）/ 外部模组蜜脾 / 其它形态，仍走原版完整比较保证堆叠语义。
			return cacheComponentMatch(slotIndex, stack, cacheable,
					ItemStack.isSameItemSameComponents(stack, probe));
		}

		/** 写入（可选）本轮组件匹配记忆表并返回结论。 */
		private boolean cacheComponentMatch(int slotIndex, ItemStack stack, boolean cacheable, boolean matches) {
			if (cacheable) {
				componentMatchGenerations[slotIndex] = componentMatchGeneration;
				componentMatchStacks[slotIndex] = stack;
				componentMatchResults[slotIndex] = matches;
			}
			return matches;
		}

		/** 清除槽位对象引用，避免复用池在两次拉取之间保留旧 ItemStack。 */
		void clearComponentMatchCache() {
			Arrays.fill(componentMatchStacks, null);
			Arrays.fill(validatorSlots, null);
		}
	}
}
