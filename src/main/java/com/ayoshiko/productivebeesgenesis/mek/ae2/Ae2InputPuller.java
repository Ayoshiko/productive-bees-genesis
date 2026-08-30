package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
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

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
	 * AE2 输入拉取器（与 {@link Ae2OutputPusher} 对称）— 将 AE2 网络中的蜜脾拉取到离心机输入槽。
	 * 调用方需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫。
	 * @since 2.0.0
	 */
public final class Ae2InputPuller {
	/**
	 * Refresh the candidate key list at most every half second. Inventory amounts are
	 * resolved again for every pull; reserve-aware modes are clamped against a live
	 * simulation immediately before extraction, so this only delays discovery of new types.
	 */
	private static final long CANDIDATE_KEY_REFRESH_INTERVAL_TICKS = 10L;

	private static final Comparator<PullEntry> PULL_ENTRY_ORDER = (a, b) -> {
		if (a.marked != b.marked) return Boolean.compare(b.marked, a.marked);
		if (a.smelting != b.smelting) return Boolean.compare(b.smelting, a.smelting);
		if (a.combBlock != b.combBlock) return Boolean.compare(b.combBlock, a.combBlock);
		return Long.compare(a.servedInWindow, b.servedInWindow);
	};

	/** 异常日志计数器 — 用于在日志中显示累计出现次数（LogThrottle 已负责节流） */
	private static final AtomicLong PULL_EXCEPTION_COUNTER = new AtomicLong(0);

	/**
	 * Result of one per-key extract and local distribution attempt.
	 * <p>
	 * degraded/healthy 只描述「本次网络操作的健康度」，三者可同时为 false：
	 * 那是 <b>中性跳过</b>（全服预算耗尽、pending 类型位已满、请求量为 0），
	 * 本机什么都没做，绝不能据此推进整机退避指数 —— 否则一台机器的慢 insert
	 * 会通过全服预算把同网络的其他机器一起推入 1 秒退避并互相续命。
	 */
	private record PullBatchResult(int insertedCount, long extractCostNanos,
			boolean slowExtract, boolean degraded, boolean healthy) {

		/** 中性跳过：不触碰退避状态。 */
		static PullBatchResult skipped() {
			return new PullBatchResult(0, 0L, false, false, false);
		}
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

		// 3. Level null 守卫（已在开头获取，复用避免重复调用）
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
		// 5.5 低 TPS 降级 —— 限流而非停机。原实现在 TPS<10 时直接 return，
		// 而推送/加工/回送都无此闸门，卡服时表现为「机器在线、产物正常、就是不拉取」；
		// 滚动平均贴在阈值附近时还会出现「两台相同配置只有一台拉」「过一会儿自己好了」。
		if (Ae2LowTpsGate.shouldSkip(
				ServerTickTimeMonitor.getInstance().getTps(currentTick), pullCounter)) {
			return;
		}
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
		// Global unlimited applies after filter admission. Per-entry unlimited remains
		// independent and is carried by each PullEntry.
		boolean unlimitedMode = filter != null && filter.hasUnlimitedEntries();
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
		// 先处理上次抽取后未能落槽或回送 ME 的物品，避免新抽取继续扩大待处理所有权。
		boolean hadPendingItems = holder.getPendingItemBuffer().size() > 0;
		retryPendingItems(level, holder, meStorage, inputSlots, pos, currentTick);
		if (hadPendingItems) host.productivebeesgenesis$markAe2StateChanged();

		// 9. 获取复用缓冲与调度状态；同样延后到真正需要扫描网络库存时。
		Ae2PushBuffers buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		boolean smeltingEnabled = MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(host);
		// smelt 输入的标签表达式过滤：未配置时使用 ALLOW_ALL，热路径零额外开销。
		Ae2TagFilter tagFilter = holder.getAeTagFilter();
		boolean tagFilterActive = tagFilter != null && tagFilter.isActive();
		int tagGeneration = tagFilter == null ? 0 : tagFilter.getGeneration();
		Ae2InputCandidatePolicy.SmeltingTagGate tagGate = tagFilterActive
				? key -> buffers.tagFilterCache.allows(tagFilter, key)
				: Ae2InputCandidatePolicy.SmeltingTagGate.ALLOW_ALL;
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
		boolean ignoreNbt = holder.isAeInputNbtIgnore();
		boolean globalNetworkStock = filter != null && filter.isGlobalNetworkStock();
		boolean hasNetworkStockEntries = filter != null && !globalNetworkStock
				&& filter.hasNetworkStockEntries();
		boolean directOnly = filter != null
				&& filter.getFilterMode() == Ae2InputFilter.FilterMode.WHITELIST
				&& filter.hasOnlyNetworkStockEntries()
				&& !filter.isGlobalNetworkStock()
				&& !ignoreNbt;
		if (directOnly) {
			// Network-stock whitelist entries use exact keys and avoid a full inventory scan.
			for (Ae2InputFilter.DirectEntry direct : directEntries) {
				if (pullList.size() >= maxTypesToCollect) break;
				AEItemKey key = direct.key();
				Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
						level, key, smeltingEnabled, buffers.smeltingInputCache, tagGate);
				if (!kind.isAllowed() || !pullKeys.add(key)) continue;
				long available = Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
						availableStacks, meStorage, key, Long.MAX_VALUE, ActionSourceHolder.INSTANCE);
				long configuredLimit = filter.getPullLimitIfAllowed(key, available, ignoreNbt);
				if (configuredLimit == Ae2InputFilter.PULL_DISALLOWED) {
					pullKeys.remove(key);
					continue;
				}
				if (configuredLimit >= 0L) {
					available = Math.min(available, configuredLimit);
				}
				if (available > 0) {
					pullList.add(buffers.borrowPullEntry(key, SaturatingMath.saturatingToInt(available),
							filter.isUnlimitedForKey(key, ignoreNbt)));
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
					Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
							level, key, smeltingEnabled, buffers.smeltingInputCache, tagGate);
					if ((!direct.networkStock() && !filter.isGlobalNetworkStock())
							|| !kind.isAllowed() || !pullKeys.add(key)) continue;
					long available = Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
						availableStacks, meStorage, key, Long.MAX_VALUE, ActionSourceHolder.INSTANCE);
					long configuredLimit = filter.getPullLimitIfAllowed(key, available, ignoreNbt);
					if (configuredLimit == Ae2InputFilter.PULL_DISALLOWED) {
						pullKeys.remove(key);
						continue;
					}
					if (configuredLimit >= 0L) {
						available = Math.min(available, configuredLimit);
					}
					if (available > 0) {
						pullList.add(buffers.borrowPullEntry(key, SaturatingMath.saturatingToInt(available),
								filter.isUnlimitedForKey(key, ignoreNbt)));
					} else {
						pullKeys.remove(key);
					}
				}
			}
			// Scan the cached inventory directly. A bounded prefix scratch preserves cursor
			// wraparound without copying every key in a large AE network into each tile.
			List<AEItemKey> selectedKeys = buffers.borrowScanSelectedKeys();
			selectedKeys.clear();
			Ae2PullCandidateAmounts candidateAmounts = buffers.borrowScanCandidateAmounts();
			candidateAmounts.clear();
			List<AEItemKey> prefixKeys = buffers.borrowScanPrefixKeys();
			List<AEItemKey> smeltingCandidateKeys = buffers.borrowScanSmeltingCandidateKeys();
			List<AEItemKey> candidateKeys = buffers.borrowScanCandidateKeys();
			long recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
			if (buffers.needsScanCandidateRefresh(availableStacks, currentTick,
					CANDIDATE_KEY_REFRESH_INTERVAL_TICKS, recipeVersion, smeltingEnabled, tagGeneration)) {
				smeltingCandidateKeys.clear();
				candidateKeys.clear();
				for (var entry : availableStacks) {
					if (!(entry.getKey() instanceof AEItemKey itemKey)) continue;
					Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
							level, itemKey, smeltingEnabled, buffers.smeltingInputCache, tagGate);
					if (kind.isSmelting()) smeltingCandidateKeys.add(itemKey);
					else if (kind == Ae2InputCandidatePolicy.CandidateKind.COMB) candidateKeys.add(itemKey);
				}
				buffers.markScanCandidateRefresh(availableStacks, currentTick, recipeVersion, smeltingEnabled,
						tagGeneration);
			}
			// 总收集上限与旧实现一致：whitelist 预置条目也计入 maxTypesToCollect
			int scanCap = Math.max(0, maxTypesToCollect - pullList.size());
			// Candidate selection remains cheap and bounded. A provisional high stock value
			// keeps under-reporting external storage compatible; the final live reserve gate
			// clamps every guarded key immediately before MODULATE.
			Predicate<AEItemKey> acceptableCandidate = key -> {
				if (pullKeys.contains(key)) return false;
				boolean reserveGuarded = globalNetworkStock || (hasNetworkStockEntries
						&& filter.getReserveFloorForKey(key, ignoreNbt) >= 0L);
				long available = reserveGuarded ? Long.MAX_VALUE : availableStacks.get(key);
				int amount = getPullCandidateAmount(
						level, key, available, filter, ignoreNbt,
						smeltingEnabled, buffers.smeltingInputCache, tagGate);
				if (amount <= 0) return false;
				candidateAmounts.put(key, amount);
				return true;
			};
			Ae2CursorScan.collectPrioritized(selectedKeys, prefixKeys, smeltingCandidateKeys,
					candidateKeys, candidateCursor, scanCap, acceptableCandidate);
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
			entry.reserveFloor = filter == null ? -1L
					: filter.getReserveFloorForKey(entry.key, sortIgnoreNbt);
			entry.smelting = Ae2InputCandidatePolicy.classify(
					level, entry.key, smeltingEnabled, buffers.smeltingInputCache, tagGate).isSmelting();
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
		// 多类型公平分配（修复 smelt 候选被同一种物品饿死）：排序器只决定顺序，
		// 排在第一的类型原先会同时吃掉整个 normalQuota 与全部空槽（高堆叠槽上限千万级，
		// 一旦占满其他类型的 getSlotRemainingCapacity 恒为 0），顺序公平落不到实际。
		// 第 0 轮给每个类型限「槽数/类型数」条新车道 + 配额等分份额；第 1 轮解除上限
		// 把没人要的余量填满，故总吞吐与旧实现一致。单类型时两个上限都退化为无限。
		int laneBudget = Ae2InputLaneFairness.emptyLaneBudget(processCount, typeCount);
		long fairShare = Ae2InputLaneFairness.typeQuotaShare(normalQuota, typeCount);
		int passes = typeCount > 1 ? 2 : 1;
		boolean fairPassTruncated = false;
		for (int pass = 0; pass < passes && totalPulled < inputCapacity; pass++) {
			boolean fairPass = pass == 0 && typeCount > 1;
			// 补齐轮只在公平轮确实被上限截断时才跑：否则会为每个类型重复一遍
			// 逐槽 SIMULATE 探测（processCount × typeCount 次），纯属浪费。
			if (!fairPass && !fairPassTruncated) break;
			for (int typeOffset = 0; typeOffset < typeCount && totalPulled < inputCapacity; typeOffset++) {
				PullEntry entry = pullList.get(typeOffset);
				if (entry.remaining <= 0 || keyBackoff.shouldSkip(entry.key, nanoNow)) continue;

				// 先汇总该类型在所有可用槽位中的容量，然后一次 ME extract，再本地分发。
				// 同一类型的模拟探针在所有输入槽中复用；SIMULATE 不会修改传入栈，
				// 这样可避免高倍加速下为每个「类型 × 槽位」重复创建 ItemStack。
				ItemStack slotProbe = entry.key.toStack(1);
				long typeAvailable = 0L;
				int newLanes = 0;
				boolean lanesSuppressed = false;
				for (int slotOffset = 0; slotOffset < processCount; slotOffset++) {
					int slotIdx = (slotStart + slotOffset) % processCount;
					IInventorySlot slot = inputSlots.get(slotIdx);
					long slotQuota = entry.unlimited ? Long.MAX_VALUE : perSlotQuota;
					long slotCapacity = slot == null ? 0L
							: Math.min(slotQuota, getSlotRemainingCapacity(slot, entry.key, slotProbe));
					if (slotCapacity > 0L && fairPass && slot.getStack().isEmpty()) {
						// 空槽 = 一条新车道；超出份额的空槽本轮留给其他类型
						if (newLanes >= laneBudget) {
							slotCapacity = 0L;
							lanesSuppressed = true;
						} else {
							newLanes++;
						}
					}
					inputSlotCapacities[slotIdx] = slotCapacity;
					if (slotCapacity > 0L) {
						typeAvailable = SaturatingMath.saturatingAdd(typeAvailable, slotCapacity);
					}
				}
				if (typeAvailable <= 0L) {
					// 车道被压制导致本类型这轮完全拿不到槽：补齐轮必须再给它一次机会
					if (lanesSuppressed) fairPassTruncated = true;
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
				if (entry.unlimited) unlimitedAttempted = true;
				try {
					PullBatchResult batchResult = pullBatchForType(level, holder, entry.key, toPull,
							entry.reserveFloor, meStorage,
							ActionSourceHolder.INSTANCE,
						inputSlots, inputSlotCapacities, slotStart, pos, keyBackoff,
						buffers.fingerprintCache);
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
					degradedExtractDetected = true;
				}
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

	private static int getPullCandidateAmount(Level level, Object rawKey, long available, Ae2InputFilter filter,
			boolean ignoreNbt, boolean smeltingEnabled, Ae2SmeltingInputCache smeltingInputCache,
			Ae2InputCandidatePolicy.SmeltingTagGate tagGate) {
		if (available <= 0 || !(rawKey instanceof AEItemKey itemKey)
				|| !Ae2InputCandidatePolicy.classify(level, itemKey, smeltingEnabled,
						smeltingInputCache, tagGate).isAllowed()) return 0;
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
			long reserveFloor, MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, long[] inputSlotCapacities, int slotStart, BlockPos pos,
			Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, Ae2FingerprintCache fingerprintCache) {
		// 全服慢网络操作预算（Spark w4xREcN1HI：EnderDrives 的 extract 同样写 WAL，
		// appendWalRecordsLocked→force 在主线程 fsync 5-10ms/次）。预算耗尽时跳过本轮
		// extract — 物品留在 ME 网络无损，退避结束后由冷却节奏自然恢复，吞吐不受损。
		long gameTick = level.getGameTime();
		if (Ae2GlobalInsertBudget.isExhausted(gameTick)) {
			return PullBatchResult.skipped();
		}
		// 抽取前的兜底闸门：只要求 pending 缓冲还有「类型条目位」可登记，不限制抽取数量。
		// extract 一旦执行就无法撤回，若之后既无法落槽、又无法回送 ME、也无处登记，
		// 物品就无处安放。旧实现把这部分 dropItemStack 到世界，而原版
		// Containers.dropItemStack 把一个大栈按 maxStackSize 拆成多个 ItemEntity，
		// 因此在「输入槽满 + 缓冲满」的稳定态下每轮都在刷掉落物，
		// 本整合包 ME 存量达 1e8 级时直接堆出 75 万个 ItemEntity 打满 4GB 堆，
		// 表现为「进入存档卡在 100% 加载界面、日志无输出、无崩溃报告」。
		// 注意：这里不能用数量额度去截断 request —— 无限拉取模式要求一次拉满槽位堆叠上限
		// （无限多元工厂单槽 17M），按缓冲额度限流会把吞吐压到 131K，属功能回退。
		// 缓冲只承载「分发 + 回送 ME 之后仍剩下的」少量物品，数量本身不占 NBT 体积。
		// 指纹按 AEItemKey 记忆化：编码本身是 Codec + StringTagVisitor 遍历，
		// 时间加速下每刻上千次（spark ejYMNQjDf7 中本处 432ms / 1.44%）。
		String fingerprint = fingerprintCache.get(key, level.registryAccess());
		Ae2PendingItemBuffer pending = holder.getPendingItemBuffer();
		if (!pending.canRegister(fingerprint)) {
			LogThrottle.warn("ae2_pending_item_capacity",
					"AE2 输入剩余物缓冲类型已满（{} 种），本轮跳过抽取（物品留在 ME 网络无损）key={}",
					Ae2PendingItemBuffer.MAX_ENTRIES, key);
			return PullBatchResult.skipped();
		}
		if (amount <= 0) return PullBatchResult.skipped();

		long reserveQueryCost = 0L;
		boolean slowReserveQuery = false;
		if (reserveFloor >= 0L) {
			// AE2 的 KeyCounter 直到 tick 末才刷新。实际抽取前重新模拟，确保同刻的
			// 其他离心机或外部设备已提交的消耗也会压低本次请求量。
			long queryCap = SaturatingMath.saturatingAdd(reserveFloor, amount);
			long queryStart = System.nanoTime();
			long liveExtractable;
			try {
				liveExtractable = Ae2NetworkInventoryView.liveExtractableAmount(
						meStorage, key, queryCap, actionSource);
			} catch (LinkageError | RuntimeException e) {
				reserveQueryCost = System.nanoTime() - queryStart;
				Ae2GlobalInsertBudget.recordCost(gameTick, reserveQueryCost,
						Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
				if (keyBackoff != null) keyBackoff.recordFailure(key, System.nanoTime());
				LogThrottle.warn("ae2_reserve_query",
						"AE2 库存保留实时查询失败，本轮跳过抽取 key={}: {}", key, e.toString());
				return new PullBatchResult(0, reserveQueryCost,
						Ae2StorageHealth.isPathological(reserveQueryCost), true, false);
			}
			reserveQueryCost = System.nanoTime() - queryStart;
			Ae2GlobalInsertBudget.recordCost(gameTick, reserveQueryCost,
						Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
			slowReserveQuery = Ae2StorageHealth.isPathological(reserveQueryCost);
			amount = Ae2FilterPullPolicy.reserveSafeRequest(amount, liveExtractable, reserveFloor);
			boolean reserveReached = amount <= 0;
			if (reserveReached || Ae2GlobalInsertBudget.isExhausted(gameTick)) {
				// 256x 加速仍只执行一次完整 tick，但保留线稳态下会每个真实 tick 重试。
				// 按 key 墙钟退避可把空探针降到最高每秒一次；补货并成功抽取后立即清除。
				if ((reserveReached || slowReserveQuery) && keyBackoff != null) {
					keyBackoff.recordFailure(key, System.nanoTime());
				}
				return new PullBatchResult(0, reserveQueryCost, slowReserveQuery,
						slowReserveQuery, !slowReserveQuery);
			}
		}
		long extractStart = System.nanoTime();
		long extracted = SaturatingMath.clampToRequest(
				meStorage.extract(key, amount, Actionable.MODULATE, actionSource), amount);
		long extractCost = System.nanoTime() - extractStart;
		Ae2GlobalInsertBudget.recordCost(gameTick, extractCost,
				Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
		long maxNetworkCost = Math.max(reserveQueryCost, extractCost);
		boolean slowNetworkOperation = slowReserveQuery || Ae2StorageHealth.isPathological(extractCost);
		if (extracted <= 0) {
			if (keyBackoff != null) {
				keyBackoff.recordFailure(key, System.nanoTime());
			}
			return new PullBatchResult(0, maxNetworkCost, slowNetworkOperation,
					slowNetworkOperation, !slowNetworkOperation);
		}
		Ae2NetworkInventoryView.recordExtract(holder, gameTick, meStorage, key, extracted);
		// 只登记「分发+回送之后真正剩下的」数量，不再预登记整批抽取量。
		// 抽取前已确认该指纹有条目位可用，因此剩余量一定能被完整登记，
		// 不存在「登记不下 → 掉落世界」的分支。
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
		boolean slowExtract = slowNetworkOperation;
		if (insertedCount > 0 && keyBackoff != null) {
			if (slowExtract) {
				keyBackoff.recordFailure(key, System.nanoTime());
			} else {
				keyBackoff.recordSuccess(key);
			}
		}
		boolean hadLeftover = !stack.isEmpty();
		boolean leftoverStranded = false;
		if (hadLeftover) {
			int leftoverRemaining = Ae2LeftoverReturner.returnLeftoverToMe(meStorage, key, stack, actionSource,
					holder.getPushState().getReturnBackoff(), level, pos, inputSlots);
			if (leftoverRemaining > 0) {
				// 既没落槽也没回送成功的部分才登记 pending，由下一轮 retryPendingItems 处理。
				// 抽取前的条目位检查保证这里必定登记成功（数量无上限，只用饱和加法防溢出）。
				leftoverStranded = true;
				pending.enqueue(fingerprint, leftoverRemaining, gameTick);
				pending.recordFailure(fingerprint, gameTick);
				if (level.getBlockEntity(pos) != null) level.getBlockEntity(pos).setChanged();
				LogThrottle.warn("ae2_pull_leftover_pending",
						"AE2 批量拉取剩余物品已登记 pending，等待下一轮回送 key={} count={}",
						key, leftoverRemaining);
			}
		}
		// 只有「剩余物既落不进槽、又回送不进 ME」才算故障。全部回送成功属正常稳态
		// （无限拉取本就按槽位上限请求，多出来的退回网络），原实现把它也算 degraded，
		// 会让整机 returnBackoff 长期停在退避窗口里，表现为拉取时断时续。
		return new PullBatchResult(insertedCount, maxNetworkCost, slowExtract, leftoverStranded,
				!slowExtract && !leftoverStranded);
	}

	/** 重试宿主级 pending 物品；每次最多处理四种 key，避免断网时占满 tick。 */
	private static void retryPendingItems(Level level, Ae2OutputStateHolder holder, MEStorage meStorage,
			List<IInventorySlot> inputSlots, BlockPos pos, long currentTick) {
		Ae2PendingItemBuffer pending = holder.getPendingItemBuffer();
		int attempts = 0;
		for (Ae2PendingItemBuffer.PendingItem entry : pending.snapshot(currentTick)) {
			if (attempts++ >= 4) break;
			AEItemKey key = Ae2ItemFingerprint.decode(entry.fingerprint(), level.registryAccess());
			if (key == null) {
				pending.recordFailure(entry.fingerprint(), currentTick);
				LogThrottle.warn("ae2_pending_item_decode",
						"AE2 pending 物品指纹无法解析，保留等待迁移 fingerprint={}", entry.fingerprint());
				continue;
			}
			// 单次只尝试 int 能表达的部分：pending 数量现在无上限（可超过 Integer.MAX_VALUE），
			// 用 update 覆盖会把未尝试的超额部分抹掉造成物品丢失，改为按实际交付量 consume。
			long attempt = Math.min(entry.amount(), Integer.MAX_VALUE);
			ItemStack stack = key.toStack((int) attempt);
			for (IInventorySlot slot : inputSlots) {
				if (slot == null || stack.isEmpty()) continue;
				try {
					stack = slot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				} catch (RuntimeException e) {
					LogThrottle.warn("ae2_pending_item_slot",
							"AE2 pending 物品回插槽异常，跳过该槽: {}", e.toString());
				}
			}
			long remaining = stack.isEmpty() ? 0L : stack.getCount();
			if (remaining > 0L) {
				remaining = Ae2LeftoverReturner.returnLeftoverToMe(meStorage, key, stack,
						ActionSourceHolder.INSTANCE, holder.getPushState().getReturnBackoff(),
						level, pos, inputSlots);
			}
			long delivered = attempt - remaining;
			if (delivered > 0L) pending.consume(entry.fingerprint(), delivered, currentTick);
			if (remaining > 0L) pending.recordFailure(entry.fingerprint(), currentTick);
		}
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
		/** Effective AE2 stock floor, or -1 when no reserve policy applies. */
		long reserveFloor;
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
			this.smelting = false;
			this.reserveFloor = -1L;
			this.combBlock = false;
			this.servedInWindow = 0L;
		}
	}
}
