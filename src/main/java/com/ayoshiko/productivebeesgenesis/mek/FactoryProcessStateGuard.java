package com.ayoshiko.productivebeesgenesis.mek;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
	 * 工厂进程激活状态守卫
	 * <br/>
	 * 管理 PB 进程的 CAS 激活状态转换和计数器，确保并发场景下计数器的正确性。
	 * 从 {@link MekCentrifugeFactoryHelper} 拆分，遵循单一职责原则：
	 * Helper 聚焦配方处理逻辑，本类专注原子状态转换。
	 */
public final class FactoryProcessStateGuard {

	private FactoryProcessStateGuard() {
	}

	/**
	 * 进程激活时调用（递增计数器）
	 * <br/>
	 * 使用 CAS 状态守卫防止重复递增：仅状态 false→true 时递增计数器。
	 * <p>
	 * 线程安全：原 boolean[] + check-then-act 在并发场景下可能多个线程同时通过 if 检查后递增计数器，
	 * 导致 activeProcessCount 超过实际激活进程数。改为 {@link AtomicIntegerArray}
	 * + CAS 模式，CAS 保证「比较 + 设置」原子性，只有一个线程能成功推进状态 0→1 并递增计数器。
	 *
	 * @param process             进程索引
	 * @param pbActiveStates      PB激活状态数组（每进程一个，0=false/1=true）
	 * @param activeProcessCount  激活进程计数器
	 */
	public static void onProcessActivated(int process, @NotNull AtomicIntegerArray pbActiveStates,
			@NotNull AtomicInteger activeProcessCount) {
		// CAS 0→1：成功表示本线程是状态转换的获胜者，负责递增计数器
		if (pbActiveStates.compareAndSet(process, 0, 1)) {
			activeProcessCount.incrementAndGet();
		}
	}

	/**
	 * 进程失活时调用（递减计数器）
	 * <br/>
	 * 使用 CAS 状态守卫防止重复递减：仅状态 true→false 时递减计数器。
	 *
	 * @param process             进程索引
	 * @param pbActiveStates      PB激活状态数组（每进程一个，0=false/1=true）
	 * @param activeProcessCount  激活进程计数器
	 */
	public static void onProcessDeactivated(int process, @NotNull AtomicIntegerArray pbActiveStates,
											@NotNull AtomicInteger activeProcessCount) {
		// CAS 1→0：成功表示本线程是状态转换的获胜者，负责递减计数器
		if (pbActiveStates.compareAndSet(process, 1, 0)) {
			activeProcessCount.decrementAndGet();
		}
	}

	/**
	 * 检查是否有任意PB进程激活
	 * <br/>
	 * O(1) 计数器读取，替代 O(processes) 遍历。
	 *
	 * @param activeProcessCount 激活进程计数器
	 * @return true 如果有激活的进程
	 */
	public static boolean hasActiveProcess(@NotNull AtomicInteger activeProcessCount) {
		return activeProcessCount.get() > 0;
	}
}
