package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Classifies AE2 item keys that may enter a centrifuge through network pulling.
 * <p>
 * Productive Bees comb semantics remain owned by {@link CombFuzzyMatcher}; ordinary
 * Mekanism SMELTING inputs are admitted only while the centrifuge compatibility mode
 * is enabled. Comb classification deliberately runs first so broad SMELTING tag recipes
 * such as {@code c:honeycombs} cannot steal Productive Bees inputs.
 * <p>
 * 标签门同时约束两类候选，避免蜜脾或蜜脾块分支绕过白名单/黑名单；
 * 具体的“是否标记”仍由 {@link Ae2InputFilter} 的槽位白/黑名单负责。
 * <p>
 * 蜜脾候选另经 {@link CombProcessGate}（本机可处理性）：整合包里大量蜜脾只有蜂、
 * 没有 PB 离心配方（需其它模组机器处理），它们靠 Item 引用判定仍是「蜜脾」，
 * 若放进候选就会每轮反复拉取/探测却永远插不进槽，形成「卡但 TPS 不掉」的卡顿。
 */
final class Ae2InputCandidatePolicy {

	/**
	 * 候选物品的标签准入抽象（DIP + ISP）。
	 * <br/>
	 * 只暴露一个判定方法，使分类逻辑不依赖具体的标签缓存实现，便于单测替换。
	 */
	@FunctionalInterface
	interface SmeltingTagGate {

		/** 全部放行的门（未配置标签过滤时使用，零开销）。 */
		SmeltingTagGate ALLOW_ALL = key -> true;

		boolean allows(AEItemKey key);
	}

	/**
	 * 蜜脾候选的「本机可处理性」准入抽象（DIP + ISP）。
	 * <br/>
	 * 与 {@link SmeltingTagGate} 分离：标签门是玩家配置（可收窄 AE2 输入候选），
	 * 本门是机器能力（本机有无对应离心配方），两者语义与生命周期都不同。
	 */
	@FunctionalInterface
	interface CombProcessGate {

		/** 全部放行的门（无宿主上下文时使用，保持旧行为）。 */
		CombProcessGate ALLOW_ALL = key -> true;

		boolean canProcess(AEItemKey key);
	}

	enum CandidateKind {
		REJECTED,
		COMB,
		SMELTING;

		boolean isAllowed() {
			return this != REJECTED;
		}

		boolean isSmelting() {
			return this == SMELTING;
		}
	}

	private Ae2InputCandidatePolicy() {
	}

	/**
	 * 分类候选键。判定顺序刻意保持不变：先蜜脾能力、再 SMELTING 配方、最后标签门。
	 * 标签门在两类候选上统一执行，避免蜜脾分支绕过白名单/黑名单。
	 * <p>
	 * 蜜脾被 {@link CombProcessGate} 拒绝时<b>直接 REJECTED，绝不下落 SMELTING 分支</b>：
	 * 整合包（modularbees 等）会为 {@code c:honeycombs} 注册熔炼配方，一旦下落，
	 * 本机无离心配方的蜜脾会以 SMELTING 身份重新被拉进来并产出错误结果，
	 * 正是历史上已修过的「c:honeycombs 抢占 PB 输入」缺陷。
	 * <p>
	 * <b>返回值必须被调用方保留</b>：分类结果只由候选身份决定，与后续排序、配额分配无关。
	 * 拉取器把它写入 {@code PullEntry.smelting} 随条目传递，绝不在排序阶段重新调用本方法 ——
	 * 那会让每次拉取额外穿过 typeCount 次 SMELTING 配方缓存与标签缓存
	 * （spark BkTP3d9oSc 中整条分类链路占服务端主线程 784ms / 1.31%）。
	 */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache, SmeltingTagGate tagGate, CombProcessGate combGate) {
		if (key == null) return CandidateKind.REJECTED;
		boolean comb = CombFuzzyMatcher.isCombItem(key);
		if (comb) {
			// 本机无法加工的蜜脾（只有蜂、无离心配方，需其它模组机器）必须在此终止：
			// 否则它会占满候选类型窗口并每轮空转探测槽位，永远无法插入。
			if (combGate != null && !combGate.canProcess(key)) return CandidateKind.REJECTED;
		} else {
			if (!smeltingEnabled || level == null || smeltingCache == null) return CandidateKind.REJECTED;
			if (!smeltingCache.contains(level, key)) return CandidateKind.REJECTED;
		}
		if (tagGate != null && !tagGate.allows(key)) return CandidateKind.REJECTED;
		return comb ? CandidateKind.COMB : CandidateKind.SMELTING;
	}
	/** 三条候选路径共用本机分类、过滤额度与资源出口；精确提交由执行轮负责。 */
	static List<Ae2InputExecution.PullEntry> collect(IAe2InputHost host, Ae2OutputStateHolder holder,
			Ae2PushBuffers buffers, Level level, IStorageService storageService, MEStorage meStorage,
			long currentTick, long inputCapacity, int processCount, Ae2InputFilter filter,
			boolean unlimitedMode, Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, IActionSource actionSource) {
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

		// 10. 遍历 MEStorage 可用栈，收集待拉取类型（不消耗 quota，由执行阶段按 round-robin 分配）
		//     V13 修复：收集所有可用类型，单类型走原版顺序填充，多类型走 round-robin 跨进程分发
		List<Ae2InputExecution.PullEntry> pullList = buffers.borrowPullList();
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
		if (!directOnly || stockPolicyActive) {
			availableStacks = Ae2NetworkCandidateDirectory.readInventory(
					meStorage, storageService, currentTick, stockPolicyActive);
		}
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
								availableStacks, meStorage, key, Long.MAX_VALUE, actionSource)
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
					Ae2InputExecution.PullEntry entry = buffers.borrowPullEntry(key,
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
									availableStacks, meStorage, key, Long.MAX_VALUE, actionSource)
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
						Ae2InputExecution.PullEntry entry = buffers.borrowPullEntry(key,
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
					Ae2InputExecution.PullEntry entry = buffers.borrowPullEntry(key, amount);
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

		return pullList;
	}
}
