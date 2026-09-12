package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
	 * AE2 流体输出推送器(Bug 7)
	 * <br/>
	 * 将宿主的流体罐内容推送到 AE2 网络,与 {@link Ae2OutputPusher} 物品推送并行工作。
	 * <p>
	 * <b>待推送缓冲集成</b>:引入 {@link Ae2PendingBatchBuffer} 按 AEFluidKey 合并本刻槽内库存,
	 * 同一真实游戏刻内的加速子 tick 只采样一次、只发起一次 insert（每 key 一次），
	 * 且一次 insert 就带走槽内该流体的全部数量。
	 * <p>
	 * <b>Task 13 多槽推送策略</b>:
	 * <ul>
	 *   <li><b>SINGLE 模式</b>(默认):{@code host.fluidOutputTankCount()} 返回 1,
	 *       遍历单槽,行为与修改前完全一致</li>
	 *   <li><b>MULTI_PER_FLUID 模式</b>:遍历所有已分配槽位(上限 tier.processes),
	 *       每个非空槽独立推送。能量适配器和操作源全局共享,避免每槽重复创建</li>
	 * </ul>
	 * <p>
	 * <b>spec fix-ae2-push-backoff-and-jdte-adapt 自适应机制</b>:
 * <ul>
 *   <li><b>卡顿保护</b>:由 {@link Ae2GlobalInsertBudget} 全服慢 insert 预算承担 —
 *       只钳制病态网络（如 EnderDrives fsync）的慢 insert,健康网络满速推送不受限
 *       （原 TPS 自适应跳过设计因伤害产出效率已移除）</li>
 *   <li><b>网格节点状态检查</b>:仅 ONLINE(3) 时继续推送,与 {@link Ae2OutputPusher} 对称,
	 *       非 ONLINE 状态直接返回不触发退避(修复: 原缺少此检查导致网格不稳定时 poweredInsert 必然失败)</li>
	 *   <li><b>退避</b>:仅所有流体 key 都失败时进入 50ms→1s 的短指数退避,
	 *       基于 {@link System#nanoTime()} 墙钟单调时钟,窗口内跳过所有 AE2 存储操作,
	 *       避免 JDTE 加速下 counter 退避失效(Task 5)。
	 *       部分成功不重置退避(避免"部分成功→重置→立即失败→激进退避"死循环)</li>
	 *   <li><b>直推配额与退避</b>:{@link #pushGeneratedFluid} 与物品直推路径同构 —
	 *       每真实游戏刻配额 + 按流体键退避,避免加速子 tick 下无上限地发起注定失败的
	 *       全量网络遍历(见 {@link #MAX_DIRECT_FLUID_INSERTS_PER_TICK})</li>
	 *   <li><b>固定分批</b>:单次 MEStorage 请求最多 {@link #MAX_FLUID_BATCH_REQUEST_MB}，
	 *       一轮刷新把槽内流体推空</li>
	 *   <li><b>有就推送 + 窗口重试</b>:出现新增流体即在本真实游戏刻推送（延迟 0）；
	 *       没有新增但窗口到期（{@link Ae2PendingBatchBuffer#RIPE_TICKS} 刻）时重试上一批 ——
	 *       等价于 useless {@code PendingAEBatch} 的「同 key 快路径 + 成熟窗口 + 整窗退避」，
	 *       既不让新产出滞留，也不对同一批被拒流体每刻空转全网络遍历</li>
	 *   <li><b>单趟槽位扫描</b>:按 key 聚合库存一次遍历完成，
	 *       聚合结果直接充当 clamp 索引,推送阶段不再按 key 重扫槽位</li>
	 *   <li><b>分块 shrinkStack</b>:推送量超过 Integer.MAX_VALUE 时分块调用 tank.shrinkStack,
	 *       防止 long→int 截断丢失流体(Task 21 新增)</li>
	 * </ul>
	 * <p>
	 * <b>设计原则</b>(SRP):与 {@link Ae2OutputPusher} 分离,独立负责流体推送。
	 * <p>
	 * <b>线程安全</b>:由服务端 tick 线程独占调用,无需同步。
	 */
public final class Ae2FluidPusher {

	/** 异常累计计数器 — 用于日志显示总次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong PUSH_EXCEPTION_COUNTER = new AtomicLong(0);

	/**
	 * 懒加载 Holder — AE2 未安装时本类初始化不触发 {@link BaseActionSource} 类解析（Issue #8）
	 * <br/>
	 * 原静态字段在 &lt;clinit&gt; 执行先于方法体守卫，AE2 未安装时首次调用 pushFluids 即
	 * NoClassDefFoundError。Holder 仅在首次访问 INSTANCE 时初始化（JVM 保证线程安全），
	 * 所有访问点均位于 isFluidPushEnabled 守卫之后的 AE2 路径。
	 */
	private static final class ActionSourceHolder {
		/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态,全局只需 1 个实例 */
		static final IActionSource INSTANCE = new BaseActionSource() {};
	}

	/**
	 * 单次 ME 请求的流体上限（mB）。
	 * <p>
	 * <b>为什么不设成 int 上限</b>：一槽容量本身是 int（Mekanism {@code BasicFluidTank}），
	 * 但多流体槽模式下同一种流体可以占满多个槽，主机总存量可达 <b>槽数 × int 上限</b>
	 * （16 槽即约 3.4e10 mB）。若单次请求钳在 int 上限，即使连发 8 轮也只能推走不到一半，
	 * 高并行产线下表现为「槽满长时间不排空」。
	 * <p>
	 * AE2 侧对超长请求是安全的：{@code NetworkStorage.insert} 逐挂载点用 <b>long</b> 递减，
	 * 落到外部存储时才由 {@code ExternalStorageFacade.insert} 做
	 * {@code Ints.saturatedCast}（饱和到 int 上限，不会回绕成负数），存储元件则按 long 处理。
	 * 取值 {@code 1 << 40}（约 1.1e12 mB）远高于任何现实槽容量，同时给第三方存储的
	 * 「数量 × 倍率」运算留足不溢出的余量。
	 */
	private static final long MAX_FLUID_BATCH_REQUEST_MB = 1L << 40;
	/**
	 * 单键单轮刷新的最大请求轮数 — 仅当网络连续足额接收时才继续下一轮
	 * （{@code inserted < request} 即中断），因此轮数上限只决定「槽容量 &gt; 单次上限」时
	 * 一轮能否推空，不会放大常规调用次数。
	 */
	private static final int MAX_FLUID_BATCH_CALLS_PER_KEY = 8;
	/** Maximum extra local-tank drains in one real tick for a high-parallel output batch. */
	private static final int MAX_ADDITIONAL_LOCAL_DRAINS_PER_TICK = 64;
	/**
	 * 每台机器每真实游戏刻允许的直推流体插入次数。
	 * <p>
	 * 与物品路径的 {@code MAX_DIRECT_GENERATED_ITEM_PUSHES_PER_TICK} 对称：加速模组让同一
	 * 真实游戏刻内可推进 {@code processes × batchMultiplier} 个虚拟刻，每个虚拟刻的产出
	 * 都会各自发起一次完整 ME 网络遍历。超出配额的流体由调用方留在本地罐，
	 * 由 {@link #pushFluids} 的批处理路径（同刻合并 + 失败退避）接管，不丢流体。
	 */
	private static final int MAX_DIRECT_FLUID_INSERTS_PER_TICK = 16;

	private Ae2FluidPusher() {}

	/**
	 * Inserts newly generated fluid before it enters a local tank.
	 * The caller stores {@code requested - returned} locally, so rejection cannot lose fluid.
	 * <p>
	 * <b>返回 0 的四种含义</b>（调用方一律把流体写回本地罐，语义相同）：
	 * (1) 未接线/未开启/节点非 ONLINE；(2) 该流体键处于退避窗口内；
	 * (3) 本真实游戏刻的直推配额已用完（{@link #MAX_DIRECT_FLUID_INSERTS_PER_TICK}）；
	 * (4) 网络拒绝。无论哪种，本地罐内容都会由 {@link #pushFluids} 的批处理路径在后续 tick
	 * 推送，因此配额与退避只改变「由谁发起 insert」，不改变最终吞吐。
	 */
	public static long pushGeneratedFluid(IAe2OutputHostBase host, FluidStack template, long requested) {
		return insertGeneratedFluid(host, template, requested, Actionable.MODULATE);
	}

	/**
	 * Returns the amount the network would accept without changing storage.
	 * <p>
	 * 只读探测：不消耗直推配额、不改动退避状态。注意 AE2 的 SIMULATE 与 MODULATE
	 * 走完全相同的网络遍历路径，因此它省的是数据风险而不是 CPU，不应放在每 tick 热路径上。
	 */
	public static long simulateGeneratedFluid(IAe2OutputHostBase host, FluidStack template, long requested) {
		return insertGeneratedFluid(host, template, requested, Actionable.SIMULATE);
	}

	private static long insertGeneratedFluid(IAe2OutputHostBase host, FluidStack template, long requested,
			Actionable action) {
		if (template == null || template.isEmpty() || requested <= 0) return 0L;
		if (!host.productivebeesgenesis$isFluidPushEnabled()
				|| !host.productivebeesgenesis$isAeFluidOutputEnabled()) return 0L;
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return 0L;
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return 0L;
		if (holder.getPushState().getCachedNodeState(host) != Ae2GridNodeManager.STATE_ONLINE) return 0L;
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return 0L;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return 0L;
		long gameTick = level.getGameTime();
		// 不取网络级工作令牌（与批处理路径同一理由）：该令牌在昂贵网络下每刻只放行一个宿主，
		// 多机同网时会把其它机器的直推整轮跳过、迫使流体回落本地罐。直推的节流由
		// 「每真实游戏刻配额 + per-tile insert 成本预算 + 按 key 退避」共同承担，
		// 与 git 基线一致；实际 insert 成本仍照常回馈给网络级 EWMA（只统计，不闸门）。
		AEFluidKey key = getCachedGeneratedFluidKey(holder, template);
		if (key == null) return 0L;
		// 全服慢 insert 预算：病态网络（EnderDrives fsync）下跳过本轮直推，流体由调用方留在本地
		// 自适应成本记账器与物品路径共用：流体与物品经过同一个 ExternalStorageFacade 门面，
		// 决定 tick 成本的是 insert 调用次数（样板解码/WAL fsync 与 amount 无关），故共享同一份 EWMA。
		Ae2InsertCostTracker costTracker = Ae2OutputPusher.getReusableBuffers(holder, host).insertCostTracker;
		if (costTracker.isExhausted(gameTick)) return 0L;
		Ae2PushStateHolder pushState = holder.getPushState();
		// 满存储/病态网络专项（与物品直推路径同构）：真实 insert 被拒时记入**按 key**退避，
		// 避免加速子 tick 下每刻重复发起注定失败的全量网络遍历。
		// 这里刻意不写整机级 fluidBackoff：单种流体被拒（白名单存储总线等）不该连带
		// 压制同一台机器其它流体的直推与批处理路径；整机级退避只由批量路径的「全部 key 失败」写入。
		// SIMULATE 只是探测调用（遍历成本与 MODULATE 相同，仅用于只读预估），不消耗配额。
		Ae2KeyBackoffRegistry<AEFluidKey> keyBackoff = getOrCreateFluidKeyBackoff(holder);
		boolean modulate = action == Actionable.MODULATE;
		if (modulate) {
			long nanoNow = System.nanoTime();
			if (pushState.getFluidBackoff().shouldSkip(nanoNow)) return 0L;
			if (keyBackoff.shouldSkip(key, nanoNow)) return 0L;
			if (!pushState.tryAcquireGeneratedFluidInsert(gameTick, MAX_DIRECT_FLUID_INSERTS_PER_TICK)) {
				// 配额耗尽：返回 0 让调用方把流体写进本地罐，批处理路径同刻接管（无损）
				return 0L;
			}
		}
		long insertStart = System.nanoTime();
		try {
			long accepted = SaturatingMath.clampToRequest(
					meStorage.insert(key, requested, action, ActionSourceHolder.INSTANCE), requested);
			long insertCost = System.nanoTime() - insertStart;
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
			holder.recordNetworkCost(meStorage, gameTick, insertCost,
					Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
			costTracker.record(gameTick, insertCost);
			if (modulate) {
				if (accepted > 0L) {
					keyBackoff.recordSuccess(key);
				} else {
					keyBackoff.recordFailure(key, System.nanoTime());
				}
			}
			return accepted;
		} catch (Exception e) {
			// 抛异常的 insert 同样入账全服预算，防止病态网络下每 tick 重复昂贵遍历
			long insertCost = System.nanoTime() - insertStart;
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
			holder.recordNetworkCost(meStorage, gameTick, insertCost,
					Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
			costTracker.record(gameTick, insertCost);
			if (modulate) {
				keyBackoff.recordFailure(key, System.nanoTime());
			}
			LogThrottle.warnWithCooldown("ae2_direct_generated_fluid", 60_000L,
					"AE2 direct generated-fluid insert failed: fluid={}, amount={}, error={}",
					key, requested, e.toString());
			return 0L;
		}
	}

	/**
	 * 推送宿主所有流体罐内容到 AE2 网络(Task 13 多槽遍历 + Task 21 批处理缓冲 + spec 自适应)
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。
	 * <p>
	 * <b>Task 21 批处理流水线</b>(按顺序短路):
	 * <ol>
	 *   <li>同刻去重:加速子 tick 在同一真实游戏刻内合并为一次(本地罐的额外排空另计配额)</li>
	 *   <li>深度退避:退避窗口内入口直接返回</li>
	 *   <li>单趟槽位扫描:按 key 聚合当前库存到复用采样表</li>
	 *   <li>触发:出现新增流体即推送；无新增则等 {@link Ae2PendingBatchBuffer#isRipe()} 重试</li>
	 *   <li>网格节点状态检查:仅 ONLINE 时继续(与 Ae2OutputPusher 对称,不触发退避)</li>
	 *   <li>推送阶段:drain batchBuffer,对每个 key 用采样索引 O(1) clamp 到实际库存,
	 *       再执行批量推送,最后按实际接收量 shrink(网络拒绝时不触碰 tank,无丢失)</li>
	 * </ol>
	 *
	 * @param host 输出宿主(蜂箱/离心机方块实体)
	 */
	public static void pushFluids(IAe2OutputHostBase host) {
		pushFluids(host, false);
	}

	/**
	 * Drains fluid committed to a local output tank during the current real game tick.
	 * <p>
	 * This is intentionally separate from direct generated-fluid insertion: the machine has
	 * already committed the fluid to its Mekanism tank before this method is called. It lets a
	 * high-parallel batch reuse that tank without enabling the direct-AE-output option.
	 * <p>
	 * 调用频率仍是每真实游戏刻一次（提供采样时机）：出现新增流体就在本刻真正发起
	 * {@code insert} 并把槽内该流体的全部数量一次推走；没有新增的滞留流体按成熟窗口重试。
	 */
	public static void pushLocalTankContentsNow(IAe2OutputHostBase host) {
		pushFluids(host, true);
	}

	private static void pushFluids(IAe2OutputHostBase host, boolean allowAdditionalSameTickDrain) {
		// 1. 流体推送独立开关检查(与物品推送分离)
		//    注意：这两个接口方法可能被蜂箱子类覆盖，保持原调用方式（各内部调用1次 getAe2StateHolder）
		if (!host.productivebeesgenesis$isFluidPushEnabled()) return;
		// 1.1 per-tile 流体输出开关检查(与全局配置 AND 关系)
		if (!host.productivebeesgenesis$isAeFluidOutputEnabled()) return;

		// Spark 优化：缓存 holder 和 pushState 到局部变量，消除后续 11 次冗余
		// getAe2StateHolder() 接口分发（每次2层接口分发：getLifecycleHandler→getStateHolder）
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		Ae2PushStateHolder pushState = holder.getPushState();
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		long gameTick = level.getGameTime();
		boolean firstPushThisTick = pushState.tryStartFluidPush(gameTick);
		// 同刻额外排空（收尾排空/提交后压力排空）在两种产出模式下都允许：
		// 触发条件本身已经是「有新增或已满槽」，不该因为「直出模式」把本刻新增的流体留到下一 tick。
		// 频率仍由 MAX_ADDITIONAL_LOCAL_DRAINS_PER_TICK 封顶，且加速子 tick 已在 tryStartFluidPush 合并。
		if (!firstPushThisTick && (!allowAdditionalSameTickDrain
				|| !pushState.tryAcquireAdditionalLocalFluidDrain(gameTick, MAX_ADDITIONAL_LOCAL_DRAINS_PER_TICK))) {
			return;
		}

		// 2. 深度退避检查(入口级别)— 退避期内跳过整个 pushFluids 路径(spec Change 3)
		//    基于 System.nanoTime() 墙钟单调时钟,不受 JDTE 加速影响(Task 5)
		long pushCounter = pushState.incrementFluidPushCallCounter();
		if (pushState.getFluidBackoff().shouldSkip(System.nanoTime())) return;

		pushState.updateLastFluidPushCounter(pushCounter);

		// 8. 获取待推送缓冲（holder 感知重载）
		Ae2PendingBatchBuffer batchBuffer = getOrCreatePendingBatchBuffer(holder);

		// 加速子 tick 只推进一次成熟窗口；同刻的额外排空不改变窗口计时。
		if (firstPushThisTick) batchBuffer.tick();

		// 9. 采样阶段（单趟扫描）
		//    一次遍历按 key 聚合当前槽内库存。聚合结果（tankTotals）既作为本刻采样，
		//    也直接充当「推送量 clamp 到实际库存」的索引，推送阶段不必再按 key 重扫槽位。
		List<IExtendedFluidTank> tankSnapshot = host instanceof IMultiFluidTankHost multiFluidHost
				? multiFluidHost.getFluidTanks() : null;
		int tankCount = tankSnapshot == null ? host.fluidOutputTankCount() : tankSnapshot.size();

		Object2LongOpenHashMap<AEFluidKey> tankTotals = batchBuffer.beginTankSample();
		Object2LongOpenHashMap<AEFluidKey> tankCapacities = batchBuffer.tankSampleCapacities();
		for (int i = 0; i < tankCount; i++) {
			IExtendedFluidTank tank = outputTank(host, tankSnapshot, i);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty()) continue;
			long amount = stack.getAmount();
			if (amount <= 0) continue;
			// Task 24：复用按槽缓存的 AEFluidKey，避免每 tick 重建（AEFluidKey.of 会分配新对象）
			AEFluidKey fluidKey = getCachedFluidKey(holder, i, stack);
			if (fluidKey == null) continue;
			tankTotals.put(fluidKey,
					SaturatingMath.saturatingAdd(tankTotals.getLong(fluidKey), amount));
			// 该流体所占槽位的容量之和：用于识别「已满槽」压力状态（见 needsPush）。
			tankCapacities.put(fluidKey, SaturatingMath.saturatingAdd(
					tankCapacities.getLong(fluidKey), Math.max(0, tank.getCapacity())));
		}
		batchBuffer.commitTankSample();

		// 10. 触发条件（三条通道，等价于 useless 的「同 key 快路径 + 成熟窗口」）：
		//     (1) 有新增流体 → 本刻立即推送（有就推送，延迟 0）；
		//     (2) 该流体的槽位已满 → 本刻立即推送。满槽时液面被容量钳住无法再上升，
		//         若只以「液面是否升高」判新增就会恒为假，退化成 10 刻才推一次，
		//         高并行产线下（产量 ≫ 单次可排空量）槽就长期满着 —— 这是上一版的回归点；
		//     (3) 既无新增也未满槽，但窗口到期（RIPE_TICKS 刻）→ 重试上一批滞留流体。
		//     窗口计时器不因累积而重置（同 useless 的 PendingAEBatch#add），否则永远不会成熟。
		//     每次推送把该流体在槽内的全部数量一次推走（clamp 到本刻实际库存，
		//     单次请求上限与轮数见 MAX_FLUID_BATCH_REQUEST_MB / MAX_FLUID_BATCH_CALLS_PER_KEY）。
		if (!batchBuffer.needsPush(tankTotals, tankCapacities) && !batchBuffer.isRipe()) return;

		// 3. 卡顿保护说明:曾设计 TPS 自适应跳过（TPS<5 时停推），因会导致流体槽打满停机、
		//     伤害产出效率已移除；卡顿保护由 Ae2GlobalInsertBudget 慢 insert 预算承担
		//     （只钳制病态网络的慢 insert，不误伤正常推送）。

		// 3.1 模块2.1 对称：强检测 grid node 状态，仅当 ONLINE(3) 时继续推送
		//     状态 0/1/2: OFFLINE/NETWORK_BOOTING/MISSING_CHANNEL — 不进入 poweredInsert 路径，不触发退避
		//     修复：原流体推送器缺少此检查（物品推送器有），导致网格不稳定时 poweredInsert
		//     在节点非 ONLINE 状态下执行只会产生无效存储调用
		int nodeState = pushState.getCachedNodeState(host);
		if (nodeState != Ae2GridNodeManager.STATE_ONLINE) {
			return;
		}

		// 4. 获取网格节点 + 已连接的网格（holder 感知重载，跳过冗余 getAe2StateHolder）
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return;

		// 5. 获取存储服务和 ME 存储（holder 感知重载，跳过冗余 getAe2StateHolder）
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;
		// 不再取网络级工作令牌：该令牌在「昂贵网络」下每刻只放行一个宿主、且全网络每刻仅
		// 2ms 超额预算，会让多台机器共享同一 ME 网络时大部分机器的流体推送被整轮跳过
		// （表现为槽满长时间不排空）。批处理路径本来就有足够的节流：每真实游戏刻一次、
		// 三种触发条件、按 key 退避、per-tile/全服 insert 成本预算（Ae2InsertCostTracker）。
		// git 基线的流体路径同样没有该令牌，这里恢复基线语义；实际 insert 成本仍照常
		// 通过 recordNetworkCost 回馈给网络级 EWMA（只统计，不闸门）。

		// 11. Task 21: drain 并批量推送
		Object2LongMap<AEFluidKey> pendingMap = batchBuffer.drainFast();
		if (pendingMap.isEmpty()) return;

		boolean anySuccess = false;
		boolean allFailed = true;
		long totalRequested = 0L;
		long totalActualShrunk = 0L; // 实际从 tank shrink 的总量（区分 tank 已空和推送失败）
		long rejectedCount = 0L; // 被网络完全拒绝的流体种类数（用于触发退避）
		long nanoNow = System.nanoTime();
		Ae2KeyBackoffRegistry<AEFluidKey> keyBackoff = getOrCreateFluidKeyBackoff(holder);
		// 与物品推送共享同一 per-tile 成本记账器：同一 ME 网络的昂贵外部存储对流体同样昂贵，
		// 共享 EWMA 可让任一路径先探到病态网络后，另一路径立即受益（无需各自重新学习）。
		Ae2InsertCostTracker costTracker = Ae2OutputPusher.getReusableBuffers(holder, host).insertCostTracker;

		for (Object2LongMap.Entry<AEFluidKey> entry : pendingMap.object2LongEntrySet()) {
			AEFluidKey fluidKey = entry.getKey();
			long amount = entry.getLongValue();
			long tankTotal = tankTotals.getLong(fluidKey);
			if (keyBackoff.shouldSkip(fluidKey, nanoNow)) {
				// 退避期内不动这一批：登记「仍在槽内」的余量，下一轮才不会被误判成新增流体
				batchBuffer.recordAttempt(fluidKey, tankTotal);
				continue;
			}
			totalRequested = SaturatingMath.saturatingAdd(totalRequested, amount);

			try {
				// 无丢失推送：clamp 到「本刻采样到的该流体实际库存」作为推送上限。
				// 缓冲里的 amount 是窗口内最大采样，可能因 Ejector 弹出等已过期；
				// tankTotals 是本轮单趟扫描刚构建的索引（同一真实游戏刻、推送前无其它写者），
				// 因此与重新扫描等价但为 O(1)。clamp 后按实际接收量 shrink 精确扣除，
				// 未接收部分天然留在 tank，无需回填，杜绝"先 shrink 后推送失败再回填"的丢失。
				if (tankTotal <= 0) {
					batchBuffer.recordAttempt(fluidKey, 0L); // tank 已空：下次该流体出现即算新增
					continue;
				}
				long pushed = Math.min(amount, tankTotal);

				long inserted = batchPush(holder, meStorage, fluidKey, pushed, gameTick, costTracker);
				if (inserted <= 0) {
					// 完全失败：不触碰 tank，触发按 key 退避，并登记余量等窗口重试
					keyBackoff.recordFailure(fluidKey, nanoNow);
					batchBuffer.recordAttempt(fluidKey, tankTotal);
					rejectedCount = SaturatingMath.saturatingAdd(rejectedCount, 1L);
					continue;
				}
				keyBackoff.recordSuccess(fluidKey);
				anySuccess = true;
				allFailed = false;
				// 按实际接收量从 tank 精确扣除。inserted ≤ pushed ≤ tankTotal，
				// 正常情况下 shrink 必然足额；不足仅可能出现在极端并发/异常，
				// 记录 error 防止静默复制（防御性）。
				long shrunk = shrinkStackSafely(host, tankSnapshot, fluidKey, inserted, tankCount);
				totalActualShrunk = SaturatingMath.saturatingAdd(totalActualShrunk, shrunk);
				// 部分成功：余量仍留在槽内。记为「已尝试过的存量」，让新增产出才能触发下一轮直推，
				// 否则同一个被拒余量会每刻重复进入推送路径（AE2LT 的「EXTRACTED/BLOCKED 保持活跃、
				// 只有 EMPTY/UNAVAILABLE 才退避」同理：有货但被阻塞不等于该退避）。
				batchBuffer.recordAttempt(fluidKey, Math.max(0L, tankTotal - shrunk));
				if (shrunk < inserted) {
					LogThrottle.error("ae2_fluid_shrink_mismatch",
							"AE2 流体推送后 shrink 不足: fluid={}, inserted={}, shrunk={} (5秒内仅首条)",
							fluidKey, inserted, shrunk);
				}

				logPushResult(fluidKey, pushed, inserted);
			} catch (Exception e) {
				keyBackoff.recordFailure(fluidKey, nanoNow);
				batchBuffer.recordAttempt(fluidKey, tankTotal);
				rejectedCount = SaturatingMath.saturatingAdd(rejectedCount, 1L);
				handlePushException(e, fluidKey, amount);
			}
		}

		// 12. Only a batch where every attempted key was rejected enters short backoff.
		// Any accepted key resets it so another fluid cannot hold the whole host offline.
		Ae2PushBackoff fluidBackoff = pushState.getFluidBackoff();
		if (allFailed && (totalActualShrunk > 0 || rejectedCount > 0)) {
			fluidBackoff.recordFailure(System.nanoTime());
			LogThrottle.warn("ae2_fluid_backoff",
					"AE2 流体推送全部失败，进入短退避 totalRequested={}, 指数={}",
					totalRequested, fluidBackoff.getBackoffExponent());
		} else if (anySuccess) {
			fluidBackoff.recordSuccess();
		}
		if (totalActualShrunk > 0) host.productivebeesgenesis$onAe2FluidPushComplete();
	}

	@SuppressWarnings("unchecked")
	private static Ae2KeyBackoffRegistry<AEFluidKey> getOrCreateFluidKeyBackoff(
			Ae2OutputStateHolder holder) {
		Object cached = holder.getPushState().getFluidKeyBackoffRegistry();
		if (cached instanceof Ae2KeyBackoffRegistry<?> registry) {
			return (Ae2KeyBackoffRegistry<AEFluidKey>) registry;
		}
		Ae2KeyBackoffRegistry<AEFluidKey> registry = new Ae2KeyBackoffRegistry<>();
		holder.getPushState().setFluidKeyBackoffRegistry(registry);
		return registry;
	}

	/** 多槽宿主复用稳定列表；单槽宿主保持原接口语义。 */
	private static IExtendedFluidTank outputTank(IAe2OutputHostBase host,
			List<IExtendedFluidTank> tankSnapshot, int index) {
		return tankSnapshot == null ? host.fluidOutputTank(index) : tankSnapshot.get(index);
	}

	/**
	 * 获取（或构建并缓存）指定流体槽的 AEFluidKey。
	 * <br/>
	 * Task 24：{@code AEFluidKey.of(FluidStack)} 每次分配 AEFluidKey + FluidStack，
	 * 多槽高并行工厂在大量机器下每 tick 累积都会产生分配压力。
	 * 无组件补丁时按 Fluid 引用失效并跳过 hash；带组件时额外校验内容 hash，
	 * 防止可变组件映射原地更新后错误复用缓存对象。
	 *
	 * @param holder AE2 状态持有者（承载 per-tile 缓存）
	 * @param index  流体槽索引
	 * @param stack  当前槽位流体（仅读取，不修改）
	 * @return 缓存的 AEFluidKey；栈为空返回 null
	 */
	private static AEFluidKey getCachedFluidKey(Ae2OutputStateHolder holder, int index, FluidStack stack) {
		Object fluid = stack.getFluid();
		boolean componentsEmpty = stack.isComponentsPatchEmpty();
		int componentsHash = componentsEmpty ? 0 : stack.getComponents().hashCode();
		if (holder.getCachedFluidPushKeyFluid(index) == fluid
				&& holder.isCachedFluidPushKeyComponentsEmpty(index) == componentsEmpty
				&& (componentsEmpty
						|| holder.getCachedFluidPushKeyComponentsHash(index) == componentsHash)
				&& holder.getCachedFluidPushKey(index) instanceof AEFluidKey existing) {
			return existing;
		}
		AEFluidKey created = AEFluidKey.of(stack);
		holder.setCachedFluidPushKey(index, created, fluid, componentsEmpty, componentsHash);
		return created;
	}

	private static AEFluidKey getCachedGeneratedFluidKey(
			Ae2OutputStateHolder holder, FluidStack stack) {
		Object fluid = stack.getFluid();
		boolean componentsEmpty = stack.isComponentsPatchEmpty();
		int componentsHash = componentsEmpty ? 0 : stack.getComponents().hashCode();
		if (holder.getGeneratedFluidKeyFluidRef() == fluid
				&& holder.isGeneratedFluidKeyComponentsEmpty() == componentsEmpty
				&& (componentsEmpty
						|| holder.getGeneratedFluidKeyComponentsHash() == componentsHash)
				&& holder.getGeneratedFluidKeyCache() instanceof AEFluidKey existing) {
			return existing;
		}
		AEFluidKey created = AEFluidKey.of(stack);
		holder.setGeneratedFluidKeyCache(created, fluid, componentsEmpty, componentsHash);
		return created;
	}

	/**
	 * Task 21: 获取或创建批处理缓冲(懒初始化,存储在 Ae2OutputStateHolder 中)
	 * <br/>
	 * 字段类型为 Object 保持 AE2 依赖隔离,此处 instanceof 检查后强转。
	 * AE2 未安装时不会调用本方法(上层 isFluidPushEnabled 守卫)。
	 * <p>
	 * Spark 优化：接受预缓存的 holder，避免冗余 getAe2StateHolder() 接口分发。
	 *
	 * @param holder 已缓存的 AE2 状态持有者
	 */
	private static Ae2PendingBatchBuffer getOrCreatePendingBatchBuffer(Ae2OutputStateHolder holder) {
		Object obj = holder.getPendingBatchBuffer();
		if (obj instanceof Ae2PendingBatchBuffer buffer) return buffer;
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		holder.setPendingBatchBuffer(buffer);
		return buffer;
	}

	/**
	 * 自适应分批推送核心循环
	 * <br/>
	 * <b>批量大小</b>:单次请求 {@link #MAX_FLUID_BATCH_REQUEST_MB}（int 上限，约 21.47 亿 mB），
	 * 匹配 JDTE 加速 + 高 STACK 升级下的高吞吐需求：一轮刷新把槽内流体推空，
	 * 而不是拆成多次全量网络遍历。
	 * <p>
	 * <b>循环策略</b>:
	 * <ol>
	 *   <li>每次推送 min(单次上限, remaining) mB</li>
	 *   <li>若 inserted == 0,说明 AE2 网络完全无法接收,停止循环(完全失败)</li>
	 *   <li>若 inserted &lt; request,说明网络已满,停止循环(部分成功)</li>
	 *   <li>若 inserted == request,继续下一批,直到 remaining == 0 或轮数达上限</li>
	 * </ol>
	 * 正常网络一次调用即可推空；轮数上限只为「槽容量 &gt; int 上限」的极端配置兜底。
	 *
	 * @param meStorage     AE2 存储目标
	 * @param fluidKey      流体键
	 * @param amount        总推送量(mB)
	 * @param gameTick      当前游戏刻 — 供全服慢 insert 预算判定与记账
	 * @param costTracker   自适应成本记账器（与物品路径共享同一 ME 网络的 EWMA）
	 * @return 实际推送总量(mB);0 表示完全失败
	 */
	private static long batchPush(Ae2OutputStateHolder holder, MEStorage meStorage, AEFluidKey fluidKey,
			long amount, long gameTick, Ae2InsertCostTracker costTracker) {
		// 单次请求尽量大（int 上限），一轮推空槽内流体；只有网络连续足额接收才会继续下一轮。
		long remaining = Math.max(0L, amount);
		long insertedTotal = 0L;
		for (int call = 0; call < MAX_FLUID_BATCH_CALLS_PER_KEY && remaining > 0L; call++) {
			// 网络级令牌已在入口取得；循环内仅保留本机成本预算，跨网络不共享硬闸门。
			if (costTracker.isExhausted(gameTick)) break;
			long request = Math.min(remaining, MAX_FLUID_BATCH_REQUEST_MB);
			long insertStart = System.nanoTime();
			long inserted;
			try {
				inserted = SaturatingMath.clampToRequest(
						meStorage.insert(fluidKey, request, Actionable.MODULATE, ActionSourceHolder.INSTANCE), request);
			} catch (RuntimeException error) {
				long insertCost = System.nanoTime() - insertStart;
				Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
				holder.recordNetworkCost(meStorage, gameTick, insertCost,
						Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
				costTracker.record(gameTick, insertCost);
				throw error;
			}
			long insertCost = System.nanoTime() - insertStart;
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
			holder.recordNetworkCost(meStorage, gameTick, insertCost,
					Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
			costTracker.record(gameTick, insertCost);
			if (inserted <= 0L) break;
			insertedTotal = SaturatingMath.saturatingAdd(insertedTotal, inserted);
			remaining -= inserted;
			if (inserted < request) break;
		}
		return SaturatingMath.clampToRequest(insertedTotal, amount);
	}

	/**
	 * Task 21: 按比例从匹配 fluidKey 的 tank 分块 shrinkStack(防 long→int 截断丢失)
	 * <br/>
	 * <b>分块原理</b>:256× 加速 + 16 STACK 下,单次推送量可能超过 Integer.MAX_VALUE(21 亿 mB)。
	 * 直接 {@code tank.shrinkStack((int) totalToShrink, EXECUTE)} 会截断丢失流体。
	 * 分块策略:每次 shrink 最多 Integer.MAX_VALUE,循环直到全部 shrink 完成。
	 * <p>
	 * <b>多 tank 分配</b>:遍历所有匹配 fluidKey 的非空 tank,按顺序 shrink(先 shrink 第一个 tank
	 * 直到空,再 shrink 下一个)。总和等于 totalToShrink,不丢失流体。
	 * <p>
	 * <b>M4-1 修复</b>:返回实际 shrink 总量,调用方对比 inserted 判断是否有复制风险。
	 * 内部对每个 tank 的 shrink 用 try-catch 包住,异常时记录 ERROR 并跳出,避免部分失败时静默继续。
	 * <p>
	 * <b>Spark 优化</b>:用 Fluid 引用比较替代 AEFluidKey.of(stack) 重建。
	 * AEFluidKey 基于 Fluid（无 NBT），equals 等价于 fluid 引用比较，
	 * 缓存 fluidKey.getFluid() 后用 == 直接比较，避免每次循环重建 AEFluidKey。
	 *
	 * @param host           输出宿主
	 * @param fluidKey       流体键(用于匹配 tank)
	 * @param totalToShrink  总 shrink 量(mB,等于实际推送量)
	 * @param tankCount      流体罐总数
	 * @return 实际 shrink 总量(mB),小于 totalToShrink 表示有复制风险
	 */
	private static long shrinkStackSafely(IAe2OutputHostBase host,
			List<IExtendedFluidTank> tankSnapshot, AEFluidKey fluidKey,
			long totalToShrink, int tankCount) {
		if (totalToShrink <= 0) return 0;
		long remaining = totalToShrink;
		long totalShrunk = 0;

		// 遍历所有匹配 fluidKey 的非空 tank,顺序 shrink
		for (int i = 0; i < tankCount && remaining > 0; i++) {
			IExtendedFluidTank tank = outputTank(host, tankSnapshot, i);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty()) continue;
			if (!fluidKey.matches(stack)) continue;

			long tankAmount = stack.getAmount();
			long shrinkThisTank = Math.min(remaining, tankAmount);
			long actualShrunk = shrinkSingleTankChunked(tank, shrinkThisTank);
			totalShrunk = SaturatingMath.saturatingAdd(totalShrunk, actualShrunk);
			remaining -= actualShrunk;
			// 实际 shrink 量小于请求量,说明 tank 状态异常,跳出避免无效循环
			if (actualShrunk < shrinkThisTank) break;
		}
		return totalShrunk;
	}

	/**
	 * Task 21: 对单个 tank 分块调用 shrinkStack(防 long→int 截断)
	 * <br/>
	 * 每块最多 Integer.MAX_VALUE,循环直到全部 shrink 完成。
	 * <p>
	 * <b>M4-1 契约</b>:{@code IExtendedFluidTank#shrinkStack} 按 Mekanism API 契约返回
	 * <b>实际扣减量</b>（BasicFluidTank 覆写 {@code setStackSize} 后为就地 {@code stored.setAmount}）。
	 * 因此直接采信返回值，省掉原先每块两次 {@code getFluid()} 读回；若第三方 tank 少报，
	 * 调用方的 {@code shrunk < inserted} 校验仍会报警，不会静默复制。
	 * <p>
	 * <b>M4-1 修复</b>:用 try-catch 包住每次 shrink,异常时记录 ERROR 并返回已 shrink 量。
	 *
	 * @param tank   流体罐
	 * @param amount 总 shrink 量(mB,必须 > 0)
	 * @return 实际 shrink 量(mB),小于 amount 表示 tank 异常
	 */
	private static long shrinkSingleTankChunked(IExtendedFluidTank tank, long amount) {
		long totalShrunk = 0;
		while (amount > 0) {
			long chunk = Math.min(amount, Integer.MAX_VALUE);
			try {
				long actualChunk = tank.shrinkStack((int) chunk, mekanism.api.Action.EXECUTE);
				// 防御第三方实现超报：单块扣减不得超过请求量
				if (actualChunk > chunk) actualChunk = chunk;
				if (actualChunk <= 0) break;
				totalShrunk = SaturatingMath.saturatingAdd(totalShrunk, actualChunk);
				amount -= actualChunk;
			} catch (Exception e) {
				// shrink 异常时记录 ERROR 并返回已 shrink 量,调用方判断是否有复制风险
				// M9 修复：用 LogThrottle.error 节流，5 秒内同 key 仅首条输出，避免高频刷屏
				LogThrottle.error("ae2_fluid_shrink_exception",
						"AE2 流体 tank shrinkStack 异常,可能存在复制风险 (5秒内仅首条输出): {}", e.toString());
				break;
			}
		}
		return totalShrunk;
	}

	/**
	 * 记录推送结果日志
	 * <br/>
	 * <b>日志分级策略</b>:
	 * <ul>
	 *   <li><b>完全成功</b>(totalInserted == requestedAmount):不打扰</li>
	 *   <li><b>部分成功</b>(0 &lt; totalInserted &lt; requestedAmount):WARN 级别,
	 *       说明 AE2 网络容量不足,剩余流体降级到 Ejector</li>
	 *   <li><b>完全失败</b>(totalInserted == 0):WARN 级别,
	 *       退避机制自动处理,降级到 Ejector</li>
	 * </ul>
	 */
	private static void logPushResult(AEFluidKey fluidKey, long requestedAmount, long totalInserted) {
		if (totalInserted == requestedAmount) {
			// 完全成功 — DEBUG 级别,正常路径
			return;
		}

		if (totalInserted > 0) {
			// 部分成功 — AE2 网络容量不足,剩余流体降级到 Ejector
			// DevLog 节流日志便于排查（高频 tick 路径，避免刷屏）
			DevLog.warn("ae2_fluid_push", "分批推送部分成功 流体={}, 已推送={}, 剩余={}, 降级到 Ejector (AE2 网络容量不足)",
					fluidKey, totalInserted, requestedAmount - totalInserted);
			return;
		}

		// 完全失败 — 退避机制自动处理
		DevLog.warn("ae2_fluid_push", "分批推送失败 流体={}, 数量={}, 降级到 Ejector",
				fluidKey, requestedAmount);
	}

	/**
	 * 异常处理:限流日志 + InterruptedException 恢复中断
	 * <br/>
	 * M9 修复：原原子计数器节流（1+1024n 触发）在 256× 加速下单 tick 可达 1024 次异常，
	 * 导致每 tick 刷屏。改用 LogThrottle.error 时间维度节流（5 秒内同 key 仅首条）。
	 */
	private static void handlePushException(Exception e, AEFluidKey fluidKey, long amount) {
		long count = PUSH_EXCEPTION_COUNTER.incrementAndGet();
		// M9: 时间维度节流替代计数器节流，避免高频刷屏
		LogThrottle.error("ae2_fluid_push_exception",
				"AE2 流体推送异常 (累计 {} 次,5秒内仅首条输出) - fluid={}, amount={}: {}",
				count, fluidKey, amount, e.toString());
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}
}
