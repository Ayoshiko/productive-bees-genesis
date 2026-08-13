package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.level.Level;

/**
 * Per-tile 批量收获状态管理器 — 封装 skipPb 的"虚拟 tick 银行 + 每 tick 预算"策略。
 * <br/>
 * 由 {@link AbstractMekCentrifugeFactory} 持有实例，所有工厂变体（原版/ME/EME）共用；
 * 基础机（{@code MekCentrifugeTickHandler}）与蜂箱（{@code ApiaryTickHandler}）各自持有一个实例。
 * 逻辑镜像 ApiaryTickHandler.skipBeeProcessing 和 MekCentrifugeTickHandler.skipPb。
 * <p>
 * 策略说明：
 * <ul>
 *   <li><b>同 gameTick 去重</b>：ticker（Minecraft 方块实体 tick）与 JDTE
 *       {@code CoalescedAcceleratedMachine.flushAcceleratedTicks} 共享门控，
 *       每个真实 gameTick 只允许一个入口执行完整批量（第一次调用者执行，其余跳过），
 *       避免 JDTE 时序不确定时双倍处理（super/SMELTING 双跑、PB 双跑、能量双扣）。</li>
 *   <li><b>共享预算</b>：ticker 与 flush 同 gameTick 共用一份批量预算（
 *       {@code budgetConsumedThisTick} 累计扣减），保证每 gameTick 总处理量不超过
 *       {@code maxBatchTicksPerTick}（默认 256），与配置文档语义一致。</li>
 *   <li><b>multiplier == 1</b>（本 gameTick 第一次 ticker 调用）：从虚拟 tick 银行取出批量预算，执行完整处理</li>
 *   <li><b>multiplier &gt; 1</b>（旧式加速模组同 gameTick 多次调用 ticker）：仅入账（onTick 内完成），跳过 PB</li>
 * </ul>
 * 线程安全：所有字段仅由服务端 tick 线程访问（方块实体 tick 链），无需 volatile/同步；
 * reset() 仅在主线程调用（tile 卸载/服务器停止/维度切换）。
 *
 * @author ayoshiko
 * @since 2.0.0
 * @see TickAccelTracker
 * @see AbstractMekCentrifugeFactory#productivebeesgenesis$getTickBatchSkipState()
 */
public final class TickBatchSkipState {

	/** 本 gameTick 已由某入口完整处理过的标记（ticker/flush 共享门控） */
	private long lastHandledGameTick = Long.MIN_VALUE;

	/** 本 gameTick 已消耗的批量预算（ticker/flush 共享扣减） */
	private int budgetConsumedThisTick = 0;

	/** 预算记账对应的 gameTick（跨 tick 自动重置 consumed） */
	private long budgetTrackedGameTick = Long.MIN_VALUE;

	/** 本 gameTick 第 1 次完整处理时的批量倍率 — 由 getBatchMultiplier() 读取 */
	private int batchMultiplierForCurrentTick = 1;

	/** tick 入口动作判定结果 */
	public enum TickAction {
		/** 本调用应执行完整处理（本 gameTick 第一个入口，已从银行取款） */
		PROCESS,
		/** 本调用应跳过 PB（旧式加速器同 tick 重复调用，仅保留 super） */
		SKIP,
		/** 本 gameTick 已由其他入口完整处理过，应完全跳过（含 super，避免双跑） */
		ALREADY_HANDLED
	}

	/**
	 * 判定本 gameTick 的 tick 入口动作（ticker 专用入口）。
	 * <br/>
	 * 调用时机：由各 tick 处理器在每次 ticker 调用时执行。
	 * <p>
	 * 逻辑：
	 * <ul>
	 *   <li>tracker/level 无效或客户端：返回 PROCESS（不启用批量跳过）</li>
	 *   <li>调用 tracker.onTick(level) 更新真实 ticker 计数（AE2 网格 tick 不参与计数，见 {@link TickAccelTracker#onAe2Tick}）</li>
	 *   <li>门控被占用（同 gameTick 已由 flush 处理）：返回 ALREADY_HANDLED，调用方完全跳过</li>
	 *   <li>multiplier &gt; 1：返回 SKIP，调用方仅保留 super</li>
	 *   <li>multiplier == 1：从共享预算中取款，返回 PROCESS</li>
	 * </ul>
	 *
	 * @param tracker Tick 加速追踪器（可为 null）
	 * @param level   当前世界（可为 null）
	 * @return 本 tick 应执行的动作
	 */
	public TickAction decideAction(TickAccelTracker tracker, Level level) {
		if (tracker == null || level == null || level.isClientSide()) {
			return TickAction.PROCESS;
		}
		tracker.onTick(level);
		long gameTick = level.getGameTime();
		if (!tryBeginGameTick(gameTick)) {
			// 同 gameTick 已由 flush（JDTE）完整处理，完全跳过避免双跑
			return TickAction.ALREADY_HANDLED;
		}
		if (tracker.getMultiplier() > 1) {
			// 旧式加速器同 gameTick 多次调用 ticker：首次调用已 PROCESS，本次跳过 PB
			return TickAction.SKIP;
		}
		batchMultiplierForCurrentTick = takeSharedBatchMultiplier(tracker, gameTick);
		return TickAction.PROCESS;
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine.flushAcceleratedTicks} 入口的同一 gameTick 门控。
	 * <br/>
	 * 返回 true 表示本调用是本 gameTick 第一个完整处理者，调用方应执行完整批量
	 * （先 {@link #takeSharedBatchMultiplier} 取款）；返回 false 表示 ticker 已处理过，应跳过。
	 */
	public boolean tryBeginGameTick(long gameTick) {
		if (lastHandledGameTick == gameTick) {
			return false;
		}
		lastHandledGameTick = gameTick;
		// 新 gameTick 重置共享预算记账
		budgetConsumedThisTick = 0;
		budgetTrackedGameTick = gameTick;
		return true;
	}

	/**
	 * 从虚拟 tick 银行取本 gameTick 的共享批量预算。
	 * <br/>
	 * ticker 与 flush 共用同一份预算：本 gameTick 已消耗的量会被扣除，
	 * 保证每 gameTick 总处理量不超过 {@link TickAccelTracker#getBatchBudget} 的上限。
	 *
	 * @param tracker  加速追踪器
	 * @param gameTick 当前游戏刻
	 * @return 本入口应批量执行的虚拟 tick 数（至少 1）
	 */
	public int takeSharedBatchMultiplier(TickAccelTracker tracker, long gameTick) {
		if (budgetTrackedGameTick != gameTick) {
			budgetTrackedGameTick = gameTick;
			budgetConsumedThisTick = 0;
		}
		int budget = tracker.getBatchBudget(gameTick);
		int remaining = Math.max(1, budget - budgetConsumedThisTick);
		int taken = tracker.takeBatchTicks(remaining);
		budgetConsumedThisTick += taken;
		batchMultiplierForCurrentTick = taken;
		return taken;
	}

	/**
	 * 获取本 gameTick 第 1 次完整处理时的批量倍率。
	 * <br/>
	 * 仅在 {@link #decideAction} 返回 PROCESS 或 flush 取款后调用才有意义。
	 *
	 * @return 批量倍率（范围 [1, 1024]，与 {@link TickAccelTracker#getMultiplier()} 一致）
	 */
	public int getBatchMultiplier() {
		return batchMultiplierForCurrentTick;
	}

}
