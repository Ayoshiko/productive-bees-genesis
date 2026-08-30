package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * AE2 输出推送编排器 — 只负责守卫、扫描与路径选择，具体策略已拆分到同包协作类
 * <p>
 * <b>协作分工</b>（原单文件 1102 行，按 SRP 拆分）：
 * <ul>
 *   <li>{@link Ae2PushLimits}：共享阈值与 {@code IActionSource} 懒加载 Holder</li>
 *   <li>{@link Ae2PushBuffers}：跨 tick 复用缓冲区与对象池</li>
 *   <li>{@link Ae2SlotEntry} / {@link Ae2PullCandidateAmounts}：数据载体</li>
 *   <li>{@link Ae2OutputCommitter}：读槽、账本结算与实际 ME 提交</li>
 *   <li>{@link Ae2OutputSlotPass} / {@link Ae2OutputMergedPass}：逐槽与合并两条提交策略</li>
 *   <li>{@link Ae2DirectItemPushSession}：产物/缓冲直推会话</li>
 *   <li>{@link Ae2OutputBackoffLog} / {@link Ae2PushExceptionLog}：退避记账与异常日志</li>
 * </ul>
 * <p>
 * <b>推送流程</b>：账本结算 → 集成/开关检查 → 同刻去重 → 退避 → 空输出短路 → 节点 ONLINE →
 * 解析网格/存储 → 轮转扫描输出槽 → 按槽位数选择逐槽或合并路径。
 * <p>
 * <b>容错策略</b>：只按 AE 实际接收量扣除；失败时物品留在原槽并进入短退避。
 * <p>
 * <b>性能钳制</b>：同 key 批量合并、空输出短路、AEItemKey 缓存、缓冲区跨 tick 复用，
 * 以及 insert 四层耗时钳制 —— 单机时间预算 + 慢 insert 联动指数退避 +
 * {@link Ae2GlobalInsertBudget} 全服预算 + {@link Ae2InsertCostTracker} 自适应键配额。
 * 前三层只统计慢 insert（&gt;0.5ms）的超出耗时，第四层用 EWMA 覆盖「中等昂贵 + 极高频」外部存储；
 * 健康网络下四层全部不生效，推送吞吐无任何损失。
 */
public final class Ae2OutputPusher {

	private Ae2OutputPusher() {}

	/**
	 * 推送宿主所有输出槽的物品到 AE2 网络
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。输出槽全空时通过 {@code hasOutputItems()} 短路。
	 *
	 * @param host 输出宿主（离心机/蜂箱方块实体）
	 */
	public static void pushOutputs(IAe2OutputHostBase host) {
		// Spark 优化：缓存 holder 和 pushState，消除后续 9 次冗余 getAe2StateHolder() 接口分发
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		int settled = Ae2OutputCommitter.settleOutputLedger(host, holder.getOutputLedger(),
				level.registryAccess());
		if (settled > 0) {
			host.productivebeesgenesis$onAe2PushComplete(settled);
			host.productivebeesgenesis$markAe2StateChanged();
		}

		// 新提交可被开关和退避阻止，但已提交账本必须优先结算，避免关闭输出后复制窗口悬挂。
		if (!host.productivebeesgenesis$isOutputPushEnabled()
				|| !host.productivebeesgenesis$isAeItemOutputEnabled()) return;
		Ae2PushStateHolder pushState = holder.getPushState();

		// 同 gameTick 去重 — JDTE/JDT 加速器在同一真实 tick 内多次调用时仅首次执行完整推送。
		long gameTick = level.getGameTime();
		if (!pushState.tryStartItemPush(gameTick)) return;

		// 退避检查 — 使用缓存的 pushState（消除 2 次冗余 getAe2StateHolder）
		long pushCounter = pushState.incrementItemPushCallCounter();
		Ae2PushBackoff itemBackoff = pushState.getItemBackoff();
		long nowNanos = System.nanoTime();
		if (itemBackoff.shouldSkip(nowNanos)) return;
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff = getOrCreateOutputKeyBackoff(holder);

		// 同一游戏刻的重复调用已由 tryStartItemPush 合并；工厂每刻仅调用一次也不会被 M 误节流。
		pushState.updateLastItemPushCounter(pushCounter);

		// 空输出短路：标志位由 OutputSlotFlagManager/MekCentrifugeSlotManager O(1) 维护，
		// AE2 推送清空槽位后 onAe2PushComplete 已调用 updateOutputSlotFlags() 保证同步
		if (!host.productivebeesgenesis$hasOutputItems()) return;

		// 强检测 grid node 状态，仅当 ONLINE(3) 时继续推送。
		// 状态 0/1/2: OFFLINE/NETWORK_BOOTING/MISSING_CHANNEL — 不进入 insert 路径，不触发退避。
		// 使用 pushState 缓存（20 tick 刷新一次）避免每 tick 高频调用 getGridNodeState
		if (pushState.getCachedNodeState(host) != Ae2GridNodeManager.STATE_ONLINE) return;

		// 获取已连接的网格与 ME 存储（holder 感知重载，跳过冗余 getAe2StateHolder）
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return;
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;

		Ae2PushBuffers buffers = getReusableBuffers(holder, host);

		// AEItemKey 缓存（Task 7：减少 AEItemKey.of(stack) 重复调用）
		Object cacheObj = host.productivebeesgenesis$getAeItemKeyCache();
		AeItemKeyCache keyCache = cacheObj instanceof AeItemKeyCache cache ? cache : null;

		// 第一遍扫描：轮转起点开始收集所有非空槽位（复用 ArrayList，clear 而非新建）
		int processes = host.processes();
		List<Ae2SlotEntry> entries = buffers.entries;
		entries.clear();
		buffers.entryPoolCursor = 0;
		int flatSlotCount = Math.max(0, processes) * AeItemKeyCache.SLOTS_PER_PROCESS;
		int scanStart = RoundRobinSlotTraversal.normalize(buffers.outputSlotScanCursor, flatSlotCount);
		buffers.outputSlotScanCursor = RoundRobinSlotTraversal.advance(scanStart, flatSlotCount);
		for (int offset = 0; offset < flatSlotCount; offset++) {
			int flatIndex = RoundRobinSlotTraversal.index(scanStart, offset, flatSlotCount);
			int process = flatIndex / AeItemKeyCache.SLOTS_PER_PROCESS;
			int slotIdx = flatIndex % AeItemKeyCache.SLOTS_PER_PROCESS;
			Ae2OutputCommitter.collectSlot(buffers, process, slotIdx,
					Ae2OutputCommitter.outputSlot(host, process, slotIdx), keyCache,
					level.registryAccess());
		}
		// 账本中已预留的槽位本轮不得再次提交，否则会重复推送同一批物品
		entries.removeIf(entry -> holder.getOutputLedger().hasSlot(entry.process, entry.slotIdx));
		if (entries.isEmpty()) return;

		Ae2OutputPushContext ctx = new Ae2OutputPushContext(host, holder, buffers, meStorage,
				keyBackoff, itemBackoff, gameTick, nowNanos, scanStart, flatSlotCount);
		if (Ae2OutputMergePolicy.shouldMergeEntries(entries.size())) {
			Ae2OutputMergedPass.run(ctx, entries);
		} else {
			// 少量槽位时直接逐槽推送，避免建 Map 的开销
			Ae2OutputSlotPass.run(ctx, entries);
		}
	}

	/**
	 * 推送单个物品栈到 AE2 网络（蜂箱输出缓冲区直推用）
	 * <br/>
	 * 复用 {@link #prepareDirectItemPush} 的开关、节点和存储守卫；失败时由调用方保留原栈。
	 *
	 * @param host  输出宿主
	 * @param stack 待推送的物品栈（不修改原栈，返回实际接收数量由调用方扣除）
	 * @return 实际推送数量；0 表示未推送或完全失败
	 */
	public static int pushItemStack(IAe2OutputHostBase host, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		Ae2DirectItemPushSession session = prepareDirectItemPush(host);
		return session == null ? 0 : session.applyAsInt(stack);
	}

	/** Resolves the AE target once so a bounded buffer drain does not repeat host/grid lookups per group. */
	@Nullable
	public static Ae2DirectItemPushSession prepareDirectItemPush(IAe2OutputHostBase host) {
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
		Ae2PushBuffers buffers = getReusableBuffers(holder, host);
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff = getOrCreateOutputKeyBackoff(holder);
		if (buffers.directItemPushSession == null) {
			buffers.directItemPushSession = new Ae2DirectItemPushSession(meStorage, keyBackoff, gameTick,
					buffers.insertCostTracker);
		} else {
			buffers.directItemPushSession.reset(meStorage, keyBackoff, gameTick, buffers.insertCostTracker);
		}
		return buffers.directItemPushSession;
	}

	@SuppressWarnings("unchecked")
	private static Ae2KeyBackoffRegistry<AEItemKey> getOrCreateOutputKeyBackoff(Ae2OutputStateHolder holder) {
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
	 * 包级可见：供 {@link Ae2FluidPusher} 复用能量适配器与 insert 成本记账器，
	 * 以及 {@link Ae2InputPuller} 复用扫描缓冲，避免每 tick 创建临时对象。
	 */
	static Ae2PushBuffers getReusableBuffers(IAe2OutputHostBase host) {
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
	static Ae2PushBuffers getReusableBuffers(Ae2OutputStateHolder holder, IAe2OutputHostBase host) {
		Object obj = holder.getReusableBuffers();
		if (obj instanceof Ae2PushBuffers buffers) return buffers;
		Ae2PushBuffers buffers = new Ae2PushBuffers();
		holder.setReusableBuffers(buffers);
		return buffers;
	}
}
