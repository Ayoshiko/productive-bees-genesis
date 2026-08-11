package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
	 * 蜜蜂激活状态 O(1) 计数器
	 * <br/>
	 * 参考离心机 {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper} 的 CAS 模式，
	 * 使用 {@link AtomicIntegerArray} + CAS 守卫每槽位状态转换，workingCount 通过增量维护而非每 tick 重置+重计。
	 * <p>
	 * 设计原因：工厂版蜂箱槽位最多 20，旧实现每 tick 执行 {@code workingCount.set(0)} 后遍历全部槽位重计，
	 * 复杂度 O(n)。改为 CAS 增量维护后，稳态下绝大多数槽位 CAS 失败为 no-op，仅状态转换时递增/递减。
	 * <p>
	 * 线程安全：CAS 保证「比较+设置」原子性，避免多线程重复递增/递减计数器。
	 */
final class ApiaryBeeActivationCounter {

	/** 每槽位激活状态（0=未工作, 1=工作） */
	private final AtomicIntegerArray beeActiveStates;

	/** 当前工作中的蜜蜂数量 — 通过 CAS 增量维护，供 active 状态管理与声音触发使用 */
	private final AtomicInteger workingCount = new AtomicInteger(0);

	/**
	 * 构造计数器
	 *
	 * @param beeSlotCount 蜜蜂槽位数量
	 */
	ApiaryBeeActivationCounter(int beeSlotCount) {
		this.beeActiveStates = new AtomicIntegerArray(beeSlotCount);
	}

	/**
	 * 蜜蜂开始工作时调用（递增计数器）
	 * <br/>
	 * 使用 CAS 0→1 守卫状态转换：仅状态从「未工作」变为「工作」时才递增计数器，
	 * 保证连续工作的蜜蜂每 tick 不会重复递增。CAS 失败（已为工作状态）为 no-op。
	 *
	 * @param slotIndex 蜜蜂槽位索引
	 */
	void onBeeActivated(int slotIndex) {
		if (slotIndex < 0 || slotIndex >= beeActiveStates.length()) return;
		if (beeActiveStates.compareAndSet(slotIndex, 0, 1)) {
			workingCount.incrementAndGet();
		}
	}

	/**
	 * 蜜蜂停止工作时调用（递减计数器）
	 * <br/>
	 * 使用 CAS 1→0 守卫状态转换：仅状态从「工作」变为「未工作」时才递减计数器，
	 * 保证连续未工作的槽位每 tick 不会重复递减。CAS 失败（已为未工作状态）为 no-op。
	 *
	 * @param slotIndex 蜜蜂槽位索引
	 */
	void onBeeDeactivated(int slotIndex) {
		if (slotIndex < 0 || slotIndex >= beeActiveStates.length()) return;
		if (beeActiveStates.compareAndSet(slotIndex, 1, 0)) {
			workingCount.decrementAndGet();
		}
	}

	/**
	 * 是否有任意蜜蜂正在工作 — O(1) 计数器读取
	 *
	 * @return true 如果有工作中的蜜蜂
	 */
	boolean hasActiveBee() {
		return workingCount.get() > 0;
	}

	/**
	 * 当前工作中的蜜蜂数量 — 供声音处理器等需要具体数值的场景使用
	 *
	 * @return 工作中的蜜蜂数量
	 */
	int getWorkingCount() {
		return workingCount.get();
	}

	/**
	 * 全部蜜蜂失活 — 红石关闭等场景调用
	 * <br/>
	 * CAS 守卫保证幂等：已失活槽位 CAS 1→0 失败为 no-op，不会重复递减。
	 */
	void deactivateAll() {
		for (int i = 0; i < beeActiveStates.length(); i++) {
			onBeeDeactivated(i);
		}
	}
}
