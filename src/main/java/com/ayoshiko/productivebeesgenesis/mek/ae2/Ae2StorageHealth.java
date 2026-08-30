package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * AE2 存储操作健康度判定 — 退避决策的唯一阈值来源。
 * <p>
 * <b>为什么与 {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS}(0.5ms) 分开</b>：
 * 0.5ms 是「预算记账」阈值，只累计超出部分，用于跨机器钳制同 tick 的尖峰总量，
 * 单次超过它完全正常。若把它同时当成「故障信号」去驱动退避，则专用服务器上
 * 一次正常的 comb 抽取（大网络 / 多存储总线 / 跨维度，0.6-3ms）会被判为失败：
 * per-key 退避拉黑该 key，整机 returnBackoff 指数一路涨到 1 秒上限并锁死入口，
 * 表现为「机器在线、加工与产物回送正常，但完全不从 AE2 拉取」。
 * <p>
 * 真正需要退避的是催生这套机制的病态形态：EnderDrives 型 WAL 在主线程
 * {@code FileChannelImpl.force} fsync，单次 5-10ms。故障阈值取 5ms。
 * <p>
 * 线程模型：纯函数，无状态。
 */
final class Ae2StorageHealth {

	/** 病态存储操作阈值（纳秒）— 单次超过 5ms 才视为需要退避的故障。 */
	static final long PATHOLOGICAL_OPERATION_NANOS = 5_000_000L;

	private Ae2StorageHealth() {
	}

	/** 单次存储操作是否慢到需要触发退避（区别于预算记账的 0.5ms 阈值）。 */
	static boolean isPathological(long costNanos) {
		return costNanos > PATHOLOGICAL_OPERATION_NANOS;
	}
}
