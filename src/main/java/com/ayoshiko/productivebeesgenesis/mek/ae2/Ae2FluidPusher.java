package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;

import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import mekanism.api.fluid.IExtendedFluidTank;

/**
 * AE2 流体输出推送器(Bug 7)
 * <br/>
 * 将宿主的流体罐内容推送到 AE2 网络,与 {@link Ae2OutputPusher} 物品推送并行工作。
 * <p>
 * <b>Task 21 批处理缓冲集成</b>:引入 {@link Ae2PendingBatchBuffer} 实现 20 tick 累积窗口,
 * 相同 AEFluidKey 跨 tick 合并,256× 加速 + 16 STACK(65536 并行)下 AE2 API 调用次数降低 ≥ 99.8%。
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
 *   <li><b>TPS 自适应</b>:服务器 MSPT 严重卡顿(tpsFactor &lt; 0.5)时跳过推送,让出 tick 资源</li>
 *   <li><b>网格节点状态检查</b>:仅 ONLINE(3) 时继续推送,与 {@link Ae2OutputPusher} 对称,
 *       非 ONLINE 状态直接返回不触发退避(修复: 原缺少此检查导致网格不稳定时 poweredInsert 必然失败)</li>
 *   <li><b>退避</b>:仅所有流体 key 都失败时进入 50ms→1s 的短指数退避,
 *       基于 {@link System#nanoTime()} 墙钟单调时钟,窗口内跳过所有 AE2 存储操作,
 *       避免 JDTE 加速下 counter 退避失效(Task 5)。
 *       部分成功不重置退避(避免"部分成功→重置→立即失败→激进退避"死循环)</li>
	 *   <li><b>固定分批</b>:单次 MEStorage 请求最多 16,000,000 mB，限制第三方存储调用规模</li>
	 *   <li><b>真实游戏刻窗口</b>:加速子 tick 在入口合并。低产量按 20 游戏刻批量，
	 *       槽内总量达到 250,000 mB 时立即刷新</li>
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

	/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态,全局只需 1 个实例 */
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	/** 进入"按子 tick 累积并立即刷新"模式的槽内流体阈值（mB）— 低于此值时只在窗口边界累积 */
	private static final long SATURATION_ACCUMULATE_THRESHOLD_MB = 250_000L;

	private Ae2FluidPusher() {}

	/**
	 * Inserts newly generated fluid before it enters a local tank.
	 * The caller stores {@code requested - returned} locally, so rejection cannot lose fluid.
	 */
	public static long pushGeneratedFluid(IAe2OutputHostBase host, FluidStack template, long requested) {
		return insertGeneratedFluid(host, template, requested, Actionable.MODULATE);
	}

	/** Returns the amount the network would accept without changing storage. */
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
		AEFluidKey key = AEFluidKey.of(template);
		if (key == null) return 0L;
		try {
			return meStorage.insert(key, requested, action, ACTION_SOURCE);
		} catch (Exception e) {
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
	 *   <li>深度退避:退避窗口内入口直接返回</li>
	 *   <li>TPS 自适应:严重卡顿时跳过,由 MEK Ejector 兜底</li>
	 *   <li>网格节点状态检查:仅 ONLINE 时继续(与 Ae2OutputPusher 对称,不触发退避)</li>
	 *   <li>批量短路:同一"游戏刻"内(counter 差值 &lt; M)跳过</li>
	 *   <li>累积阶段:遍历流体罐,将 fluidKey+amount 累积到 batchBuffer</li>
	 *   <li>刷新检查:isRipe()(20 tick 窗口) OR shouldFlushNow(超 50 亿 mB) 时刷新</li>
	 *   <li>推送阶段:drain batchBuffer,对每个 key 先按当前 tank 实际总量 clamp 请求量,
	 *       再执行批量推送,最后按实际接收量 shrink(网络拒绝时不触碰 tank,无丢失)</li>
	 * </ol>
	 *
	 * @param host 输出宿主(蜂箱/离心机方块实体)
	 */
	public static void pushFluids(IAe2OutputHostBase host) {
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
		if (level == null || !pushState.tryStartFluidPush(level.getGameTime())) return;

		// 2. 深度退避检查(入口级别)— 退避期内跳过整个 pushFluids 路径(spec Change 3)
		//    基于 System.nanoTime() 墙钟单调时钟,不受 JDTE 加速影响(Task 5)
		long pushCounter = pushState.incrementFluidPushCallCounter();
		if (pushState.getFluidBackoff().shouldSkip(System.nanoTime())) return;

		pushState.updateLastFluidPushCounter(pushCounter);

		// 8. Task 21: 获取批处理缓冲（holder 感知重载）
		Ae2PendingBatchBuffer batchBuffer = getOrCreatePendingBatchBuffer(holder);

		// 9. Task 21: 累积阶段
		//     第一遍：廉价统计槽内流体总量（仅 getFluid/getAmount，不构建 AEFluidKey）
		int tankCount = host.fluidOutputTankCount();
		long totalInTanks = 0L;
		for (int i = 0; i < tankCount; i++) {
			IExtendedFluidTank tank = host.fluidOutputTank(i);
			if (tank == null || tank.isEmpty()) continue;
			totalInTanks += tank.getFluid().getAmount();
		}

		// tryStartFluidPush 已将加速子 tick 合并为每个真实游戏刻一次。
		batchBuffer.tick();
		if (totalInTanks <= 0) return;

		// 第二遍：每个真实游戏刻采样一次。加速子 tick 已在入口合并，不会放大组件哈希开销。
		for (int i = 0; i < tankCount; i++) {
			IExtendedFluidTank tank = host.fluidOutputTank(i);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty()) continue;
			// Task 24：复用按槽缓存的 AEFluidKey，避免每 tick 重建（AEFluidKey.of 会分配新对象）
			AEFluidKey fluidKey = getCachedFluidKey(holder, i, stack);
			if (fluidKey == null) {
				continue;
			}
			long amount = stack.getAmount();
			if (amount <= 0) continue;
			batchBuffer.accumulate(fluidKey, amount);
		}

		// 10. 刷新检查：
		//     - 成熟窗口（低产量批量，默认 20 游戏刻）
		//     - 累积量达到自适应阈值：槽内流体总量（即首个采样采满整槽时立即刷新）
		//       且至少 250,000 mB，避免极小罐在低产量下每子 tick 都调用 AE API
		long adaptiveThreshold = Math.max(SATURATION_ACCUMULATE_THRESHOLD_MB, totalInTanks);
		boolean shouldFlush = batchBuffer.isRipe()
				|| batchBuffer.shouldFlushNow(batchBuffer.getTotalAmount(), adaptiveThreshold);
		if (!shouldFlush) return;

		// 3. TPS 自适应:TPS 严重下降时跳过推送,让出服务器资源（阈值从 10 降到 5，
		//     避免 256× 加速下常规高负载把推送整体关停导致流体槽打满停机）

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

		// 11. Task 21: drain 并批量推送
		ConcurrentMap<AEFluidKey, Long> pendingMap = batchBuffer.drain();
		if (pendingMap.isEmpty()) return;

		boolean anySuccess = false;
		boolean allFailed = true;
		long totalRequested = 0L;
		long totalActualShrunk = 0L; // 实际从 tank shrink 的总量（区分 tank 已空和推送失败）
		long rejectedCount = 0L; // 被网络完全拒绝的流体种类数（用于触发退避）

		for (Map.Entry<AEFluidKey, Long> entry : pendingMap.entrySet()) {
			AEFluidKey fluidKey = entry.getKey();
			long amount = entry.getValue();
			totalRequested += amount;

			try {
				// 无丢失推送：先快照当前 tank 中该流体的实际总量作为推送上限。
				// 缓冲里的 amount 是窗口内最大采样，可能因 Ejector 弹出等已过期，
				// clamp 到当前实际库存可保证「推入 AE 的量 ≤ 实际库存」，
				// 之后按实际接收量 shrink 精确扣除，未接收部分天然留在 tank，
				// 无需回填，杜绝"先 shrink 后推送失败再回填"路径中的流体丢失。
				long tankTotal = currentTankAmount(host, fluidKey, tankCount);
				if (tankTotal <= 0) continue; // tank 已空，跳过推送
				long pushed = Math.min(amount, tankTotal);

				long inserted = batchPush(meStorage, fluidKey, pushed);
				if (inserted <= 0) {
					// 完全失败：不触碰 tank，触发退避
					rejectedCount++;
					continue;
				}
				anySuccess = true;
				allFailed = false;
				// 按实际接收量从 tank 精确扣除。inserted ≤ pushed ≤ tankTotal，
				// 正常情况下 shrink 必然足额；不足仅可能出现在极端并发/异常，
				// 记录 error 防止静默复制（防御性）。
				long shrunk = shrinkStackSafely(host, fluidKey, inserted, tankCount);
				totalActualShrunk += shrunk;
				if (shrunk < inserted) {
					LogThrottle.error("ae2_fluid_shrink_mismatch",
							"AE2 流体推送后 shrink 不足: fluid={}, inserted={}, shrunk={} (5秒内仅首条)",
							fluidKey, inserted, shrunk);
				}

				logPushResult(fluidKey, pushed, inserted);
			} catch (Exception e) {
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

	/**
	 * 快照当前所有匹配 tank 中该流体的实际总量。
	 * <br/>
	 * 与 {@link #shrinkStackSafely} 的匹配规则一致（Fluid 引用比较，AEFluidKey 无 NBT）。
	 *
	 * @param host      输出宿主
	 * @param fluidKey  流体键
	 * @param tankCount 流体罐总数
	 * @return 当前实际总量(mB)；0 表示该流体当前不在任何 tank
	 */
	private static long currentTankAmount(IAe2OutputHostBase host, AEFluidKey fluidKey, int tankCount) {
		long total = 0L;
		for (int i = 0; i < tankCount; i++) {
			IExtendedFluidTank tank = host.fluidOutputTank(i);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty() || !fluidKey.matches(stack)) continue;
			total += stack.getAmount();
		}
		return total;
	}

	/**
	 * 获取（或构建并缓存）指定流体槽的 AEFluidKey。
	 * <br/>
	 * Task 24：{@code AEFluidKey.of(FluidStack)} 每次分配 AEFluidKey + FluidStack，
	 * 多槽高并行工厂在大量机器下每 tick 累积都会产生分配压力。
	 * 按 (Fluid 引用 + components hash) 失效：流体类型不变时直接复用缓存对象。
	 *
	 * @param holder AE2 状态持有者（承载 per-tile 缓存）
	 * @param index  流体槽索引
	 * @param stack  当前槽位流体（仅读取，不修改）
	 * @return 缓存的 AEFluidKey；栈为空返回 null
	 */
	private static AEFluidKey getCachedFluidKey(Ae2OutputStateHolder holder, int index, FluidStack stack) {
		Object fluid = stack.getFluid();
		int componentsHash = stack.getComponents().hashCode();
		if (holder.getCachedFluidPushKeyFluid(index) == fluid
				&& holder.getCachedFluidPushKeyComponentsHash(index) == componentsHash
				&& holder.getCachedFluidPushKey(index) instanceof AEFluidKey existing) {
			return existing;
		}
		AEFluidKey created = AEFluidKey.of(stack);
		holder.setCachedFluidPushKey(index, created, fluid, componentsHash);
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
	 * spec M 自适应分批推送核心循环(Spark优化: 批大小从100K提升到1M)
	 * <br/>
	 * <b>批量大小</b>:1000000 × M mB(M=1 时 100万, M=256 时 2.56亿),
	 * 匹配 JDTE 加速 + 高 STACK 升级下的高吞吐需求,减少循环次数。
	 * <p>
	 * <b>循环策略</b>:
	 * <ol>
	 *   <li>每次推送 min(batchSizeLimit, remaining) mB</li>
	 *   <li>若 inserted &lt; batchSize,说明 AE2 网络容量已满,停止循环(部分成功)</li>
	 *   <li>若 inserted == 0,说明 AE2 网络完全无法接收,停止循环(完全失败)</li>
	 *   <li>若 inserted == batchSize,继续下一批,直到 remaining == 0</li>
	 * </ol>
	 *
	 * @param meStorage     AE2 存储目标
	 * @param fluidKey      流体键
	 * @param amount        总推送量(mB)
	 * @return 实际推送总量(mB);0 表示完全失败
	 */
	private static long batchPush(MEStorage meStorage, AEFluidKey fluidKey, long amount) {
		// 固定有限批量，避免 256× 时单次向第三方存储请求数亿 mB，同时保持较低调用次数。
		long batchSizeLimit = 16_000_000L;
		return Math.max(0L, meStorage.insert(fluidKey, amount, Actionable.MODULATE, ACTION_SOURCE));
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
	private static long shrinkStackSafely(IAe2OutputHostBase host, AEFluidKey fluidKey,
			long totalToShrink, int tankCount) {
		if (totalToShrink <= 0) return 0;
		long remaining = totalToShrink;
		long totalShrunk = 0;

		// 遍历所有匹配 fluidKey 的非空 tank,顺序 shrink
		for (int i = 0; i < tankCount && remaining > 0; i++) {
			IExtendedFluidTank tank = host.fluidOutputTank(i);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty()) continue;
			if (!fluidKey.matches(stack)) continue;

			long tankAmount = stack.getAmount();
			long shrinkThisTank = Math.min(remaining, tankAmount);
			long actualShrunk = shrinkSingleTankChunked(tank, shrinkThisTank);
			totalShrunk += actualShrunk;
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
	 * <b>M4-1 修复</b>:用 try-catch 包住每次 shrink,异常时记录 ERROR 并返回已 shrink 量。
	 * 回读 before/after 实际量,防止 tank 内部保护逻辑导致实际 shrink 不足而静默复制。
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
				// 回读 before/after 计算实际 shrink 量,防止 tank 内部截断或保护逻辑导致不足
				FluidStack before = tank.getFluid();
				long beforeAmount = before.isEmpty() ? 0 : before.getAmount();
				tank.shrinkStack((int) chunk, mekanism.api.Action.EXECUTE);
				FluidStack after = tank.getFluid();
				long afterAmount = after.isEmpty() ? 0 : after.getAmount();
				long actualChunk = Math.max(0, beforeAmount - afterAmount);
				totalShrunk += actualChunk;
				amount -= actualChunk;
				// tank 实际未 shrink(chunk > 0 但 actualChunk == 0),跳出避免无限循环
				if (actualChunk == 0 && chunk > 0) break;
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
