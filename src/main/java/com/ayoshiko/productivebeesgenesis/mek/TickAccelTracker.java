package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.level.Level;

/**
	 * 加速倍率自动检测器 — 自动检测方块实体在同一游戏刻内被调用的次数作为加速倍率 M
	 * <br/>
	 * 兼容所有加速模组（JDT、加速火把、Industrial Foregoing: Souls、JDTE、EAEP 等），无需检测具体模组。
	 * 被 IAe2InputHost 实现类持有（通过 Ae2OutputStateHolder），用于自适应节流 AE2 输入拉取逻辑。
	 * <p>
	 * <b>检测原理</b>：服务端单线程下，正常游戏刻内方块实体每 tick 仅被调用一次；
	 * 当安装加速模组时，加速模组会在同一 gameTick 内多次调用方块实体的 tick 方法，
	 * 通过统计同一 gameTick 内的调用次数即可得到加速倍率 M。
	 * <p>
	 * <b>线程安全</b>：本类不使用 synchronized 或 volatile，方块实体在服务端单线程执行，
	 * 跨线程访问无需同步。reset() 仅在主线程调用（服务器停止/维度切换时）。
	 * <p>
	 * <b>性能约束</b>：onTick() 单次调用开销必须 &lt; 10ns（仅 long == 比较 + int++），
	 * getMultiplier() 单次调用开销必须 &lt; 5ns（仅 Math.min/max）。
	 *
	 * @since 2.0.0
	 * @see com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost
	 * @see com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder
	 */
public class TickAccelTracker {

	/** 加速倍率上限 — 防止极端值导致 long/int 溢出 */
	private static final int MAX_MULTIPLIER = 1024;

	/** 上次被调用的游戏刻 — 用于检测同一游戏刻内多次调用 */
	private long lastGameTick = Long.MIN_VALUE;

	/** 当前游戏刻内已调用的次数 — 即加速倍率 M 的原始值 */
	private int callsInCurrentTick = 0;

	/** Completed call count from the previous real game tick, used by first-call batch work. */
	private int callsInPreviousTick = 1;

	/**
	 * 在方块实体 tick 时调用，统计同一游戏刻内的调用次数
	 * <br/>
	 * <b>性能约束（极重要）</b>：方法体仅允许 {@code long == 比较} + {@code int++}，
	 * 禁止任何其他方法调用、字段反射、字符串拼接、I/O 操作。
	 * 单次调用开销必须 &lt; 10ns。JIT 可将本方法内联为极少的字节码指令。
	 * <p>
	 * <b>逻辑</b>：当前 gameTick 与上次相同时 callsInCurrentTick++，
	 * 否则重置 lastGameTick 并将 callsInCurrentTick 置 1。
	 *
	 * @param level 当前世界（仅用于获取 getGameTime，不进行任何其他访问）
	 */
	public void onTick(Level level) {
		long currentTick = level.getGameTime();
		if (currentTick == lastGameTick) {
			callsInCurrentTick++;
		} else {
			if (lastGameTick != Long.MIN_VALUE && callsInCurrentTick > 0) {
				callsInPreviousTick = callsInCurrentTick;
			}
			lastGameTick = currentTick;
			callsInCurrentTick = 1;
		}
	}

	/**
	 * AE2 节点路径的 tick 钩子（Task 11 — JDTE 适配）
	 * <br/>
	 * JDTE 对 AE2 节点调用 {@code tickingRequest(node, 1)} 不经过方块的 {@code tick()} 方法，
	 * 因此 {@link #onTick} 不会被触发，导致 multiplier 检测失效。
	 * 本方法与 {@link #onTick} 共用同一计数器，供 AE2 节点路径调用。
	 * <p>
	 * <b>调用时机</b>：由 {@code IAe2InputHost} / {@code IAe2OutputHost} 的
	 * {@code productivebeesgenesis$onAe2Tick()} 默认方法在 AE2 网络事件触发时调用。
	 * <p>
	 * <b>性能约束</b>：与 {@link #onTick} 一致，单次调用开销 &lt; 10ns。
	 *
	 * @param level 当前世界（仅用于获取 getGameTime，不进行任何其他访问）
	 */
	public void onAe2Tick(Level level) {
		long currentTick = level.getGameTime();
		if (currentTick == lastGameTick) {
			callsInCurrentTick++;
		} else {
			if (lastGameTick != Long.MIN_VALUE && callsInCurrentTick > 0) {
				callsInPreviousTick = callsInCurrentTick;
			}
			lastGameTick = currentTick;
			callsInCurrentTick = 1;
		}
	}

	/**
	 * 获取加速倍率 M（已截断到 [1, 1024]）
	 * <br/>
	 * 返回值范围 [1, 1024]：
	 * <ul>
	 *   <li>1 表示无加速（每 tick 调用 1 次）</li>
	 *   <li>256 表示 256x 加速（每 tick 调用 256 次）</li>
	 *   <li>1024+ 被截断为 1024 防止溢出</li>
	 * </ul>
	 * 单次调用开销 &lt; 5ns（仅 Math.min/max）。
	 *
	 * @return 加速倍率 M，范围 [1, 1024]
	 */
	public int getMultiplier() {
		return Math.min(MAX_MULTIPLIER, Math.max(1, callsInCurrentTick));
	}

	/**
	 * Returns the last completed game tick's multiplier. Batch work runs on the first call of
	 * a new tick, before {@link #getMultiplier()} can observe the later accelerated calls.
	 */
	public int getPreviousTickMultiplier() {
		return Math.min(MAX_MULTIPLIER, Math.max(1, callsInPreviousTick));
	}

	/**
	 * 获取未截断的原始调用次数（仅用于调试/测试）
	 * <br/>
	 * 返回当前游戏刻内的实际调用次数，未经过 MAX_MULTIPLIER 截断。
	 * 生产代码应使用 {@link #getMultiplier()}。
	 *
	 * @return 当前游戏刻内的原始调用次数（可能大于 1024）
	 */
	public int getRawCallCount() {
		return callsInCurrentTick;
	}

	/**
	 * 重置追踪状态（用于服务器停止/维度切换）
	 * <br/>
	 * 将 lastGameTick 重置为 Long.MIN_VALUE，callsInCurrentTick 重置为 0。
	 * 下次 {@link #onTick} 调用会重新初始化计数。
	 */
	public void reset() {
		lastGameTick = Long.MIN_VALUE;
		callsInCurrentTick = 0;
		callsInPreviousTick = 1;
	}
}
