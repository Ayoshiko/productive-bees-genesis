package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 离心机加工进度条显示限流器（纯客户端渲染辅助）
 * <br/>
 * 256× tick 加速下真实进度会在 0↔1 之间高频跳变，直接渲染会闪烁，
 * 让用户误以为机器卡顿。本类对进度值做帧间平滑：
 * <ul>
 *   <li>上升限速：满条至少需要 {@link #MIN_FILL_SECONDS} 秒，处理再快也保持可读的填充动画</li>
 *   <li>下降快速：新循环开始时以较快速度回落，避免旧进度残留造成"卡在满条"的错觉</li>
 * </ul>
 * 仅用于显示，不修改服务端真实进度。状态按方块实体弱引用持有，GUI 关闭后自动回收。
 * 渲染线程单线程访问，无需同步。
 */
public final class ProgressDisplaySmoother {

	/** 满条所需最小时间（秒）— 0.35s 填充完整进度条 */
	private static final double MIN_FILL_SECONDS = 0.35;

	/** 进度回落速度（单位/秒）— 新循环开始后约 0.33s 归零 */
	private static final double RESET_SPEED_PER_SECOND = 3.0;

	/** 单帧最小步进 — 防止高帧率下 dt 过小导致进度几乎不动 */
	private static final double MIN_STEP_PER_FRAME = 0.015;

	/** 预分配进度槽位（EME 最高 18 进程，留余量） */
	private static final int DEFAULT_PROCESS_CAPACITY = 24;

	private static final Map<BlockEntity, State> STATES = new WeakHashMap<>();

	private static final class State {
		double[] displayed = new double[DEFAULT_PROCESS_CAPACITY];
		long[] lastUpdateNanos = new long[DEFAULT_PROCESS_CAPACITY];
	}

	private ProgressDisplaySmoother() {
	}

	/**
	 * 对指定进程的进度值做帧间平滑。
	 *
	 * @param tile    方块实体（缓存状态 key）
	 * @param process 进程索引
	 * @param target  真实进度（0.0~1.0，来自服务端同步）
	 * @return 用于渲染的平滑进度
	 */
	public static double smooth(BlockEntity tile, int process, double target) {
		if (tile == null || process < 0) {
			return target;
		}
		State state = STATES.computeIfAbsent(tile, t -> new State());
		if (process >= state.displayed.length) {
			int newLength = Math.max(process + 1, state.displayed.length * 2);
			double[] newDisplayed = new double[newLength];
			long[] newLastUpdate = new long[newLength];
			System.arraycopy(state.displayed, 0, newDisplayed, 0, state.displayed.length);
			System.arraycopy(state.lastUpdateNanos, 0, newLastUpdate, 0, state.lastUpdateNanos.length);
			state.displayed = newDisplayed;
			state.lastUpdateNanos = newLastUpdate;
		}

		double clamped = Math.max(0.0, Math.min(1.0, target));
		long now = System.nanoTime();
		long last = state.lastUpdateNanos[process];
		double displayed = state.displayed[process];
		state.lastUpdateNanos[process] = now;
		if (last == 0L) {
			// 首次渲染直接采用目标值，避免打开 GUI 瞬间从 0 缓慢爬升
			state.displayed[process] = clamped;
			return clamped;
		}

		double dt = (now - last) / 1_000_000_000.0;
		if (dt <= 0.0) {
			return displayed;
		}
		if (clamped >= displayed) {
			// 上升：限速填充
			double maxDelta = Math.max(MIN_STEP_PER_FRAME, dt / MIN_FILL_SECONDS);
			displayed = Math.min(clamped, displayed + maxDelta);
		} else {
			// 下降：快速回落（新循环）
			double maxDelta = Math.max(MIN_STEP_PER_FRAME, dt * RESET_SPEED_PER_SECOND);
			displayed = Math.max(clamped, displayed - maxDelta);
		}
		state.displayed[process] = displayed;
		return displayed;
	}
}
