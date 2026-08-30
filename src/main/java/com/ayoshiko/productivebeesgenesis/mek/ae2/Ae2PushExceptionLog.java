package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AE2 推送异常的统一记录（限流日志 + NPE/其他分级 + 中断状态恢复）
 * <p>
 * 从 {@code Ae2OutputPusher.handlePushException} 提为独立类：拆分后逐槽提交、批量提交与
 * 直推会话三处都要用同一套异常记录语义，集中在此避免各自复制。
 * <p>
 * M9 修复历史：原用原子计数器节流（1+1024n 触发），在 256× 加速下单 tick 可达 1024 次异常导致
 * 每 tick 刷屏；改用 {@link LogThrottle} 时间维度节流（5 秒内同 key 仅首条）。
 * NPE 用 error key、其他异常用 warn key，分别节流避免相互覆盖。
 * <p>
 * <b>线程安全</b>：累计计数用 {@link AtomicLong}；LogThrottle 内部为 ConcurrentHashMap。
 */
final class Ae2PushExceptionLog {

	/** 异常累计计数器 — 用于日志显示总次数（节流由 LogThrottle 时间维度处理） */
	private static final AtomicLong PUSH_EXCEPTION_COUNTER = new AtomicLong(0);

	private Ae2PushExceptionLog() {
	}

	static void handle(Exception e, int process, int slotIdx, ItemStack stack, int originalCount) {
		long count = PUSH_EXCEPTION_COUNTER.incrementAndGet();
		if (e instanceof NullPointerException) {
			LogThrottle.error("ae2_push_npe_exception",
					"AE2 推送 NPE 异常 (累计 {} 次,5秒内仅首条输出) - process={}, slotIdx={}, item={}: {}",
					count, process, slotIdx, stack.getItem(), e.toString());
		} else {
			LogThrottle.warn("ae2_push_exception",
					"AE2 推送异常 (累计 {} 次,5秒内仅首条输出) - process={}, slotIdx={}, item={}, count={}: {}",
					count, process, slotIdx, stack.getItem(), originalCount, e.toString());
		}
		if (e instanceof InterruptedException) Thread.currentThread().interrupt();
	}
}
