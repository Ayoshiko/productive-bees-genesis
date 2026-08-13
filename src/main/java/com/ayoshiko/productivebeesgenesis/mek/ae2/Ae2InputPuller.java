package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
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
import java.util.concurrent.atomic.AtomicLong;

/**
	 * AE2 输入拉取器（与 {@link Ae2OutputPusher} 对称）— 将 AE2 网络中的蜜脾拉取到离心机输入槽。
	 * 调用方需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫。
	 * @since 2.0.0
	 */
public final class Ae2InputPuller {

	/** 异常日志计数器 — 用于在日志中显示累计出现次数（LogThrottle 已负责节流） */
	private static final AtomicLong PULL_EXCEPTION_COUNTER = new AtomicLong(0);

	/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

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

		// 3. 网格节点 + 已连接网格检查（Task 12：holder 感知重载，跳过冗余 getAe2StateHolder）
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return;

		// 4. 存储服务和 ME 存储检查（Task 12：holder 感知重载，避免重复 getService/getInventory）
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;

		// 5. Level null 守卫（已在 1.5 节获取，复用避免重复调用）
		if (level == null) return;
		long currentTick = level.getGameTime();
		// Task 2：获取 BlockPos 用于 returnLeftoverToMe 兜底 popResource
		BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();

		// 6. 加速倍率检测 — multiplier 已在调用方 onUpdateServer 入口处通过 tracker.onTick(level) 更新
		//    直接使用 holder 替代 host 接口分发
		TickAccelTracker tracker = holder.getTickAccelTracker();
		int M = (tracker != null)
				? Math.max(tracker.getMultiplier(), tracker.getPreviousTickMultiplier())
				: 1;

		// 7. AE2LT-style adaptive cooldown (success: 1 tick unlimited / 5 normal, failures back off).
		//    Driven by the pull call counter so acceleration mods that invoke multiple
		//    ticks per game tick still converge; unlimited entries ignore the configured interval.
		long pullCounter = holder.incrementPullCallCounter();
		int cooldownTicks = holder.getInputPullCooldownTicks();
		if (pullCounter - holder.getLastPullCounter() < cooldownTicks) return;

		// 8. 获取复用缓冲区（与推送共享 ReusableBuffers，避免每 tick 创建临时对象）
		//    holder 感知重载，跳过冗余 getAe2StateHolder
		Ae2OutputPusher.ReusableBuffers buffers = Ae2OutputPusher.getReusableBuffers(holder, host);

		// 9. 获取输入槽列表
		List<IInventorySlot> inputSlots = host.productivebeesgenesis$getInputSlotsForPull();
		if (inputSlots == null || inputSlots.isEmpty()) return;
		int processCount = inputSlots.size();

		// 10. Unlimited entries bypass the rate budget entirely (AE2LT overloaded
		//     interface semantics): pull as much as the input slots can hold,
		//     ignoring both the configured interval and per-tick quantity.
		Ae2InputFilter filter = holder.getOrCreateInputFilter();
		boolean unlimitedMode = filter != null && filter.hasUnlimitedEntries();
		long inputCapacity = calculateInputCapacity(inputSlots);
		if (inputCapacity <= 0) {
			// Input slots are full; treat as a failed attempt so the cooldown backs off.
			holder.onInputPullFail(unlimitedMode);
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
			return;
		}
		long remainingQuota;
		long perSlotQuota;
		if (unlimitedMode) {
			remainingQuota = inputCapacity;
			perSlotQuota = Long.MAX_VALUE;
		} else {
			long baseRate = holder.getCachedInputRatePerTick();
			double tpsFactor = ServerTickTimeMonitor.getInstance().getTpsFactor(currentTick);
			perSlotQuota = Ae2PullFairnessPolicy.perSlotQuota(baseRate, M, processCount);
			long baseProduct = SaturatingMath.saturatingMultiply(perSlotQuota, processCount);
			long effectiveRate = Math.max(1L, (long) (baseProduct * tpsFactor));
			remainingQuota = Math.min(effectiveRate, inputCapacity);
		}
		if (remainingQuota <= 0) return;

		// 13. 遍历 MEStorage 可用栈，收集待拉取类型（不消耗 quota，由执行阶段按 round-robin 分配）
		//     V13 修复：收集所有可用类型，单类型走原版顺序填充，多类型走 round-robin 跨进程分发
		List<PullEntry> pullList = buffers.borrowPullList();
		pullList.clear(); // 清空上一 tick 残留数据
		int maxTypesToCollect = Math.max(1, processCount * 2); // 上限避免海量类型拖慢分发
		AEItemKey candidateCursor = holder.getInputCandidateCursor() instanceof AEItemKey key ? key : null;
		boolean afterCursor = candidateCursor == null;
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
				&& !holder.isAeInputNbtIgnore();
		if (directOnly) {
			// Network-stock whitelist entries use exact keys and avoid a full inventory scan.
			for (Ae2InputFilter.DirectEntry direct : directEntries) {
				if (pullList.size() >= maxTypesToCollect) break;
				AEItemKey key = direct.key();
				if (key == null || containsPullKey(pullList, key)
						|| !CombFuzzyMatcher.isCombItem(key)) continue;
				long available = Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
						availableStacks, meStorage, key, Long.MAX_VALUE, ACTION_SOURCE);
				long configuredLimit = filter.getDirectPullLimit(key, available, holder.isAeInputNbtIgnore());
				if (!unlimitedMode && configuredLimit >= 0L) {
					available = Math.min(available, configuredLimit);
				}
				if (available > 0) pullList.add(new PullEntry(key, SaturatingMath.saturatingToInt(available)));
			}
		} else {
			// Mixed whitelists still honor network-stock entries that are extractable but absent from AE's cache.
			if (filter != null && filter.getFilterMode() == Ae2InputFilter.FilterMode.WHITELIST) {
				for (Ae2InputFilter.DirectEntry direct : directEntries) {
					if (pullList.size() >= maxTypesToCollect) break;
					AEItemKey key = direct.key();
					if (!direct.networkStock() || key == null || containsPullKey(pullList, key)
							|| !CombFuzzyMatcher.isCombItem(key)) continue;
					long available = Ae2NetworkInventoryView.visibleAmount(holder, currentTick,
							availableStacks, meStorage, key, Long.MAX_VALUE, ACTION_SOURCE);
					long configuredLimit = filter.getDirectPullLimit(key, available, holder.isAeInputNbtIgnore());
					if (!unlimitedMode && configuredLimit >= 0L) {
						available = Math.min(available, configuredLimit);
					}
					if (available > 0) {
						pullList.add(new PullEntry(key, SaturatingMath.saturatingToInt(available)));
					}
				}
			}
			// 构建键序列并执行游标回绕扫描（纯逻辑抽离为 collectCursorScan，便于单测回归验证）。
			// KeyCounter.keySet() 返回同一快照内的稳定引用序，游标定位与回绕语义一致。
			List<AEItemKey> scanKeys = buffers.borrowScanKeys();
			scanKeys.clear();
			for (AEKey scanKey : availableStacks.keySet()) {
				if (scanKey instanceof AEItemKey itemKey) scanKeys.add(itemKey);
			}
			List<AEItemKey> selectedKeys = buffers.borrowScanSelectedKeys();
			selectedKeys.clear();
			boolean ignoreNbt = holder.isAeInputNbtIgnore();
			// 总收集上限与旧实现一致：whitelist 预置条目也计入 maxTypesToCollect
			int scanCap = Math.max(0, maxTypesToCollect - pullList.size());
			Ae2CursorScan.collect(selectedKeys, scanKeys, candidateCursor, scanCap,
					key -> !containsPullKey(pullList, key)
							&& createPullCandidate(key, availableStacks.get(key), filter, ignoreNbt, unlimitedMode) != null);
			for (AEItemKey key : selectedKeys) {
				pullList.add(createPullCandidate(key, availableStacks.get(key), filter, ignoreNbt, unlimitedMode));
			}
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
		for (PullEntry entry : pullList) {
			entry.marked = filter != null && filter.matchesAnyEntry(entry.key, sortIgnoreNbt);
		}
		pullList.sort((a, b) -> {
			if (a.marked != b.marked) return Boolean.compare(b.marked, a.marked);
			boolean aBlock = CombFuzzyMatcher.isCombBlock(a.key);
			boolean bBlock = CombFuzzyMatcher.isCombBlock(b.key);
			return Boolean.compare(bBlock, aBlock); // true (block) first
		});

		// 15. 槽位和类型双轮转。单类型也逐槽限额，不再从 slot 0 一次灌满。
		long totalPulled = 0;
		int typeCount = pullList.size();
		int typeStart = holder.getAndIncrementTypeRotation(1, typeCount);
		int slotStart = holder.getPushState().getAndAdvanceInputSlotRotation(processCount);
		for (int slotOffset = 0; slotOffset < processCount && totalPulled < remainingQuota; slotOffset++) {
			int slotIdx = (slotStart + slotOffset) % processCount;
			IInventorySlot slot = inputSlots.get(slotIdx);
			if (slot == null) continue;

			for (int typeOffset = 0; typeOffset < typeCount; typeOffset++) {
				PullEntry entry = pullList.get((typeStart + slotOffset + typeOffset) % typeCount);
				if (entry.remaining <= 0) continue;
				long slotRemaining = getSlotRemainingCapacity(slot, entry.key);
				if (slotRemaining <= 0) continue;
				int toPull = SaturatingMath.saturatingToInt(Math.min(
						Math.min(perSlotQuota, slotRemaining),
						Math.min(entry.remaining, remainingQuota - totalPulled)));
				if (toPull <= 0) break;
				try {
					int pulled = pullAndInsert(level, entry.key, toPull, meStorage,
							ACTION_SOURCE, inputSlots, slotIdx, pos, returnBackoff);
					entry.remaining -= pulled;
					totalPulled += pulled;
					if (pulled > 0) break;
				} catch (LinkageError | RuntimeException e) {
					handlePullException(e, entry.key);
				}
			}
		}

		// 16. 更新上次拉取游戏刻（无论是否拉取成功，只要触发过就更新，避免下一 tick 重复扫描）
		//     Spark 优化：直接使用 holder 替代 host 接口分发
		// 16. Update the adaptive cooldown: success shortens the next interval
		//     (1 tick unlimited / 5 normal), failure backs off (AE2LT parity).
		if (totalPulled > 0) {
			holder.onInputPullSuccess(unlimitedMode, totalPulled, remainingQuota);
		} else {
			holder.onInputPullFail(unlimitedMode);
		}
		holder.updateLastPullTick(currentTick);
		holder.updateLastPullCounter(pullCounter);

		// 拉取列表已使用完毕，clear 而非新建（复用 ReusableBuffers）
		pullList.clear();
	}

	private static PullEntry createPullCandidate(Object rawKey, long available, Ae2InputFilter filter,
		boolean ignoreNbt, boolean unlimitedMode) {
		if (available <= 0 || !(rawKey instanceof AEItemKey itemKey)) return null;
		if (!CombFuzzyMatcher.isCombItem(itemKey)) return null;
		boolean allowed = filter == null || filter.isAllowed(itemKey, ignoreNbt);
		if (!allowed) {
			return null;
		}
		if (filter != null && !unlimitedMode) {
			long configuredLimit = filter.getDirectPullLimit(itemKey, available, ignoreNbt);
			if (configuredLimit >= 0L) available = Math.min(available, configuredLimit);
		}
		if (available <= 0L) return null;
		return new PullEntry(itemKey, SaturatingMath.saturatingToInt(available));
	}

	private static boolean containsPullKey(List<PullEntry> entries, AEItemKey key) {
		for (PullEntry entry : entries) {
			if (entry.key.equals(key)) return true;
		}
		return false;
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
					total += slot.getLimit(ItemStack.EMPTY);
				} catch (RuntimeException e) {
					// getLimit 异常时跳过该槽位（节流日志便于排查自定义槽实现缺陷）
					LogThrottle.warn("ae2_input_capacity_empty",
							"AE2 输入槽容量计算异常 (空槽), 跳过该槽位: {}", e.toString());
				}
			} else {
				try {
					int limit = slot.getLimit(stack);
					long remaining = (long) limit - stack.getCount();
					if (remaining > 0) total += remaining;
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
	private static long getSlotRemainingCapacity(IInventorySlot slot, AEItemKey key) {
		if (slot == null || key == null) return 0;
		ItemStack stack = slot.getStack();
		try {
			if (stack.isEmpty()) {
				return slot.getLimit(ItemStack.EMPTY);
			}
			if (!key.matches(stack)) return 0;
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
	 * 执行单个 key 的拉取并按指定策略插入输入槽。
	 * <br/>
	 * 从 ME 网络提取物品，按 {@code targetSlotIndex} 决定插入策略：
	 * -1 顺序填充；&gt;=0 先尝试指定 slot，失败回退到顺序填充其他兼容槽。剩余物品回送 ME 网络。
	 *
	 * @param level           当前世界
	 * @param key             AE2 物品键
	 * @param amount          拉取数量
	 * @param meStorage       ME 存储
	 * @param actionSource    AE2 操作源
	 * @param inputSlots      输入槽列表
	 * @param targetSlotIndex 目标槽索引（-1 顺序填充；&gt;=0 指定 slot 优先）
	 * @param pos             方块位置（用于 returnLeftoverToMe 兜底 popResource）
	 * @param returnBackoff   回送退避状态（Task 10，可为 null）
	 * @return 实际插入输入槽的物品总数
	 */
	private static int pullAndInsert(Level level, AEItemKey key, int amount,
			MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, int targetSlotIndex, BlockPos pos, Ae2PushBackoff returnBackoff) {
		long extracted = meStorage.extract(key, amount, Actionable.MODULATE, actionSource);
		if (extracted <= 0) {
			return 0;
		}

		ItemStack stack = key.toStack(SaturatingMath.saturatingToInt(extracted));
		int originalCount = stack.getCount();

		if (targetSlotIndex < 0) {
			// 顺序填充：slot 0 先满再溢出到下一个槽（原版行为，单类型场景）
			for (IInventorySlot slot : inputSlots) {
				if (slot == null) continue;
				if (stack.isEmpty()) break;
				ItemStack remainder = slot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				stack = remainder;
			}
		} else if (targetSlotIndex < inputSlots.size()) {
			// round-robin：先尝试指定 slot，失败回退到顺序填充其他兼容槽
			// 修复根因 B：输出槽不兼容时 MEK insertItem 拒绝插入并返回整个 stack，顺序尝试其他槽
			IInventorySlot targetSlot = inputSlots.get(targetSlotIndex);
			if (targetSlot != null) {
				stack = targetSlot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
			}
			for (int offset = 1; !stack.isEmpty() && offset < inputSlots.size(); offset++) {
				int idx = (targetSlotIndex + offset) % inputSlots.size();
				if (inputSlots.get(idx) != null) {
					stack = inputSlots.get(idx).insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				}
			}
		}

		int insertedCount = originalCount - (stack.isEmpty() ? 0 : stack.getCount());

		// Task 2 修复：剩余物品回送 ME 网络，使用 poweredInsert + 三层兜底保证物品不丢失
		// 根因：原 meStorage.insert 在 ME 网络状态变化（storage 已满/cell 移除/网络断开）时失败即丢弃物品。
		// 修复：poweredInsert 重试 + 回插输入槽 + popResource 三层兜底，绝不丢失物品。
		int leftoverRemaining = stack.isEmpty() ? 0 : stack.getCount();
		if (!stack.isEmpty()) {
			// M4-2 修复：returnLeftoverToMe 返回剩余未回送数量，> 0 表示有物品丢失风险
			// 这里 stack 是从 ME 拉取的局部变量，无源槽位可保留，仅记录 ERROR 等待人工排查
			leftoverRemaining = Ae2LeftoverReturner.returnLeftoverToMe(meStorage, key, stack, actionSource, returnBackoff,
					level, pos, inputSlots);
			if (leftoverRemaining > 0) {
				// M9: LogThrottle.error 节流，5 秒内同 key 仅首条，避免 256× 加速刷屏
				LogThrottle.error("ae2_pull_leftover_loss",
						"AE2 拉取剩余物品回送失败，存在丢失风险 (5秒内仅首条输出) key={}", key);
			}
		}
		return insertedCount;
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
	 * 拉取条目 — 缓存扫描结果，包级可见供 {@link Ae2OutputPusher.ReusableBuffers#pullList} 复用。
	 */
	static final class PullEntry {
		final AEItemKey key;
		int remaining;
		/** Pre-computed "matches a configured entry" flag used by the marked-first sort. */
		boolean marked;

		PullEntry(AEItemKey key, int amount) {
			this.key = key;
			this.remaining = amount;
		}
	}
}
