package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.network.Ae2CentrifugeInputReturnService;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
	 * AE2 输入拉取器（与 {@link Ae2OutputPusher} 对称）— 将 AE2 网络中的蜜脾拉取到离心机输入槽。
	 * 调用方需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫。
	 * @since 2.0.0
	 */
public final class Ae2InputPuller {

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

	/** 正式加工与新取料共用出口缓存，满槽和取料冷却不跳过这项检查。 */
	public static ResourceCentrifugeProcessor.OutputAvailability resourceOutputAvailability(
			BlockEntity entity, ResourceCentrifugeRecipe recipe) {
		if (!(entity instanceof IAe2InputHost host) || entity.getLevel() == null) {
			return ResourceCentrifugeProcessor.OutputAvailability.AVAILABLE;
		}
		var holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return ResourceCentrifugeProcessor.OutputAvailability.DEFERRED;
		var buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		var storage = holder.getPushState().getCachedNodeState(host) == Ae2GridNodeManager.STATE_ONLINE
				? Ae2GridNodeManager.getCachedMeStorage(holder, host) : null;
		buffers.resourceInputGate.prepare(host, holder, buffers, storage, entity.getLevel().getGameTime());
		return switch (buffers.resourceInputGate.decision(recipe)) {
			case ACCEPTED -> ResourceCentrifugeProcessor.OutputAvailability.AVAILABLE;
			case REJECTED -> ResourceCentrifugeProcessor.OutputAvailability.UNAVAILABLE;
			case DEFERRED -> ResourceCentrifugeProcessor.OutputAvailability.DEFERRED;
		};
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

		Ae2PushBackoff returnBackoff = holder.getPushState().getReturnBackoff();
		boolean returnCooling = returnBackoff.shouldSkip(System.nanoTime());
		boolean pendingRetriedEarly = false;
		if (pendingRetryable) {
			MEStorage pendingStorage = !returnCooling
					&& holder.getPushState().getCachedNodeState(host) == Ae2GridNodeManager.STATE_ONLINE
					? Ae2GridNodeManager.getCachedMeStorage(holder, host) : null;
			// 已知资产可先落本地槽；网络断线和回送冷却只限制网络交互。
			if (Ae2LeftoverReturner.retryPendingItems(level, holder, pendingStorage, inputSlots, currentTick,
					host instanceof BlockEntity entity ? entity : null, ActionSourceHolder.INSTANCE)) {
				host.productivebeesgenesis$markAe2StateChanged();
			}
			pendingRetriedEarly = true;
		}
		// Pending recovery above is independent from the switch for accepting new inputs.
		if (!inputPullEnabled || holder.getPendingItemBuffer().isBlocked()) return;
		Ae2PushBackoff inputBackoff = holder.getPushState().getInputBackoff();
		if (inputBackoff.shouldSkip(System.nanoTime())) return;
		if (holder.getPushState().getCachedNodeState(host) != Ae2GridNodeManager.STATE_ONLINE) return;

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
			// 满槽没有请求网络；槽位重新腾出容量后仍按原有间隔及时补料。
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
		// 先处理上次抽取后未能落槽或回送 ME 的物品，避免新抽取继续扩大待处理所有权。
		boolean hasRetryablePending = holder.getPendingItemBuffer().hasRetryableItems(currentTick);
		if (hasRetryablePending && !pendingRetriedEarly) {
			if (Ae2LeftoverReturner.retryPendingItems(level, holder, meStorage, inputSlots, currentTick,
					host instanceof BlockEntity entity ? entity : null, ActionSourceHolder.INSTANCE)) {
				host.productivebeesgenesis$markAe2StateChanged();
			}
		}
		if (holder.getPendingItemBuffer().isBlocked()) return;

		// 先取得许可再扫描候选与规划车道；等待不是一次拉取尝试，不推进原有冷却。
		if (!holder.tryAcquireNetworkExtract(meStorage, currentTick)) return;

		// 9. 获取复用缓冲与调度状态；同样延后到真正需要扫描网络库存时。
		Ae2PushBuffers buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff = getOrCreateKeyBackoff(holder);
		Ae2InputFairnessScheduler fairness = getOrCreateFairnessScheduler(holder);
		fairness.roll(currentTick);
		List<Ae2InputExecution.PullEntry> pullList = Ae2InputCandidatePolicy.collect(
				host, holder, buffers, level, storageService, meStorage, currentTick, inputCapacity,
				processCount, filter, unlimitedMode, keyBackoff, ActionSourceHolder.INSTANCE);
		if (pullList.isEmpty()) {
			// 候选可能都在等待库存保留线，未执行提取的扫描不累计失败退避。
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
			return;
		}
		holder.setInputCandidateCursor(pullList.getLast().key);
		Ae2InputExecution.Result result = Ae2InputExecution.execute(
				host, holder, buffers, pullList, inputSlots, meStorage, keyBackoff, fairness,
				inputCapacity, normalQuota, perSlotQuota, ActionSourceHolder.INSTANCE);
		long totalPulled = result.totalPulled();
		boolean unlimitedSucceeded = result.unlimitedSucceeded();
		boolean unlimitedAttempted = result.unlimitedAttempted();
		boolean networkWorkDeferred = result.networkWorkDeferred();

		// 16. 更新上次拉取游戏刻（无论是否拉取成功，只要触发过就更新，避免下一 tick 重复扫描）
		//     Spark 优化：直接使用 holder 替代 host 接口分发
		// 16. Update the adaptive cooldown: success shortens the next interval
		//     (1 tick unlimited / 5 normal), failure backs off (AE2LT parity).
		if (totalPulled > 0) {
			holder.onInputPullSuccess(unlimitedSucceeded, totalPulled, normalQuota);
		} else if (!networkWorkDeferred) {
			holder.onInputPullFail(unlimitedAttempted);
		}
		if (totalPulled > 0 || !networkWorkDeferred) {
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
		}

		// 拉取列表已使用完毕，clear 而非新建（复用 ReusableBuffers）
		pullList.clear();
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
}
