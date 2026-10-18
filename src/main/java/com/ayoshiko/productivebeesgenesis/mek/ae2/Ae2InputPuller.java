package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.inventory.BulkItemPullScope;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.network.Ae2CentrifugeInputReturnService;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
	 * AE2 输入拉取器（与 {@link Ae2OutputPusher} 对称）— 将 AE2 网络中的蜜脾拉取到离心机输入槽。
	 * 调用方需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫。
	 * @since 2.0.0
	 */
public final class Ae2InputPuller {
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
	/** 正式加工与新取料共用出口缓存，满槽和取料冷却不跳过这项检查。 */
	public static com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.OutputAvailability resourceOutputAvailability(
			BlockEntity entity, com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe recipe) {
		if (!(entity instanceof IAe2InputHost host) || entity.getLevel() == null) {
			return com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.OutputAvailability.AVAILABLE;
		}
		var holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.OutputAvailability.DEFERRED;
		var buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		var storage = holder.getPushState().getCachedNodeState(host) == Ae2GridNodeManager.STATE_ONLINE
				? Ae2GridNodeManager.getCachedMeStorage(holder, host) : null;
		buffers.resourceInputGate.prepare(host, holder, buffers, storage, entity.getLevel().getGameTime());
		return switch (buffers.resourceInputGate.decision(recipe)) {
			case ACCEPTED -> com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.OutputAvailability.AVAILABLE;
			case REJECTED -> com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.OutputAvailability.UNAVAILABLE;
			case DEFERRED -> com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.OutputAvailability.DEFERRED;
		};
	}

	public static void pullInputs(IAe2InputHost host, int executedBatchMultiplier) {
		// Spark 优化：缓存 holder 到局部变量，消除后续 10+ 次冗余 getAe2StateHolder() 接口分发
		// （每次2层接口分发：getLifecycleHandler→getStateHolder，热力图显示为热点）
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null || holder.getPendingItemBuffer().isBlocked()) return;
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		long currentTick = level.getGameTime();
		if (holder.getReusableBuffers() instanceof Ae2PushBuffers existingBuffers
				&& existingBuffers.resourceInputGate.hasWaitingRecipes()) {
			var storage = holder.getPushState().getCachedNodeState(host) == Ae2GridNodeManager.STATE_ONLINE
					? Ae2GridNodeManager.getCachedMeStorage(holder, host) : null;
			existingBuffers.resourceInputGate.prepare(host, holder, existingBuffers, storage, currentTick);
			existingBuffers.resourceInputGate.refreshWaitingRecipes();
		}
		if (holder.isConfigCacheStale(currentTick)) {
			holder.refreshConfigCache(currentTick);
		}
		List<IInventorySlot> inputSlots = host.productivebeesgenesis$getInputSlotsForPull();
		if (inputSlots == null || inputSlots.isEmpty()) return;
		if (host instanceof BlockEntity blockEntity
				&& Ae2CentrifugeInputReturnService.returnUnprocessableInputs(blockEntity, inputSlots)) {
			return;
		}

		// Known pending assets are recovery work and continue even when new input pulling is disabled.
		boolean inputPullEnabled = holder.isInputPullEnabled();
		boolean pendingRetryable = holder.getPendingItemBuffer().hasRetryableItems(currentTick);
		if (!inputPullEnabled && !pendingRetryable) return;

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
		boolean pendingRetriedEarly = false;
		if (pendingRetryable) {
			MEStorage pendingStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
			if (pendingStorage != null) {
				retryPendingItems(level, holder, pendingStorage, inputSlots, currentTick,
						host instanceof BlockEntity entity ? entity : null);
				host.productivebeesgenesis$markAe2StateChanged();
				pendingRetriedEarly = true;
			}
		}
		// Pending recovery above is independent from the switch for accepting new inputs.
		if (!inputPullEnabled || holder.getPendingItemBuffer().isBlocked()) return;

		// 3. Level 与当前游戏刻已在入口获取并复用，避免重复调用。

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
		int initialIntervalTicks = Ae2PullFairnessPolicy.effectiveInterval(
				holder.getCachedInputIntervalTicks(), M);
		if (!holder.hasCompletedInputPull()
				&& !Ae2PullFairnessPolicy.isInitialPhaseReady(pullCounter, initialIntervalTicks,
						host.productivebeesgenesis$getAe2BlockPos().asLong())) return;
		if (holder.hasCompletedInputPull()
				&& pullCounter - holder.getLastPullCounter() < cooldownTicks) return;

		// 6. 输入槽列表已在入口获取，满槽时不触碰 AE2 服务缓存。
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
		// Input has its own per-host extract budget. The shared output token can be
		// consumed before this pass, leaving an otherwise healthy input idle.
		BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();
		// 先处理上次抽取后未能落槽或回送 ME 的物品，避免新抽取继续扩大待处理所有权。
		boolean hasRetryablePending = holder.getPendingItemBuffer().hasRetryableItems(currentTick);
		if (hasRetryablePending && !pendingRetriedEarly) {
			retryPendingItems(level, holder, meStorage, inputSlots, currentTick,
					host instanceof BlockEntity entity ? entity : null);
			host.productivebeesgenesis$markAe2StateChanged();
		}
		if (holder.getPendingItemBuffer().isBlocked()) return;

		// 9. 获取复用缓冲与调度状态；同样延后到真正需要扫描网络库存时。
		Ae2PushBuffers buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		boolean smeltingEnabled = MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(host);
		// 标签表达式过滤：未配置时使用 ALLOW_ALL，热路径零额外开销。
		Ae2TagFilter tagFilter = holder.getAeTagFilter();
		boolean tagFilterActive = tagFilter != null && tagFilter.isActive();
		int tagGeneration = tagFilter == null ? 0 : tagFilter.getGeneration();
		boolean ignoreNbt = holder.isAeInputNbtIgnore();
		// 标签表达式独立筛选候选物品；F 过滤模式只决定已标记槽位的白/黑名单准入。
		Ae2InputCandidatePolicy.SmeltingTagGate tagGate = tagFilterActive
				? key -> buffers.tagFilterCache.allows(tagFilter, key)
				: Ae2InputCandidatePolicy.SmeltingTagGate.ALLOW_ALL;
		// 蜜脾可处理性门：本机无对应离心配方的蜜脾（只有蜂、需其它模组机器处理）
		// 在分类阶段就被拒，避免占用候选类型窗口并每轮空转探测槽位。
		// 判定按 (Item, bee_type) 记忆化，只在 10 tick 候选刷新与条目极少的直探路径调用；
		// 每轮限速首次判定次数，防止配方重载后的首轮刷新把全量遍历堆在同一 tick。
		buffers.combProcessableCache.beginProbeWindow();
		buffers.resourceInputGate.prepare(host, holder, buffers, meStorage, currentTick);
		Ae2InputCandidatePolicy.CombProcessGate combGate =
				key -> buffers.resourceInputGate.resourcesAvailable(key)
						&& buffers.combProcessableCache.canProcess(host, key, smeltingEnabled);
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
		long nanoNow = System.nanoTime();
		// AE2 已在 StorageService 中维护网格库存缓存。直接调用 MEStorage.getAvailableStacks()
		// 会再次遍历每个存储单元；在大型 Omni Cell 网络中这正是 Spark 的主要热点。
		// 单次拉取固定使用同一快照，游标回绕也不会触发第二次网络聚合。
		KeyCounter availableStacks = null;
		List<Ae2InputFilter.DirectEntry> directEntries = filter != null && filter.hasDirectEntries()
				? filter.getDirectEntries() : List.of();
		if (!directEntries.isEmpty()) {
			boolean unresolved = directEntries.stream().anyMatch(entry -> entry.key() == null);
			if (unresolved) availableStacks = storageService.getCachedInventory();
			var resolvedKeys = Ae2ItemFingerprint.resolve(
					directEntries, availableStacks, level.registryAccess());
			for (Ae2InputFilter.DirectEntry direct : directEntries) {
				if (direct.key() != null) continue;
				AEItemKey resolved = resolvedKeys.get(direct.fingerprint());
				if (resolved != null) filter.resolveDirectKey(direct.index(), resolved);
			}
			directEntries = filter.getDirectEntries();
		}
		Ae2PullDecision decision = buffers.pullDecision;
		boolean stockPolicyActive = filter != null
				&& (filter.isGlobalNetworkStock() || filter.hasNetworkStockEntries());
		boolean directOnly = filter != null
				&& filter.getFilterMode() == Ae2InputFilter.FilterMode.WHITELIST
				&& !filter.hasFuzzyEntries()
				&& !ignoreNbt
				&& !tagFilterActive
				&& !directEntries.isEmpty();
		if (directOnly) {
			for (Ae2InputFilter.DirectEntry direct : directEntries) {
				// 非精确蜜脾条目还会匹配同 bee_type 的蜜脾块，不能只探测配置键。
				if (direct.key() == null
						|| (!filter.isPreciseMode() && CombFuzzyMatcher.isCombItem(direct.key()))) {
					directOnly = false;
					break;
				}
			}
		}
		if (!directOnly || stockPolicyActive) availableStacks = storageService.getCachedInventory();
		if (directOnly) {
			// 精确白名单的候选集合就是已解析配置键，无需扫描整个网络类型列表。
			int directStart = Ae2CursorScan.cursorStartIndex(
					directEntries, candidateCursor, Ae2InputFilter.DirectEntry::key);
			for (int directOffset = 0; directOffset < directEntries.size(); directOffset++) {
				if (pullList.size() >= maxTypesToCollect) break;
				Ae2InputFilter.DirectEntry direct = directEntries.get(
						(directStart + directOffset) % directEntries.size());
				AEItemKey key = direct.key();
				Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
						level, key, smeltingEnabled, buffers.smeltingInputCache, tagGate, combGate);
				if (!kind.isAllowed() || !pullKeys.add(key)) continue;
				boolean liveStockEntry = direct.networkStock() || filter.isGlobalNetworkStock();
				long available = liveStockEntry
						? Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
								availableStacks, meStorage, key, Long.MAX_VALUE, ActionSourceHolder.INSTANCE)
						: inputCapacity;
				long configuredLimit = filter.getPullLimitIfAllowed(key, available, ignoreNbt, tagFilterActive, decision);
				if (configuredLimit == Ae2InputFilter.PULL_DISALLOWED) {
					pullKeys.remove(key);
					continue;
				}
				if (configuredLimit >= 0L) {
					available = Math.min(available, configuredLimit);
				}
				if (available > 0) {
					var resourceDecision = buffers.resourceInputGate.decision(key);
					if (resourceDecision == Ae2ResourceInputGate.Decision.REJECTED) {
						pullKeys.remove(key);
						continue;
					}
					PullEntry entry = buffers.borrowPullEntry(key,
							SaturatingMath.saturatingToInt(available),
							unlimitedMode && decision.unlimited);
					entry.marked = decision.marked;
					entry.resourceDeferred = resourceDecision == Ae2ResourceInputGate.Decision.DEFERRED;
					entry.smelting = kind.isSmelting();
					entry.combBlock = CombFuzzyMatcher.isCombBlock(key);
					entry.reserveFloor = decision.reserveFloor;
					pullList.add(entry);
				} else {
					pullKeys.remove(key);
				}
			}
		} else {
			KeyCounter scanInventory = availableStacks;
			// 白名单精确条目优先直探：过滤配置刚变更时无需等待候选缓存刷新，且单条
			// SMELTING 指纹也不会依赖游标扫描。库存模式继续用实时可见量探测外部存储；
			// 普通条目沿用 AE2 已聚合的 KeyCounter，避免额外网络模拟。黑名单仍只走扫描。
			if (filter != null && filter.getFilterMode() != Ae2InputFilter.FilterMode.BLACKLIST) {
				int directStart = Ae2CursorScan.cursorStartIndex(
						directEntries, candidateCursor, Ae2InputFilter.DirectEntry::key);
				for (int directOffset = 0; directOffset < directEntries.size(); directOffset++) {
					if (pullList.size() >= maxTypesToCollect) break;
					Ae2InputFilter.DirectEntry direct = directEntries.get(
							(directStart + directOffset) % directEntries.size());
					AEItemKey key = direct.key();
					Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
							level, key, smeltingEnabled, buffers.smeltingInputCache, tagGate, combGate);
					boolean whitelistEntry = filter.getFilterMode() == Ae2InputFilter.FilterMode.WHITELIST;
					boolean liveStockEntry = direct.networkStock() || filter.isGlobalNetworkStock();
					if ((!whitelistEntry && !liveStockEntry)
							|| !kind.isAllowed() || !pullKeys.add(key)) continue;
					long available = liveStockEntry
							? Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
									availableStacks, meStorage, key, Long.MAX_VALUE, ActionSourceHolder.INSTANCE)
							: Math.max(0L, availableStacks.get(key));
					long configuredLimit = filter.getPullLimitIfAllowed(key, available, ignoreNbt, tagFilterActive, decision);
					if (configuredLimit == Ae2InputFilter.PULL_DISALLOWED) {
						pullKeys.remove(key);
						continue;
					}
					if (configuredLimit >= 0L) {
						available = Math.min(available, configuredLimit);
					}
					if (available > 0) {
						var resourceDecision = buffers.resourceInputGate.decision(key);
						if (resourceDecision == Ae2ResourceInputGate.Decision.REJECTED) {
							pullKeys.remove(key);
							continue;
						}
						PullEntry entry = buffers.borrowPullEntry(key,
								SaturatingMath.saturatingToInt(available),
								unlimitedMode && decision.unlimited);
						entry.marked = decision.marked;
						entry.resourceDeferred = resourceDecision == Ae2ResourceInputGate.Decision.DEFERRED;
						entry.smelting = kind.isSmelting();
						entry.combBlock = CombFuzzyMatcher.isCombBlock(key);
						entry.reserveFloor = decision.reserveFloor;
						pullList.add(entry);
					} else {
						pullKeys.remove(key);
					}
				}
			}
			// Scan the cached inventory directly. A bounded prefix scratch preserves cursor
			// wraparound without copying every key in a large AE network into each tile.
			List<AEItemKey> selectedKeys = buffers.borrowScanSelectedKeys();
			selectedKeys.clear();
			Set<AEItemKey> seenKeys = buffers.borrowScanSeenKeys();
			seenKeys.clear();
			Ae2PullCandidateAmounts candidateAmounts = buffers.borrowScanCandidateAmounts();
			candidateAmounts.clear();
			List<AEItemKey> smeltingCandidateKeys = buffers.borrowScanSmeltingCandidateKeys();
			List<AEItemKey> candidateKeys = buffers.borrowScanCandidateKeys();
			long recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
			Ae2NetworkCandidateDirectory.Snapshot networkDirectory = Ae2NetworkCandidateDirectory.get(
					meStorage, availableStacks, currentTick, availableStacks);
			if (buffers.needsScanCandidateRefresh(availableStacks, networkDirectory.generation(),
					recipeVersion, smeltingEnabled, tagGeneration)) {
				smeltingCandidateKeys.clear();
				candidateKeys.clear();
				// 共享目录只做与宿主无关的粗分类。普通物品才查询本机 SMELTING 配方；
				// 蜜脾则保留标签表达式与本机可处理性判定，防止不同等级/配置机器互相污染。
				for (AEItemKey itemKey : networkDirectory.ordinaryKeys()) {
					Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
							level, itemKey, smeltingEnabled, buffers.smeltingInputCache, tagGate, combGate);
					if (kind.isSmelting()) smeltingCandidateKeys.add(itemKey);
				}
				for (AEItemKey itemKey : networkDirectory.combKeys()) {
					Ae2InputCandidatePolicy.CandidateKind kind = Ae2InputCandidatePolicy.classify(
							level, itemKey, smeltingEnabled, buffers.smeltingInputCache, tagGate, combGate);
					if (kind == Ae2InputCandidatePolicy.CandidateKind.COMB) candidateKeys.add(itemKey);
				}
				buffers.markScanCandidateRefresh(availableStacks, networkDirectory.generation(), recipeVersion,
						smeltingEnabled, tagGeneration);
			}
			// 总收集上限与旧实现一致：whitelist 预置条目也计入 maxTypesToCollect
			int scanCap = Math.max(0, maxTypesToCollect - pullList.size());
			// Candidate selection remains cheap and bounded. A provisional high stock value
			// keeps under-reporting external storage compatible; the final live reserve gate
			// clamps every guarded key immediately before MODULATE. Keys in retry backoff do not
			// occupy the bounded window, allowing eligible comb fallback behind deferred smelting keys.
			Predicate<AEItemKey> acceptableCandidate = key -> {
				if (pullKeys.contains(key)) return false;
				if (keyBackoff.shouldSkip(key, nanoNow)) return false;
				long cachedAvailable = scanInventory.get(key);
				if (cachedAvailable <= 0L && !stockPolicyActive) return false;
				long limit;
				if (filter == null) {
					decision.set(true, false, false, -1L);
					limit = -1L;
				} else {
					limit = filter.getPullLimitIfAllowed(key, Long.MAX_VALUE, ignoreNbt, tagFilterActive, decision);
				}
				if (!decision.admitted) return false;
				long available = decision.reserveFloor >= 0L ? Long.MAX_VALUE : cachedAvailable;
				// 两个候选列表仅由上面的 classify 构建，并按配方/开关/标签代号失效；
				// 此处不再为每次拉取重复查询候选类型。
				int amount = SaturatingMath.saturatingToInt(limit < 0L ? available : Math.min(available, limit));
				if (amount <= 0) return false;
				if (buffers.resourceInputGate.decision(key) == Ae2ResourceInputGate.Decision.REJECTED) return false;
				candidateAmounts.put(key, amount, decision);
				return true;
			};
			AEItemKey smeltingCursor = holder.getInputSmeltingCandidateCursor() instanceof AEItemKey key
					? key : null;
			AEItemKey combCursor = holder.getInputCombCandidateCursor() instanceof AEItemKey key
					? key : null;
			int smeltingSelected = Ae2CursorScan.collectPrioritizedRotating(selectedKeys, seenKeys,
					smeltingCandidateKeys, candidateKeys, smeltingCursor, combCursor,
					scanCap, acceptableCandidate);
			for (int index = 0; index < selectedKeys.size(); index++) {
				AEItemKey key = selectedKeys.get(index);
				int amount = candidateAmounts.get(key);
				if (amount > 0 && pullKeys.add(key)) {
					PullEntry entry = buffers.borrowPullEntry(key, amount);
					candidateAmounts.apply(key, entry, unlimitedMode);
					entry.resourceDeferred = buffers.resourceInputGate.decision(key) == Ae2ResourceInputGate.Decision.DEFERRED;
					entry.smelting = index < smeltingSelected;
					entry.combBlock = CombFuzzyMatcher.isCombBlock(key);
					pullList.add(entry);
					if (entry.smelting) {
						holder.setInputSmeltingCandidateCursor(key);
					} else {
						holder.setInputCombCandidateCursor(key);
					}
				}
			}
			candidateAmounts.clear();
			seenKeys.clear();
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
		// 车道快照：容量规划是「候选类型 × 槽位」嵌套循环，19 车道 × 38 类型 = 722 次槽位读取，
		// 其中绝大多数只是「槽内物品与本类型不同 → 容量必为 0」。快照把每对读取压成一次数组查询，
		// 且一轮内唯一会改变槽内容的是我方自己的插入（机器消耗发生在其自身 tick 阶段），
		// 因此执行阶段每插入一个车道就刷新该条即可保持精确。
		// 必须在这里（回送剩余物之后）采集，否则会拿到回送前的旧内容。
		Ae2InputLaneSnapshot laneSnapshot = buffers.inputLaneSnapshot();
		laneSnapshot.capture(inputSlots, processCount, host instanceof BlockEntity entity ? entity : null);
		// 多类型公平分配（修复 smelt 候选被同一种物品饿死）：排序器只决定顺序，
		// 排在第一的类型原先会同时吃掉整个 normalQuota 与全部空槽（高堆叠槽上限千万级，
		// 一旦占满其他类型的 getSlotRemainingCapacity 恒为 0），顺序公平落不到实际。
		// 公平轮按「槽数/类型数」分配份额，并计入已有车道；补齐轮在所有需求均有车道后
		// 才借用空槽，避免暂缓的类型失去全部槽位。单类型时不限制车道份额。
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

				// 每个候选只构造一个探针，并复用当前规划轮的完整组件匹配缓存。
				ItemStack slotProbe = entry.key.toStack(1);
				Item keyItem = entry.key.getItem();
				int typeSlotStart = laneSnapshot.preferredStart(entry.key, slotStart);
				long typeAvailable = 0L;
				int occupiedLanes = 0;
				if (protectLanes) {
					for (int index = 0; index < processCount; index++) {
						if (laneSnapshot.item(index) == keyItem
								&& entry.matchesComponents(index, laneSnapshot.stack(index), slotProbe,
										laneSnapshot.patchSize(index), laneSnapshot.beeType(index))) occupiedLanes++;
					}
				}
				int newLaneBudget = Ae2InputLaneFairness.remainingLaneBudget(laneBudget, occupiedLanes);
				int newLanes = 0;
				boolean lanesSuppressed = false;
				for (int slotOffset = 0; slotOffset < processCount; slotOffset++) {
					int slotIdx = (typeSlotStart + slotOffset) % processCount;
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
					PullBatchResult batchResult = pullBatchForType(level, holder, entry.key, toPull,
							entry.reserveFloor, availableStacks, meStorage,
							ActionSourceHolder.INSTANCE,
						inputSlots, inputSlotCapacities, laneSnapshot, slotProbe, typeSlotStart, pos, keyBackoff,
						buffers.fingerprintCache, buffers.extractBudget);
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
		if (returnBackoff != null) {
			if (slowExtractDetected && !degradedExtractDetected) {
				returnBackoff.recordSuccessWithDelay(System.nanoTime(),
						Ae2StorageHealth.successfulExtractDelay(maxSlowExtractCost));
			} else if (slowExtractDetected) {
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
		} else if (!networkWorkDeferred) {
			holder.onInputPullFail(unlimitedAttempted);
		}
		holder.updateLastPullTick(currentTick);
		holder.updateLastPullCounter(pullCounter);

		// 拉取列表已使用完毕，clear 而非新建（复用 ReusableBuffers）
		pullList.clear();
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
	 * 计算输入槽总剩余容量的上界
	 * <br/>
	 * 空槽还没有候选类型，暂用 ItemStack 数量上界；真实请求仍由 laneCapacity 按候选约束。
	 * 用 getLimit(EMPTY) 会把可放 64 个锭的槽误估成 1，提前压低全机拉取配额。
	 * 非空槽调用 getLimit(stack)。返回 long 防止高等级工厂多槽累加溢出。
	 *
	 * @param slots 输入槽列表
	 * @return 所有输入槽剩余容量之和
	 */
	/** 暂缓的类型仍保留空槽份额，补齐轮不能把短暂退避变成永久挤占。 */
	static boolean hasUnassignedDemand(List<PullEntry> entries, Ae2InputLaneSnapshot lanes, int laneCount) {
		boolean hasEmptyLane = false;
		for (int index = 0; index < laneCount; index++) {
			if (lanes.item(index) == null) {
				hasEmptyLane = true;
				break;
			}
		}
		if (!hasEmptyLane) return false;
		for (PullEntry entry : entries) {
			if (entry.remaining <= 0) continue;
			boolean assigned = false;
			for (int index = 0; index < laneCount; index++) {
				if (entry.key.matches(lanes.stack(index))) {
					assigned = true;
					break;
				}
			}
			if (!assigned) return true;
		}
		return false;
	}

	private static long calculateInputCapacity(List<IInventorySlot> slots) {
		long total = 0;
		for (IInventorySlot slot : slots) {
			if (slot == null) continue;
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				total = SaturatingMath.saturatingAdd(total, Integer.MAX_VALUE);
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
	 * 用车道快照计算单条车道对指定候选 key 的剩余容量。
	 * <p>
	 * 与 {@link #getSlotRemainingCapacity} 逐条对应，只是「一轮内不会变化」的
	 * 槽位引用 / 槽内物品 / 该槽上限已由 {@link Ae2InputLaneSnapshot} 采好，
	 * 省掉「类型 × 槽位」次的 {@code List.get} + {@code getStack()} + {@code getItem()} 虚调用。
	 * 非 Mekanism 标准槽实现仍回退到完整路径，保证自定义 {@code IInventorySlot} 语义不变。
	 * <p>
	 * 注意：我方插入某个车道后必须调用 {@link Ae2InputLaneSnapshot#refresh(int)}，
	 * 否则后续类型会读到过期的车道内容。
	 */
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
		// 槽已满：本类型在该车道必定拿不到容量。把「判满」提到「判组件」之前 ——
		// 两者都只产出 0，但判满是两次数组读，判组件在「每格一种蜜蜂」下是
		// 逐对的组件比较。堵塞场景（输出槽长期积压 → 输入槽长期填满 → 离心机停吃）
		// 正是本模组 issue #2 描述的稳态，此处顺序调换让该稳态每次规划只剩数组读。
		// 语义不变：唯一副作用是组件记忆表少写一次，而下次该车道仍被同一「已满」短路。
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


	/**
	 * 计算单个输入槽对指定 key 的剩余容量
	 * <br/>
	 * 槽内已有不同类型物品时返回 0，使 round-robin 跳过该槽寻找空槽或同类型槽。
	 * <p>
	 * 热路径（Mekanism 标准槽）走 {@link #laneCapacity} 的快照版本；本方法保留给
	 * 自定义 {@code IInventorySlot} 实现与异常兜底，语义与原实现完全一致。
	 *
	 * @param slot  输入槽（null 时返回 0）
	 * @param entry 当前候选条目（key 为 null 时返回 0）
	 * @return 剩余容量（槽内物品与 key 不匹配时返回 0）
	 */
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

	/**
	 * Per-type batch pull: one ME extract, then local distribution across slots.
	 * This mirrors the AE2LT batching approach and reduces high-tier factory AE2 API calls.
	 */
	static PullBatchResult pullBatchForType(Level level, Ae2OutputStateHolder holder, AEItemKey key, int amount,
			long reserveFloor, KeyCounter cachedInventory, MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, long[] inputSlotCapacities, Ae2InputLaneSnapshot laneSnapshot,
			ItemStack slotProbe, int slotStart, BlockPos pos,
			Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, Ae2FingerprintCache fingerprintCache,
			Ae2ExtractBudget extractBudget) {
		// 调用方已取得网络工作许可；本机提取预算另行限制慢调用。
		long gameTick = level.getGameTime();
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
		//
		// 闸门只需要「条目表还有位置」这一位信息（hasFreeEntrySlot，O(1)）。
		// 旧实现为此先无条件编码一次 SNBT 指纹（AEItemKey.toTag 的 Mojang Codec 编码
		// + CompoundTag.toString 的 StringTagVisitor 遍历），而缓冲在正常稳态下恒为空 ——
		// spark BkTP3d9oSc 中这次纯粹为闸门服务的编码占 372ms（0.62%，含 Codec 244ms）。
		// 现在只有 64 种类型全部积压时才编码指纹去精确查重，指纹本身推迟到真正需要
		// 登记剩余物时才算（见下方 leftover 分支的 pendingFingerprint）。
		Ae2PendingItemBuffer pending = holder.getPendingItemBuffer();
		if (!pending.hasFreeEntrySlot()
				&& !pending.canRegister(fingerprintCache.get(key, level.registryAccess()))) {
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
			//
			// 该模拟按 (key, 本 game tick) 记忆化：SIMULATE extract 会穿透网络上每一个
			// 存储元件（omnicell / megacells 大宗盘的压缩链 / neoecoae 无限存储），
			// spark BkTP3d9oSc 中它占 612ms（1.02%）。时间加速模组会在同一个 game tick 内
			// 把 onUpdateServer 调用 N 次，而「同刻反复实时探测同一个键」得到的信息量为零：
			// 本机自己的消耗由 recordExtract 精确扣减，其它设备的消耗在原实现里也同样
			// 只能在两次 extract 之间的窗口里被看见。因此每 game tick 保留一次真实探测、
			// 其余 N-1 次复用扣减后的同刻视图，安全语义与「每 game tick 拉取一次」等价。
			// 另外 reserveProbeAmount 会在「KeyCounter 上报量已 ≥ queryCap」时直接短路：
			// 上报只会偏小（占位/无限存储），上报足够即意味着实时余量必然足够，
			// 概不探测 —— 这正是 spark B0uxYhjEDi 中 reserveProbeAmount→NetworkStorage.extract
			// 这条 100ms 调用链的主要来源。
			long queryCap = SaturatingMath.saturatingAdd(reserveFloor, amount);
			long queryStart = System.nanoTime();
			long liveExtractable;
			try {
				liveExtractable = Ae2NetworkInventoryView.reserveProbeAmount(holder, gameTick,
						cachedInventory, meStorage, key, queryCap, actionSource);
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
				return new PullBatchResult(0, reserveQueryCost,
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
				return new PullBatchResult(0, reserveQueryCost, slowReserveQuery,
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
			return new PullBatchResult(0, maxNetworkCost, slowNetworkOperation,
					slowNetworkOperation, !slowNetworkOperation);
		}
		Ae2NetworkInventoryView.recordExtract(holder, gameTick, meStorage, key, extracted);
		int insertedCount = 0;
		int slotCount = inputSlots.size();
		for (int offset = 0; offset < slotCount && transfer.knownAmount() > 0; offset++) {
			int slotIdx = (slotStart + offset) % slotCount;
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
		if (transfer.knownAmount() > 0) {
			Ae2LeftoverReturner.returnLeftoverToMe(holder, meStorage, key, actionSource, gameTick);
		}
		boolean stranded = transfer.knownAmount() > 0;
		transfer.finish(pending, gameTick, false);
		if (stranded && level.getBlockEntity(pos) != null) level.getBlockEntity(pos).setChanged();
		return new PullBatchResult(insertedCount, maxNetworkCost, slowNetworkOperation, stranded,
				!slowNetworkOperation && !stranded);
	}

	/** 重试已知余量；每轮最多四个身份，发生未知结果立即停止本机重试。 */
	static void retryPendingItems(Level level, Ae2OutputStateHolder holder, MEStorage meStorage,
			List<IInventorySlot> inputSlots, long currentTick) {
		retryPendingItems(level, holder, meStorage, inputSlots, currentTick, null);
	}

	private static boolean acceptsLaneInput(BlockEntity host, int lane, ItemStack input) {
		var required = com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.requiredLaneInput(host, lane);
		return required == null || !required.isEmpty() && ItemStack.isSameItemSameComponents(required, input);
	}

	private static void retryPendingItems(Level level, Ae2OutputStateHolder holder, MEStorage meStorage,
			List<IInventorySlot> inputSlots, long currentTick, BlockEntity host) {
		Ae2PendingItemBuffer pending = holder.getPendingItemBuffer();
		if (pending.isBlocked()) return;
		int attempts = 0;
		for (Ae2PendingItemBuffer.PendingItem entry : pending.snapshot(currentTick)) {
			if (attempts++ >= 4 || pending.isBlocked()) break;
			AEItemKey key = Ae2ItemFingerprint.decode(entry.fingerprint(), level.registryAccess());
			if (key == null) {
				pending.recordFailure(entry.fingerprint(), currentTick);
				LogThrottle.warn("ae2_pending_item_decode", "AE2 pending 指纹无法解析，保留等待迁移: {}", entry.fingerprint());
				continue;
			}
			if (entry.returnToNetworkAmount() > 0) {
				retryKnownAmount(holder, meStorage, inputSlots, entry, key, entry.returnToNetworkAmount(), true, currentTick, host);
			}
			if (entry.amount() > 0 && !pending.isBlocked()) {
				retryKnownAmount(holder, meStorage, inputSlots, entry, key, entry.amount(), false, currentTick, host);
			}
		}
	}

	private static void retryKnownAmount(Ae2OutputStateHolder holder, MEStorage storage,
			List<IInventorySlot> slots, Ae2PendingItemBuffer.PendingItem entry, AEItemKey key,
			long amount, boolean networkOnly, long tick, BlockEntity host) {
		var pending = holder.getPendingItemBuffer();
		var transfer = pending.transfer();
		long request = Math.min(amount, Integer.MAX_VALUE);
		transfer.beginKnown(entry::fingerprint, request);
		if (networkOnly) pending.consumeReturnToNetwork(entry.fingerprint(), request, tick);
		else pending.consume(entry.fingerprint(), request, tick);
		try {
			if (!networkOnly) {
				for (int index = 0; index < slots.size() && transfer.knownAmount() > 0; index++) {
					IInventorySlot slot = slots.get(index);
					if (slot != null && (host == null || acceptsLaneInput(host, index, key.getReadOnlyStack()))) {
						Ae2LeftoverReturner.insertIntoSlot(transfer, key, slot, (int) transfer.knownAmount(), index);
					}
				}
			}
			Ae2LeftoverReturner.returnLeftoverToMe(holder, storage, key, ActionSourceHolder.INSTANCE, tick);
			transfer.finish(pending, tick, networkOnly, entry.retries());
		} catch (LinkageError | RuntimeException error) {
			transfer.fingerprint();
			LogThrottle.error("ae2_input_transfer_unknown",
					"AE2 输入交接待核验；停止自动重试 request={} target={} known={} offered={}: {}",
					transfer.request(), transfer.destination(), transfer.knownAmount(), transfer.offeredAmount(), error.toString());
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
		boolean combBlock;
		/** 等待资源出口探测时只保留公平份额，不提取原料。 */
		boolean resourceDeferred;
		/** Effective AE2 stock floor, or -1 when no reserve policy applies. */
		long reserveFloor;
		long servedInWindow;
		/**
		 * 本轮容量规划中是否被槽位 validator 判为「永不接受」（非「暂时没位置」）。
		 * <p>
		 * 用于给这类键记一次 per-key 退避：它们从不进入 {@code pullBatchForType}，
		 * 因此原先永远不会触发 {@code keyBackoff.recordFailure}，只能全速重复空转探测。
		 */
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

		/**
		 * 解析本条目 key 的组件签名 —— 每轮容量规划只做一次。
		 * <p>
		 * <b>为什么要预解析</b>：容量规划的形状是「类型 × 车道」，同一类型的所有车道共用
		 * 同一个 key。原实现把 {@code probe.getComponentsPatch().size()}、
		 * {@code isConfigurableCombItem}、{@code probe.has(bee_type)} 全部放在内层循环里，
		 * 于是 19 车道 × 38 类型时同一份 key 签名被重复推导 19 次。
		 * <p>
		 * 与 {@code Ae2InputLaneSnapshot} 的车道签名逐条对应：两侧都满足
		 * 「补丁数 1 + 可配置蜜脾/蜜脾块 + 该补丁就是 bee_type」时，组件相等判定可以
		 * 直接退化为 bee_type 比较 —— 这正是「每个蜜蜂格子放不同种类蜜蜂」场景
		 * （全部蜜脾共用同一 Item、仅靠 bee_type 区分）下内层循环的全部成本来源。
		 */
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
			// 快径 2：双方都是「唯一补丁 = bee_type」的可配置蜜脾/蜜脾块，直接比 bee_type 值。
			// 成立条件与原先内联的「补丁数为 1 + isConfigurableCombItem + 双方 has(bee_type)」
			// 逐条等价，只是两侧签名都已在循环外预采（车道侧见 Ae2InputLaneSnapshot#refresh、
			// 条目侧见 #prepareKeyComponents），把每轮数百次组件表查找压成一次数组读 +
			// 一次引用/值比较。这正是「每个蜜蜂格子放不同种类蜜蜂」场景下
			// （全部蜜脾共用同一 Item，仅靠 bee_type 区分，Item 身份剪枝完全失效）的全部成本。
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
