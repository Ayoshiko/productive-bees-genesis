package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.me.helpers.BaseActionSource;

import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

import mekanism.api.fluid.IExtendedFluidTank;

/**
 * AE2 流体输出推送器(Bug 7)
 * <br/>
 * 将宿主的流体罐内容推送到 AE2 网络,与 {@link Ae2OutputPusher} 物品推送并行工作。
 * <p>
 * <b>Task 21 批处理缓冲集成</b>:引入 {@link Ae2PendingBatchBuffer} 实现 10 tick 累积窗口,
 * 相同 AEFluidKey 跨 tick 合并,256× 加速 + 16 STACK(65536 并行)下 AE2 API 调用次数降低 ≥ 99.6%。
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
 *   <li><b>退避</b>:所有非空 tank 推送失败时进入指数退避窗口(1s→2s→4s→8s→16s→30s),
 *       基于 {@link System#nanoTime()} 墙钟单调时钟,窗口内跳过所有 AE2 存储操作,
 *       避免 JDTE 加速下 counter 退避失效(Task 5)</li>
 *   <li><b>JDTE M 自适应批量</b>:批量大小 = 100000 × M mB(M=1 时 100000, M=256 时 25.6M),
 *       配合 JDTE 加速倍数动态调整单批推送量,减少循环次数(Task 21 提升 100 倍)</li>
 *   <li><b>批量推送短路</b>:同一"游戏刻"内(counter 差值 &lt; M)跳过 AE2 API 调用,
 *       避免 JDTE 加速下 256× 重复推送同一 tank</li>
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

	private Ae2FluidPusher() {}

	/**
	 * 推送宿主所有流体罐内容到 AE2 网络(Task 13 多槽遍历 + Task 21 批处理缓冲 + spec 自适应)
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。
	 * <p>
	 * <b>Task 21 批处理流水线</b>(按顺序短路):
	 * <ol>
	 *   <li>深度退避:退避窗口内入口直接返回</li>
	 *   <li>TPS 自适应:严重卡顿时跳过,由 MEK Ejector 兜底</li>
	 *   <li>批量短路:同一"游戏刻"内(counter 差值 &lt; M)跳过</li>
	 *   <li>累积阶段:遍历流体罐,将 fluidKey+amount 累积到 batchBuffer</li>
	 *   <li>刷新检查:isRipe()(10 tick 窗口) OR shouldFlushNow(超 20 亿 mB) 时刷新</li>
	 *   <li>推送阶段:drain batchBuffer,对每个 key 调用 batchPush</li>
	 *   <li>shrink 阶段:按比例从匹配 tank 分块 shrinkStack</li>
	 * </ol>
	 *
	 * @param host 输出宿主(蜂箱/离心机方块实体)
	 */
	public static void pushFluids(IAe2OutputHostBase host) {
		// 1. 流体推送独立开关检查(与物品推送分离)
		if (!host.productivebeesgenesis$isFluidPushEnabled()) return;
		// 1.1 per-tile 流体输出开关检查(与全局配置 AND 关系)
		if (!host.productivebeesgenesis$isAeFluidOutputEnabled()) return;

		// 2. 深度退避检查(入口级别)— 退避期内跳过整个 pushFluids 路径(spec Change 3)
		//    基于 System.nanoTime() 墙钟单调时钟,不受 JDTE 加速影响(Task 5)
		long pushCounter = host.productivebeesgenesis$incrementFluidPushCallCounter();
		if (host.productivebeesgenesis$getFluidBackoff().shouldSkip(System.nanoTime())) return;

		// 3. TPS 自适应:TPS 严重下降时跳过推送,让出服务器资源(spec Change 5)
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return;
		long currentTick = level.getGameTime();
		double currentTps = ServerTickTimeMonitor.getInstance().getTps(currentTick);
		if (currentTps < 10.0) return;

		// 4. 获取网格节点 + 已连接的网格（Task 12：使用 holder 缓存，gridChanged 回调失效）
		IGrid grid = Ae2GridNodeManager.getCachedGrid(host);
		if (grid == null) return;

		// 5. 获取存储服务和 ME 存储（Task 12：使用 holder 缓存，避免重复 getService/getInventory）
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(host);
		if (meStorage == null) return;

		// 6. 复用物品推送的能量适配器(与物品推送共享同一实例)
		Ae2OutputPusher.ReusableBuffers buffers = Ae2OutputPusher.getReusableBuffers(host);
		IEnergySource energySource = buffers.getEnergyAdapter(host.productivebeesgenesis$getAe2EnergySource());

		// 7. JDTE M 自适应 + 批量推送短路
		TickAccelTracker tracker = host.productivebeesgenesis$getAe2StateHolder().getTickAccelTracker();
		int M = (tracker != null) ? tracker.getMultiplier() : 1;
		if (pushCounter - host.productivebeesgenesis$getLastFluidPushCounter() < M) return;
		host.productivebeesgenesis$updateLastFluidPushCounter(pushCounter);

		// 8. Task 21: 获取或创建批处理缓冲,递减成熟计数器
		Ae2PendingBatchBuffer batchBuffer = getOrCreatePendingBatchBuffer(host);
		batchBuffer.tick();

		// 9. Task 21: 累积阶段 — 遍历所有流体罐,将 fluidKey+amount 累积到 buffer
		int tankCount = host.fluidOutputTankCount();
		for (int i = 0; i < tankCount; i++) {
			IExtendedFluidTank tank = host.fluidOutputTank(i);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty()) continue;
			AEFluidKey fluidKey = AEFluidKey.of(stack);
			if (fluidKey == null) {
				continue;
			}
			long amount = stack.getAmount();
			if (amount <= 0) continue;
			batchBuffer.accumulate(fluidKey, amount);
		}

		// 10. Task 21: 刷新检查 — 成熟(10 tick 窗口) OR 累积量超阈值(20 亿 mB)时刷新
		boolean shouldFlush = batchBuffer.isRipe()
				|| batchBuffer.shouldFlushNow(batchBuffer.getTotalAmount(), Ae2PendingBatchBuffer.getFlushThresholdMb());
		if (!shouldFlush) return;

		// 11. Task 21: drain 并批量推送
		ConcurrentMap<AEFluidKey, Long> pendingMap = batchBuffer.drain();
		if (pendingMap.isEmpty()) return;

		boolean anySuccess = false;
		boolean allFailed = true;
		long totalRequested = 0L;

		for (Map.Entry<AEFluidKey, Long> entry : pendingMap.entrySet()) {
			AEFluidKey fluidKey = entry.getKey();
			long amount = entry.getValue();
			totalRequested += amount;

			try {
				// M10 修复：先 shrink 后 push，消除累积与 shrink 之间的竞态导致的流体复制
				// 原方案：先 push(amount) 后 shrink(inserted)
				//   问题：amount 是多 tick 累积量(每次读取 tank.getAmount 累加)，
				//         tank 当前实际量可能远小于 amount，导致 actualShrunk < inserted
				//         (AE2 收到多于 tank 扣除，流体复制)
				// 新方案：先 shrink(amount) 得到 actualShrunk(tank 实际可扣除量)，
				//         再 push(actualShrunk)，AE2 未接受的差额返回 tank
				//   守恒：tank 扣除 = AE2 接收 + 返回 tank，无复制无丢失
				long actualShrunk = shrinkStackSafely(host, fluidKey, amount, tankCount);
				if (actualShrunk <= 0) continue; // tank 已空，跳过推送

				long inserted = batchPush(energySource, meStorage, fluidKey, actualShrunk, M);

				if (inserted > 0) {
					anySuccess = true;
					allFailed = false;
				}

				// AE2 未接受的差额返回 tank（防止流体丢失）
				long leftover = actualShrunk - inserted;
				if (leftover > 0) {
					returnFluidToTanks(host, fluidKey, leftover, tankCount);
				}

				logPushResult(fluidKey, actualShrunk, inserted);
			} catch (Exception e) {
				handlePushException(e, fluidKey, amount);
			}
		}

		// 12. 退避触发:所有 key 都完全失败时进入退避;任一成功时重置退避
		Ae2PushBackoff fluidBackoff = host.productivebeesgenesis$getFluidBackoff();
		if (allFailed && totalRequested > 0) {
			boolean firstFailure = (fluidBackoff.getBackoffExponent() == 0);
			if (firstFailure) {
				fluidBackoff.recordFailureAggressive(System.nanoTime());
				LogThrottle.warn("ae2_fluid_long_backoff",
						"AE2 流体推送首次失败，触发 30s 长退避 totalRequested={}", totalRequested);
			} else {
				fluidBackoff.recordFailure(System.nanoTime());
				LogThrottle.warn("ae2_fluid_backoff",
						"AE2 流体推送失败,进入退避 指数={}, 结束时间戳={}",
						fluidBackoff.getBackoffExponent(), fluidBackoff.getBackoffEndNanos());
			}
		} else if (anySuccess) {
			fluidBackoff.recordSuccess();
		}
	}

	/**
	 * Task 21: 获取或创建批处理缓冲(懒初始化,存储在 Ae2OutputStateHolder 中)
	 * <br/>
	 * 字段类型为 Object 保持 AE2 依赖隔离,此处 instanceof 检查后强转。
	 * AE2 未安装时不会调用本方法(上层 isFluidPushEnabled 守卫)。
	 */
	private static Ae2PendingBatchBuffer getOrCreatePendingBatchBuffer(IAe2OutputHostBase host) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		Object obj = holder.getPendingBatchBuffer();
		if (obj instanceof Ae2PendingBatchBuffer buffer) return buffer;
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		holder.setPendingBatchBuffer(buffer);
		return buffer;
	}

	/**
	 * spec M 自适应分批推送核心循环(Task 21: 批大小提升 100 倍)
	 * <br/>
	 * <b>批量大小</b>:100000 × M mB(M=1 时 100000, M=256 时 25.6M),
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
	 * @param energySource  AE2 能量源
	 * @param meStorage     AE2 存储目标
	 * @param fluidKey      流体键
	 * @param amount        总推送量(mB)
	 * @param M             JDTE 加速倍数(1=无加速)
	 * @return 实际推送总量(mB);0 表示完全失败
	 */
	private static long batchPush(IEnergySource energySource, MEStorage meStorage,
			AEFluidKey fluidKey, long amount, int M) {
		// Task 21: 批大小从 1000×M 提升到 100000×M,减少 256× 加速下循环次数
		long batchSizeLimit = 100_000L * Math.max(1, M);
		long totalInserted = 0;
		long remaining = amount;

		while (remaining > 0) {
			long batchSize = Math.min(batchSizeLimit, remaining);
			long inserted = StorageHelper.poweredInsert(
					energySource, meStorage, fluidKey, batchSize, ACTION_SOURCE, Actionable.MODULATE);

			if (inserted <= 0) {
				// AE2 网络无法接收当前批次(容量不足/通道断开/能量不足/存储单元已满)
				break;
			}

			totalInserted = SaturatingMath.saturatingAdd(totalInserted, inserted);
			remaining -= inserted;

			// 若本次推送量小于请求量,说明 AE2 网络容量已满,停止循环
			if (inserted < batchSize) {
				break;
			}
		}

		return totalInserted;
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
			AEFluidKey tankKey = AEFluidKey.of(stack);
			if (!fluidKey.equals(tankKey)) continue;

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
	 * M10 修复：将 AE2 未接受的流体返回 tank，防止流体丢失
	 * <br/>
	 * 先 shrink 后 push 模式下，AE2 网络可能因容量不足只接受部分流体，
	 * 未接受的差额需要返回 tank，否则构成流体丢失。
	 * <p>
	 * 原理：用 {@link AEFluidKey#getFluid()} 获取 Fluid 引用，创建 FluidStack insert 到匹配 tank。
	 * 逆序遍历 tank（与 shrink 顺序相反），避免立即被下次推送再次消耗。
	 * 如果所有匹配 tank 都满，剩余量记录 WARN（极少发生，表示 AE2 网络和 tank 同时满）。
	 *
	 * @param host      输出宿主
	 * @param fluidKey  流体键（用于匹配 tank 和获取 Fluid）
	 * @param amount    返回量(mB)
	 * @param tankCount 流体罐总数
	 */
	private static void returnFluidToTanks(IAe2OutputHostBase host, AEFluidKey fluidKey,
			long amount, int tankCount) {
		if (amount <= 0) return;
		net.minecraft.world.level.material.Fluid fluid = fluidKey.getFluid();
		long remaining = amount;

		// 逆序遍历 tank（与 shrink 顺序相反），找到匹配 fluidKey 的 tank insert 返回
		for (int i = tankCount - 1; i >= 0 && remaining > 0; i--) {
			IExtendedFluidTank tank = host.fluidOutputTank(i);
			if (tank == null) continue;
			FluidStack current = tank.getFluid();
			// 匹配 fluidKey 的非空 tank，或空 tank（可能接受该 fluid）
			if (!current.isEmpty()) {
				AEFluidKey tankKey = AEFluidKey.of(current);
				if (!fluidKey.equals(tankKey)) continue;
			}
			// 创建返回 Stack（分块防 long→int 截断）
			int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
			FluidStack returnStack = new FluidStack(fluid, chunk);
			// insert 返回未插入的剩余 Stack，actualInserted = chunk - leftover.getAmount()
			FluidStack leftoverStack = tank.insert(returnStack,
					mekanism.api.Action.EXECUTE, mekanism.api.AutomationType.INTERNAL);
			long leftoverAmount = (leftoverStack == null || leftoverStack.isEmpty()) ? 0 : leftoverStack.getAmount();
			long actualInserted = chunk - leftoverAmount;
			remaining -= actualInserted;
			// tank 未接受任何量（已满或不接受该 fluid），继续尝试下一个
			if (actualInserted == 0 && chunk > 0) continue;
		}

		// 所有 tank 都满，剩余流体无法返回（极少发生）
		if (remaining > 0) {
			LogThrottle.warn("ae2_fluid_return_overflow",
					"AE2 未接受的流体返回 tank 失败(所有 tank 已满), 丢失 {} mB (5秒内仅首条)", remaining);
		}
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
