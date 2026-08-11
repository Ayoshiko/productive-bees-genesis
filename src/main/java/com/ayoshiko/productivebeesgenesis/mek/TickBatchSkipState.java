package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.level.Level;

/**
	 * Per-tile 批量收获状态管理器 — 封装 skipPb 的"延迟一 tick"策略。
	 * <br/>
	 * 由 AbstractMekCentrifugeFactory 持有实例，所有工厂变体（原版/ME/EME）共用。
	 * 逻辑镜像 ApiaryTickHandler.skipBeeProcessing 和 MekCentrifugeTickHandler.skipPb。
	 * <p>
	 * 策略说明：
	 * <ul>
	 *   <li>multiplier == 1（本 gameTick 第一次调用）：使用上一 gameTick 的 lastTickMultiplier 作为批量倍率，返回 false（执行 PB）</li>
	 *   <li>multiplier &gt; 1（本 gameTick 后续调用）：更新 lastTickMultiplier，返回 true（跳过 PB）</li>
	 * </ul>
	 *
	 * @author ayoshiko
	 * @since 2.0.0
	 * @see TickAccelTracker
	 * @see AbstractMekCentrifugeFactory#productivebeesgenesis$getTickBatchSkipState()
	 */
public class TickBatchSkipState {

	/** 上一 gameTick 的最终加速倍率 — 用于本 gameTick 第一次调用时的批量倍率（延迟一 tick 策略） */
	private volatile int lastTickMultiplier = 1;

	/** 本 gameTick 第 1 次 tick 时的批量倍率 — 由 getBatchMultiplier() 读取 */
	private volatile int batchMultiplierForCurrentTick = 1;

	/** 上次处理的 gameTick — 调试用，用于检测同一 gameTick 内多次调用 */
	private volatile long lastProcessedGameTick = Long.MIN_VALUE;

	/**
	 * 判断本 gameTick 是否应跳过 PB 处理（"延迟一 tick"策略核心）。
	 * <br/>
	 * 调用时机：由工厂 onUpdateServer 在每次 tick 调用。
	 * <p>
	 * 逻辑（镜像 ApiaryTickHandler 第 105-120 行）：
	 * <ul>
	 *   <li>tracker == null / level == null / level.isClientSide：返回 false（不跳过）</li>
	 *   <li>调用 tracker.onTick(level) 更新计数器</li>
	 *   <li>multiplier &gt; 1：lastTickMultiplier = multiplier; return true（跳过 PB）</li>
	 *   <li>multiplier == 1：batchMultiplierForCurrentTick = lastTickMultiplier; lastTickMultiplier = 1; return false（执行 PB）</li>
	 * </ul>
	 *
	 * @param tracker Tick 加速追踪器（可为 null）
	 * @param level   当前世界（可为 null）
	 * @return true 表示跳过 PB 处理；false 表示执行 PB 处理
	 */
	public boolean shouldSkipPb(TickAccelTracker tracker, Level level) {
		if (tracker == null || level == null || level.isClientSide()) {
			return false;
		}
		tracker.onTick(level);
		int multiplier = tracker.getMultiplier();
		if (multiplier > 1) {
			// 本 gameTick 后续调用：持续更新 lastTickMultiplier，跳过 PB
			lastTickMultiplier = multiplier;
			lastProcessedGameTick = level.getGameTime();
			return true;
		}
		// multiplier == 1：本 gameTick 第一次调用
		// 先用旧值作为本 gameTick 的批量倍率，然后重置（本 gameTick 后续调用会更新）
		batchMultiplierForCurrentTick = lastTickMultiplier;
		lastTickMultiplier = 1;
		lastProcessedGameTick = level.getGameTime();
		return false;
	}

	/**
	 * 获取本 gameTick 第 1 次 tick 时的批量倍率。
	 * <br/>
	 * 仅在 {@link #shouldSkipPb} 返回 false 后调用才有意义。
	 *
	 * @return 批量倍率（范围 [1, 1024]，与 {@link TickAccelTracker#getMultiplier()} 一致）
	 */
	public int getBatchMultiplier() {
		return batchMultiplierForCurrentTick;
	}

	/**
	 * 重置所有字段（用于 tile 卸载 / 服务器停止 / 维度切换）。
	 * <br/>
	 * 将 lastTickMultiplier、batchMultiplierForCurrentTick 重置为 1，
	 * lastProcessedGameTick 重置为 Long.MIN_VALUE。
	 */
	public void reset() {
		lastTickMultiplier = 1;
		batchMultiplierForCurrentTick = 1;
		lastProcessedGameTick = Long.MIN_VALUE;
	}
}
