package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
	 * AE2 输入拉取器（与 {@link Ae2OutputPusher} 对称）— 将 AE2 网络中的蜜脾拉取到离心机输入槽。
	 * 调用方需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫。
	 * @since 2.0.0
	 */
public final class Ae2InputPuller {
	/**
	 * Refresh the candidate key list at most every half second. Inventory amounts are
	 * still read from the current AE2 snapshot for every pull, so this only delays
	 * discovery of newly-added item types and never uses stale amounts.
	 */
	private static final long CANDIDATE_KEY_REFRESH_INTERVAL_TICKS = 10L;

	private static final Comparator<PullEntry> PULL_ENTRY_ORDER = (a, b) -> {
		if (a.marked != b.marked) return Boolean.compare(b.marked, a.marked);
		if (a.combBlock != b.combBlock) return Boolean.compare(b.combBlock, a.combBlock);
		return Long.compare(a.servedInWindow, b.servedInWindow);
	};

	/** 异常日志计数器 — 用于在日志中显示累计出现次数（LogThrottle 已负责节流） */
	private static final AtomicLong PULL_EXCEPTION_COUNTER = new AtomicLong(0);

	/** Result of one per-key extract and local distribution attempt. */
	private record PullBatchResult(int insertedCount, long extractCostNanos,
			boolean slowExtract, boolean degraded, boolean healthy) {
	}

	/**
	 * 懒加载 Holder — AE2 未安装时本类初始化不触发 {@link BaseActionSource} 类解析
	 * <br/>
	 * 与 Ae2OutputPusher/Ae2FluidPusher/Ae2EnergyBridge 的 ActionSourceHolder 模式一致
	 * （Issue #8 防御深度：静态字段在 &lt;clinit&gt; 执行先于方法体守卫）。
	 */
	private static final class ActionSourceHolder {
		/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
		static final IActionSource INSTANCE = new BaseActionSource() {};
	}

	private Ae2InputPuller() {}

	/**
	 * 从 AE2 网络拉取蜜脾到宿主输入槽
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。
	 * 拉取间隔由 {@code mekCentrifugeAeInputIntervalTicks} 控制，避免每 tick 调用 AE2 API。
	 *
	 * @param host 输入宿主（离心机方块实体）
	 */
	public static void pullInputs(IAe2InputHost host) {
		pullInputs(host, 0);
	}

	/**
	 * Pulls inputs using the number of virtual ticks that the machine actually executed in this pass.
	 * A positive value is already constrained by the adaptive batch budget, so it must not be scaled by
	 * the MSPT factor a second time. A non-positive value keeps the legacy tracker-based fallback.
	 */
	public static void pullInputs(IAe2InputHost host, int executedBatchMultiplier) {
		// Spark 优化：缓存 holder 到局部变量，消除后续 10+ 次冗余 getAe2StateHolder() 接口分发
		// （每次2层接口分发：getLifecycleHandler→getStateHolder，热力图显示为热点）
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level != null && holder.isConfigCacheStale(level.getGameTime())) {
			holder.refreshConfigCache(level.getGameTime());
		}

		// 1. 拉取开关检查（全局 AND per-tile）— 直接使用 holder 替代 host 接口分发
		if (!holder.isInputPullEnabled()) return;

		// 1.5 TPS 自适应检查 — TPS 严重下降时跳过整个 pullInputs，由 MEK Ejector 兜底
		//    TPS < 10(对应 avgMspt > 100ms)时跳过；null 守卫防初始化阶段空指针
		if (level != null) {
			double currentTps = ServerTickTimeMonitor.getInstance().getTps(level.getGameTime());
			if (currentTps < 10.0) {
				return;
			}
		}

		// 2. 回送退避检查（Task 10：3 次重试失败后进入退避窗口，跳过整个拉取流程减少循环频率）
		//    入口级别跳过实现深度退避（Task 4）：避免进入 getAvailableStacks 遍历前的无效开销
		//    holder 已非 null，getPushState() 永不返回 null（final 字段构造时初始化）
		Ae2PushBackoff returnBackoff = holder.getPushState().getReturnBackoff();
		if (returnBackoff.shouldSkip(System.nanoTime())) {
			return;
		}

		// 模块2.5：强检测 grid node 状态，仅当 ONLINE(3) 时继续拉取
		// 状态 0/1/2: OFFLINE/NETWORK_BOOTING/MISSING_CHANNEL — 不进入拉取路径，避免无效的 getAvailableStacks 遍历
		// 使用 pushState 缓存（20 tick 刷新一次）避免每 tick 高频调用 getGridNodeState
		int nodeState = holder.getPushState().getCachedNodeState(host);
		if (nodeState != Ae2GridNodeManager.STATE_ONLINE) {
			return;
		}

		// 3. Level null 守卫（已在 1.5 节获取，复用避免重复调用）
		if (level == null) return;
		long currentTick = level.getGameTime();

		// 4. 加速倍率检测 — multiplier 已在调用方 onUpdateServer 入口处通过 tracker.onTick(level) 更新
		//    直接使用 holder 替代 host 接口分发
		TickAccelTracker tracker = holder.getTickAccelTracker();
		int M = Ae2PullFairnessPolicy.resolveAccelerationMultiplier(
				executedBatchMultiplier,
				tracker == null ? 1 : tracker.getMultiplier(),
				tracker == null ? 1 : tracker.getPreviousTickMultiplier());

		// 5. AE2LT-style adaptive cooldown (success: 1 tick unlimited / 5 normal, failures back off).
		//    Driven by the pull call counter so acceleration mods that invoke multiple
		//    ticks per game tick still converge; unlimited entries ignore the configured interval.
		long pullCounter = holder.incrementPullCallCounter();
		int cooldownTicks = Ae2PullFairnessPolicy.effectiveInterval(
				holder.getInputPullCooldownTicks(), M);
		if (pullCounter - holder.getLastPullCounter() < cooldownTicks) return;

		// 6. 获取输入槽列表。放在网格服务获取前，满槽时不触碰 AE2 服务缓存。
		List<IInventorySlot> inputSlots = host.productivebeesgenesis$getInputSlotsForPull();
		if (inputSlots == null || inputSlots.isEmpty()) return;
		int processCount = inputSlots.size();

		// 7. Unlimited entries bypass the rate budget entirely (AE2LT overloaded
		//     interface semantics): pull as much as the input slots can hold,
		//     ignoring both the configured interval and per-tick quantity.
		Ae2InputFilter filter = holder.getOrCreateInputFilter();
		// A network-stock flag is per entry. Only the explicit all-entry fallback
		// and an all-network-stock whitelist retain the high-throughput cadence.
		// The legacy Shift action is the only machine-wide unlimited mode. A direct
		// entry's unlimited flag is carried by its PullEntry and does not widen other
		// entries' quotas.
		boolean unlimitedMode = filter != null
				&& (filter.isUnlimitedAllFallback() || filter.hasUnlimitedEntries());
		long inputCapacity = calculateInputCapacity(inputSlots);
		if (inputCapacity <= 0) {
			// Input slots are full; treat as a failed attempt so the cooldown backs off.
			holder.onInputPullFail(unlimitedMode);
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
			return;
		}
		long baseRate = holder.getCachedInputRatePerTick();
		double tpsFactor = executedBatchMultiplier > 0
				? 1.0
				: ServerTickTimeMonitor.getInstance().getTpsFactor(currentTick);
		long perSlotQuota = Ae2PullFairnessPolicy.perSlotQuota(baseRate, M, processCount);
		long baseProduct = SaturatingMath.saturatingMultiply(perSlotQuota, processCount);
		long effectiveRate = Math.max(1L,
				SaturatingMath.saturatingCeilToLong(baseProduct * tpsFactor));
		long normalQuota = Math.min(effectiveRate, inputCapacity);
		if (normalQuota <= 0L && !unlimitedMode) return;

		// 8. 只有冷却到期且本地确有容量时才解析网格存储服务。getCachedStorage 内部已验证 grid，
		//    无需先单独调用 getCachedGrid；这会缩短大批机器处于冷却/满槽状态时的热路径。
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;
		BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();

		// 9. 获取复用缓冲与调度状态；同样延后到真正需要扫描网络库存时。
		Ae2OutputPusher.ReusableBuffers buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff = getOrCreateKeyBackoff(holder);
		Ae2InputFairnessScheduler fairness = getOrCreateFairnessScheduler(holder);
		fairness.roll(currentTick);

		// 10. 遍历 MEStorage 可用栈，收集待拉取类型（不消耗 quota，由执行阶段按 round-robin 分配）
		//     V13 修复：收集所有可用类型，单类型走原版顺序填充，多类型走 round-robin 跨进程分发
		List<PullEntry> pullList = buffers.borrowPullList();
		Set<AEItemKey> pullKeys = buffers.borrowPullKeys();
		pullKeys.clear();
		buffers.resetPullEntryPool();
		pullList.clear(); // 清空上一 tick 残留数据
		int maxTypesToCollect = Math.max(1, processCount * 2); // 上限避免海量类型拖慢分发
		AEItemKey candidateCursor = holder.getInputCandidateCursor() instanceof AEItemKey key ? key : null;
		// AE2 已在 StorageService 中维护网格库存缓存。直接调用 MEStorage.getAvailableStacks()
		// 会再次遍历每个存储单元；在大型 Omni Cell 网络中这正是 Spark 的主要热点。
		// 单次拉取固定使用同一快照，游标回绕也不会触发第二次网络聚合。
		var availableStacks = storageService.getCachedInventory();
		List<Ae2InputFilter.DirectEntry> directEntries = filter != null && filter.hasDirectEntries()
				? filter.getDirectEntries() : List.of();
		if (!directEntries.isEmpty()) {
			var resolvedKeys = Ae2ItemFingerprint.resolve(
					directEntries, availableStacks, level.registryAccess());
			for (Ae2InputFilter.DirectEntry direct : directEntries) {
				if (direct.key() != null) continue;
				AEItemKey resolved = resolvedKeys.get(direct.fingerprint());
				if (resolved != null) filter.resolveDirectKey(direct.index(), resolved);
			}
			directEntries = filter.getDirectEntries();
		}
		boolean directOnly = filter != null
				&& filter.getFilterMode() == Ae2InputFilter.FilterMode.WHITELIST
				&& filter.hasOnlyNetworkStockEntries()
				&& !filter.isGlobalNetworkStock()
				&& !holder.isAeInputNbtIgnore();
		if (directOnly) {
			// Network-stock whitelist entries use exact keys and avoid a full inventory scan.
			for (Ae2InputFilter.DirectEntry direct : directEntries) {
				if (pullList.size() >= maxTypesToCollect) break;
				AEItemKey key = direct.key();
				if (key == null || !CombFuzzyMatcher.isCombItem(key) || !pullKeys.add(key)) continue;
				long available = Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
						availableStacks, meStorage, key, Long.MAX_VALUE, ActionSourceHolder.INSTANCE);
				long configuredLimit = filter.getDirectPullLimit(key, available, holder.isAeInputNbtIgnore());
				if (configuredLimit >= 0L) {
					available = Math.min(available, configuredLimit);
				}
				if (available > 0) {
					pullList.add(buffers.borrowPullEntry(key, SaturatingMath.saturatingToInt(available),
							direct.unlimited()));
				} else {
					pullKeys.remove(key);
				}
			}
		} else {
			// Probe configured network-stock keys directly. This also covers disabled
			// filters and AE2 storage providers whose simulated stock is not present in
			// the cached KeyCounter. Blacklist entries remain excluded by the normal
			// filter path below.
			if (filter != null && filter.getFilterMode() != Ae2InputFilter.FilterMode.BLACKLIST) {
				for (Ae2InputFilter.DirectEntry direct : directEntries) {
					if (pullList.size() >= maxTypesToCollect) break;
					AEItemKey key = direct.key();
					if ((!direct.networkStock() && !filter.isGlobalNetworkStock())
							|| key == null || !CombFuzzyMatcher.isCombItem(key)
							|| !pullKeys.add(key)) continue;
					long available = Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
						availableStacks, meStorage, key, Long.MAX_VALUE, ActionSourceHolder.INSTANCE);
					long configuredLimit = filter.getPullLimitIfAllowed(key, available, holder.isAeInputNbtIgnore());
					if (configuredLimit == Ae2InputFilter.PULL_DISALLOWED) {
						pullKeys.remove(key);
						continue;
					}
					if (configuredLimit >= 0L) {
						available = Math.min(available, configuredLimit);
					}
					if (available > 0) {
						pullList.add(buffers.borrowPullEntry(key, SaturatingMath.saturatingToInt(available),
								direct.unlimited()));
					} else {
						pullKeys.remove(key);
					}
				}
			}
			// Scan the cached inventory directly. A bounded prefix scratch preserves cursor
			// wraparound without copying every key in a large AE network into each tile.
			List<AEItemKey> selectedKeys = buffers.borrowScanSelectedKeys();
			selectedKeys.clear();
			Ae2OutputPusher.PullCandidateAmounts candidateAmounts = buffers.borrowScanCandidateAmounts();
			candidateAmounts.clear();
			List<AEItemKey> prefixKeys = buffers.borrowScanPrefixKeys();
			List<AEItemKey> candidateKeys = buffers.borrowScanCandidateKeys();
			if (buffers.needsScanCandidateRefresh(availableStacks, currentTick,
					CANDIDATE_KEY_REFRESH_INTERVAL_TICKS)) {
				candidateKeys.clear();
				for (var entry : availableStacks) {
					if (entry.getKey() instanceof AEItemKey itemKey
							&& CombFuzzyMatcher.isCombItem(itemKey)) {
						candidateKeys.add(itemKey);
					}
				}
				buffers.markScanCandidateRefresh(availableStacks, currentTick);
			}
			boolean ignoreNbt = holder.isAeInputNbtIgnore();
			// 总收集上限与旧实现一致：whitelist 预置条目也计入 maxTypesToCollect
			int scanCap = Math.max(0, maxTypesToCollect - pullList.size());
			// The candidate list is refreshed infrequently; amounts are still read from
			// the current KeyCounter snapshot, preserving live stock and filter limits.
			Ae2CursorScan.collectMapped(selectedKeys, prefixKeys, candidateKeys,
					candidateCursor, scanCap,
					entry -> entry,
					key -> {
						if (pullKeys.contains(key)) return false;
						int amount = getPullCandidateAmount(
								key, availableStacks.get(key), filter, ignoreNbt);
						if (amount <= 0) return false;
						candidateAmounts.put(key, amount);
						return true;
					});
			for (AEItemKey key : selectedKeys) {
				int amount = candidateAmounts.get(key);
				if (amount > 0 && pullKeys.add(key)) {
					pullList.add(buffers.borrowPullEntry(key, amount,
						filter != null && filter.isUnlimitedForKey(key, ignoreNbt)));
				}
			}
			candidateAmounts.clear();
		}

		if (pullList.isEmpty()) {
			// 无可拉取物品，仍刷新 lastPullTick 避免下一 tick 重复扫描
			// Spark 优化：直接使用 holder 替代 host 接口分发
			// No pullable items: treat as a failed attempt so the cooldown backs off.
			holder.onInputPullFail(unlimitedMode);
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
			return;
		}
		holder.setInputCandidateCursor(pullList.getLast().key);

		// 14. Marked-first ordering (AE2LT: pull what is marked first);
		//      among marked entries comb blocks stay ahead (higher yield).
		//      marked flags are pre-computed once per pull (matchesAnyEntry walks the
		//      filter slots; doing it inside the comparator would cost N*logN walks).
		boolean sortIgnoreNbt = holder.isAeInputNbtIgnore();
		boolean rankMarked = filter != null && filter.getFilterMode() != Ae2InputFilter.FilterMode.DISABLED;
		for (PullEntry entry : pullList) {
			entry.marked = rankMarked && filter.matchesAnyEntry(entry.key, sortIgnoreNbt);
			entry.combBlock = CombFuzzyMatcher.isCombBlock(entry.key);
			entry.servedInWindow = fairness.served(entry.key);
		}
		pullList.sort(PULL_ENTRY_ORDER);

		long totalPulled = 0;
		long normalPulled = 0;
		boolean unlimitedAttempted = false;
		boolean unlimitedSucceeded = false;
		boolean slowExtractDetected = false;
		boolean degradedExtractDetected = false;
		boolean healthyExtractDetected = false;
		long maxSlowExtractCost = 0L;
		int typeCount = pullList.size();
		int slotStart = holder.getPushState().getAndAdvanceInputSlotRotation(processCount);
		long[] inputSlotCapacities = buffers.borrowInputSlotCapacities(processCount);
		long nanoNow = System.nanoTime();
		for (int typeOffset = 0; typeOffset < typeCount && totalPulled < inputCapacity; typeOffset++) {
			PullEntry entry = pullList.get(typeOffset);
			if (entry.remaining <= 0 || keyBackoff.shouldSkip(entry.key, nanoNow)) continue;

			// 先汇总该类型在所有可用槽位中的容量，然后一次 ME extract，再本地分发。
			// 同一类型的模拟探针在所有输入槽中复用；SIMULATE 不会修改传入栈，
			// 这样可避免高倍加速下为每个「类型 × 槽位」重复创建 ItemStack。
			ItemStack slotProbe = entry.key.toStack(1);
			long typeAvailable = 0L;
			for (int slotOffset = 0; slotOffset < processCount; slotOffset++) {
				int slotIdx = (slotStart + slotOffset) % processCount;
				IInventorySlot slot = inputSlots.get(slotIdx);
				long slotQuota = entry.unlimited ? Long.MAX_VALUE : perSlotQuota;
				long slotCapacity = slot == null ? 0L
						: Math.min(slotQuota, getSlotRemainingCapacity(slot, entry.key, slotProbe));
				inputSlotCapacities[slotIdx] = slotCapacity;
				if (slotCapacity > 0L) {
					typeAvailable = SaturatingMath.saturatingAdd(typeAvailable, slotCapacity);
				}
			}
			if (typeAvailable <= 0L) continue;
			long entryQuota = entry.unlimited
					? inputCapacity - totalPulled
					: normalQuota - normalPulled;
			int toPull = SaturatingMath.saturatingToInt(Math.min(
					typeAvailable, Math.min(entry.remaining, Math.max(0L, entryQuota))));
			if (toPull <= 0) continue;
			if (entry.unlimited) unlimitedAttempted = true;
			try {
				PullBatchResult batchResult = pullBatchForType(level, holder, entry.key, toPull, meStorage,
						ActionSourceHolder.INSTANCE,
					inputSlots, inputSlotCapacities, slotStart, pos, keyBackoff);
				int pulled = batchResult.insertedCount();
				entry.remaining -= pulled;
				totalPulled += pulled;
				if (!entry.unlimited) normalPulled += pulled;
				if (entry.unlimited && pulled > 0) unlimitedSucceeded = true;
				if (pulled > 0) fairness.recordServed(entry.key, pulled);
				slowExtractDetected |= batchResult.slowExtract();
				degradedExtractDetected |= batchResult.degraded();
				healthyExtractDetected |= batchResult.healthy();
				maxSlowExtractCost = Math.max(maxSlowExtractCost, batchResult.extractCostNanos());
			} catch (LinkageError | RuntimeException e) {
				handlePullException(e, entry.key);
				degradedExtractDetected = true;
			}
		}
		// Update the whole-tile storage backoff once per pull pass. A slow key wins over
		// a later healthy key in the same pass; otherwise a healthy pass clears the window
		// immediately, while leftover fallback remains a degraded result.
		if (returnBackoff != null) {
			if (slowExtractDetected) {
				returnBackoff.recordSlowOperation(System.nanoTime(), maxSlowExtractCost);
			} else if (degradedExtractDetected) {
				returnBackoff.recordFailure(System.nanoTime());
			} else if (healthyExtractDetected) {
				returnBackoff.recordSuccess();
			}
		}

		// 16. 更新上次拉取游戏刻（无论是否拉取成功，只要触发过就更新，避免下一 tick 重复扫描）
		//     Spark 优化：直接使用 holder 替代 host 接口分发
		// 16. Update the adaptive cooldown: success shortens the next interval
		//     (1 tick unlimited / 5 normal), failure backs off (AE2LT parity).
		if (totalPulled > 0) {
			holder.onInputPullSuccess(unlimitedSucceeded, totalPulled, normalQuota);
		} else {
			holder.onInputPullFail(unlimitedAttempted);
		}
		holder.updateLastPullTick(currentTick);
		holder.updateLastPullCounter(pullCounter);

		// 拉取列表已使用完毕，clear 而非新建（复用 ReusableBuffers）
		pullList.clear();
	}

	private static int getPullCandidateAmount(Object rawKey, long available, Ae2InputFilter filter,
			boolean ignoreNbt) {
		if (available <= 0 || !(rawKey instanceof AEItemKey itemKey)
				|| !CombFuzzyMatcher.isCombItem(itemKey)) return 0;
		if (filter != null) {
			long configuredLimit = filter.getPullLimitIfAllowed(itemKey, available, ignoreNbt);
			if (configuredLimit == Ae2InputFilter.PULL_DISALLOWED) return 0;
			if (configuredLimit >= 0L) available = Math.min(available, configuredLimit);
		}
		return available <= 0L ? 0 : SaturatingMath.saturatingToInt(available);
	}


	/**
	 * 获取每次拉取的最大物品数量
	 * <br/>
	 * null 守卫：AE2 未加载或配置段未注册时回退默认值 64。
	 */


	/**
	 * 获取拉取触发间隔（游戏刻）
	 * <br/>
	 * null 守卫：AE2 未加载或配置段未注册时回退默认值 20。
	 */


	/**
	 * 计算输入槽总剩余容量
	 * <br/>
	 * 空槽使用 getLimit(EMPTY) 获取实际上限（适配分等级堆叠倍率），
	 * 非空槽调用 getLimit(stack)。返回 long 防止高等级工厂多槽累加溢出。
	 *
	 * @param slots 输入槽列表
	 * @return 所有输入槽剩余容量之和
	 */
	private static long calculateInputCapacity(List<IInventorySlot> slots) {
		long total = 0;
		for (IInventorySlot slot : slots) {
			if (slot == null) continue;
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				// 空槽：使用 getLimit(EMPTY) 获取实际上限（适配分等级堆叠倍率）
				try {
					total = SaturatingMath.saturatingAdd(total, slot.getLimit(ItemStack.EMPTY));
				} catch (RuntimeException e) {
					// getLimit 异常时跳过该槽位（节流日志便于排查自定义槽实现缺陷）
					LogThrottle.warn("ae2_input_capacity_empty",
							"AE2 输入槽容量计算异常 (空槽), 跳过该槽位: {}", e.toString());
				}
			} else {
				try {
					int limit = slot.getLimit(stack);
					long remaining = (long) limit - stack.getCount();
					if (remaining > 0) total = SaturatingMath.saturatingAdd(total, remaining);
				} catch (RuntimeException e) {
					// getLimit 异常时跳过该槽位（节流日志便于排查自定义槽实现缺陷）
					LogThrottle.warn("ae2_input_capacity_occupied",
							"AE2 输入槽容量计算异常 (非空槽), 跳过该槽位: {}", e.toString());
				}
			}
		}
		return total;
	}

	/**
	 * 计算单个输入槽对指定 key 的剩余容量
	 * <br/>
	 * 槽内已有不同类型物品时返回 0，使 round-robin 跳过该槽寻找空槽或同类型槽。
	 *
	 * @param slot 输入槽（null 时返回 0）
	 * @param key  待插入的 AE2 物品键（null 时返回 0）
	 * @return 剩余容量（槽内物品与 key 不匹配时返回 0）
	 */
	private static long getSlotRemainingCapacity(IInventorySlot slot, AEItemKey key, ItemStack probe) {
		if (slot == null || key == null) return 0;
		ItemStack stack = slot.getStack();
		try {
			if (!stack.isEmpty() && !key.matches(stack)) return 0;
			// validator 全语义预检（SIMULATE 探测 1 个，不写入）：
			// 工厂输入槽的 validator 含 inputProducesOutput 配方检查 — 被拒时 insertItem
			// 会整栈退回，extract 到的物品只能经 returnLeftoverToMe 回送 ME，在 EnderDrives
			// 类网络上每次回送都是一次主线程 WAL fsync（spark w4xREcN1HI 回送链路 1.26s/27s）。
			// 预检被拒 → 容量记 0，从源头不拉取。不触碰 getLimit 语义，高堆叠槽位不受影响。
			if (!slot.insertItem(probe, Action.SIMULATE, AutomationType.INTERNAL).isEmpty()) {
				return 0;
			}
			if (stack.isEmpty()) {
				return slot.getLimit(ItemStack.EMPTY);
			}
			int limit = slot.getLimit(stack);
			return Math.max(0, (long) limit - stack.getCount());
		} catch (RuntimeException e) {
			// 容量查询异常视为不可插入，节流日志便于排查（fail-safe 返回 0 跳过该槽）
			LogThrottle.warn("ae2_input_slot_capacity",
					"AE2 输入槽剩余容量查询异常, 视为不可插入: {}", e.toString());
			return 0;
		}
	}

	/**
	 * 异常处理：限流日志 + 按异常类型分级记录 + InterruptedException 恢复中断。
	 * <p>
	 * Task 7.3 日志治理：
	 * <ul>
	 *   <li>NPE 异常 → ERROR 级别无节流（数据完整性问题必须立即报告，代码缺陷）</li>
	 *   <li>LinkageError → ERROR 级别无节流（AE2 版本不兼容属于严重环境问题）</li>
	 *   <li>其他异常 → LogThrottle 5 秒节流 WARN（多 tile 全局节流）</li>
	 *   <li>所有异常不阻塞拉取流程</li>
	 * </ul>
	 */
	/**
	 * Per-type batch pull: one ME extract, then local distribution across slots.
	 * This mirrors the AE2LT batching approach and reduces high-tier factory AE2 API calls.
	 */
	private static PullBatchResult pullBatchForType(Level level, Ae2OutputStateHolder holder, AEItemKey key, int amount,
			MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, long[] inputSlotCapacities, int slotStart, BlockPos pos,
			Ae2KeyBackoffRegistry<AEItemKey> keyBackoff) {
		// 全服慢网络操作预算（Spark w4xREcN1HI：EnderDrives 的 extract 同样写 WAL，
		// appendWalRecordsLocked→force 在主线程 fsync 5-10ms/次）。预算耗尽时跳过本轮
		// extract — 物品留在 ME 网络无损，退避结束后由冷却节奏自然恢复，吞吐不受损。
		long gameTick = level.getGameTime();
		if (Ae2GlobalInsertBudget.isExhausted(gameTick)) {
			return new PullBatchResult(0, 0L, false, true, false);
		}
		long extractStart = System.nanoTime();
		long extracted = SaturatingMath.clampToRequest(
				meStorage.extract(key, amount, Actionable.MODULATE, actionSource), amount);
		long extractCost = System.nanoTime() - extractStart;
		Ae2GlobalInsertBudget.recordCost(gameTick, extractCost);
		if (extracted <= 0) {
			if (keyBackoff != null) {
				keyBackoff.recordFailure(key, System.nanoTime());
			}
			boolean slow = Ae2GlobalInsertBudget.isSlowOperation(extractCost);
			return new PullBatchResult(0, extractCost, slow, slow, !slow);
		}
		Ae2NetworkInventoryView.recordExtract(holder, gameTick, meStorage, key, extracted);
		ItemStack stack = key.toStack(SaturatingMath.saturatingToInt(extracted));
		int originalCount = stack.getCount();
		int slotCount = inputSlots.size();
		for (int slotOffset = 0; slotOffset < slotCount && !stack.isEmpty(); slotOffset++) {
			int slotIdx = (slotStart + slotOffset) % slotCount;
			IInventorySlot slot = inputSlots.get(slotIdx);
			if (slot == null) continue;
			int quota = SaturatingMath.saturatingToInt(inputSlotCapacities[slotIdx]);
			if (quota <= 0) continue;
			int toInsert = Math.min(stack.getCount(), quota);
			if (toInsert == stack.getCount()) {
				ItemStack remainder = slot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				int remainderCount = remainder.isEmpty() ? 0 : Math.min(toInsert, remainder.getCount());
				if (remainderCount < toInsert) {
					stack = remainderCount == 0 ? ItemStack.EMPTY : remainder;
				}
			} else {
				ItemStack remainder = slot.insertItem(stack.copyWithCount(toInsert),
						Action.EXECUTE, AutomationType.INTERNAL);
				int insertedNow = toInsert - (remainder.isEmpty() ? 0 : remainder.getCount());
				if (insertedNow > 0) stack.shrink(insertedNow);
			}
		}
		int insertedCount = originalCount - (stack.isEmpty() ? 0 : stack.getCount());
		boolean slowExtract = Ae2GlobalInsertBudget.isSlowOperation(extractCost);
		if (insertedCount > 0 && keyBackoff != null) {
			if (slowExtract) {
				keyBackoff.recordFailure(key, System.nanoTime());
			} else {
				keyBackoff.recordSuccess(key);
			}
		}
		boolean hadLeftover = !stack.isEmpty();
		if (hadLeftover) {
			int leftoverRemaining = Ae2LeftoverReturner.returnLeftoverToMe(meStorage, key, stack, actionSource,
					null, level, pos, inputSlots);
			if (leftoverRemaining > 0) {
				LogThrottle.error("ae2_pull_leftover_loss_batch",
						"AE2 批量拉取剩余物品回送失败，存在丢失风险 (5秒内仅首条输出) key={}", key);
			}
		}
		return new PullBatchResult(insertedCount, extractCost, slowExtract, hadLeftover,
				!slowExtract && !hadLeftover);
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

	@SuppressWarnings("unchecked")
	private static Ae2KeyBackoffRegistry<AEItemKey> getOrCreateKeyBackoff(Ae2OutputStateHolder holder) {
		Object cached = holder.getPushState().getInputKeyBackoffRegistry();
		if (cached instanceof Ae2KeyBackoffRegistry<?> registry) {
			return (Ae2KeyBackoffRegistry<AEItemKey>) registry;
		}
		Ae2KeyBackoffRegistry<AEItemKey> registry = new Ae2KeyBackoffRegistry<>();
		holder.getPushState().setInputKeyBackoffRegistry(registry);
		return registry;
	}

	private static Ae2InputFairnessScheduler getOrCreateFairnessScheduler(Ae2OutputStateHolder holder) {
		Object cached = holder.getPushState().getInputFairnessScheduler();
		if (cached instanceof Ae2InputFairnessScheduler scheduler) {
			return scheduler;
		}
		Ae2InputFairnessScheduler scheduler = new Ae2InputFairnessScheduler();
		holder.getPushState().setInputFairnessScheduler(scheduler);
		return scheduler;
	}

	/**
	 * 拉取条目 — 缓存扫描结果，包级可见供 {@link Ae2OutputPusher.ReusableBuffers#pullList} 复用。
	 */
	static final class PullEntry {
		AEItemKey key;
		int remaining;
		boolean unlimited;
		/** Pre-computed "matches a configured entry" flag used by the marked-first sort. */
		boolean marked;
		boolean combBlock;
		long servedInWindow;

		PullEntry(AEItemKey key, int amount) {
			reset(key, amount, false);
		}

		void reset(AEItemKey key, int amount, boolean unlimited) {
			this.key = key;
			this.remaining = amount;
			this.unlimited = unlimited;
			this.marked = false;
			this.combBlock = false;
			this.servedInWindow = 0L;
		}
	}
}
