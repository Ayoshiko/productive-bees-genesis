package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 退避窗口断言使用抖动无关的确定性边界：
 * recordFailure 的窗口 = 名义值 × (0.75 ~ 1.25)（±25% 相位抖动，打散多机重试时刻）。
 * 因此仅断言"确定跳过 < 0.75×"与"确定恢复 >= 1.25×"两个稳定区间。
 */
class Ae2PushBackoffTest {

	@Test
	void failuresUseProgressiveWindowsAndSuccessResetsImmediately() {
		Ae2PushBackoff backoff = new Ae2PushBackoff();
		long start = 10_000L;

		// 首次失败：名义 50ms，实际窗口 (37.5ms, 62.5ms)
		backoff.recordFailure(start);
		assertTrue(backoff.shouldSkip(start + 37_499_999L));
		assertFalse(backoff.shouldSkip(start + 62_500_000L));

		long secondFailure = start + 62_500_000L;
		backoff.recordFailure(secondFailure);
		// 第二次失败：名义 100ms，实际窗口 (75ms, 125ms)
		assertTrue(backoff.shouldSkip(secondFailure + 74_999_999L));
		assertFalse(backoff.shouldSkip(secondFailure + 125_000_000L));

		backoff.recordSuccess();
		assertFalse(backoff.shouldSkip(secondFailure));

		backoff.recordFailure(secondFailure);
		assertTrue(backoff.shouldSkip(secondFailure + 37_499_999L));
		assertFalse(backoff.shouldSkip(secondFailure + 62_500_000L));
	}

	@Test
	void aggressiveFailureDoesNotJumpToLongBackoff() {
		Ae2PushBackoff backoff = new Ae2PushBackoff();
		long start = 20_000L;

		// 首次激进失败与普通失败同窗口（名义 50ms，实际 (37.5ms, 62.5ms)），不跳长退避
		backoff.recordFailureAggressive(start);
		assertTrue(backoff.shouldSkip(start + 37_499_999L));
		assertFalse(backoff.shouldSkip(start + 62_500_000L));
	}

	@Test
	void consecutiveSlowInsertFailuresEscalateToMaxWindow() {
		// 慢 insert 场景（病态网络：insert 成功但遍历昂贵 5-10ms）：调用方在慢 insert
		// 轮次只调 recordFailure、不调 recordSuccess 复位。指数必须能一路爬到 1s 封顶 —
		// 若封顶不可达（如指数逻辑被改坏），稳态卡短窗口 = 每 50ms 一次完整网络遍历。
		Ae2PushBackoff backoff = new Ae2PushBackoff();
		long now = 1_000_000L;
		// 6 次失败（名义窗口 50/100/200/400/800ms→1s）：每次跨过上一窗口后重试又失败
		for (int i = 0; i < 6; i++) {
			backoff.recordFailure(now);
			now += 1_100_000_000L;
		}
		// 第 6 次失败后：名义 1s，实际 (750ms, 1250ms)
		long lastFailure = now - 1_100_000_000L;
		assertTrue(backoff.shouldSkip(lastFailure + 749_999_999L));
		assertFalse(backoff.shouldSkip(lastFailure + 1_250_000_000L));

		// 封顶后继续失败窗口不再增长（名义仍 1s）
		long capFailure = lastFailure + 1_250_000_000L;
		backoff.recordFailure(capFailure);
		assertTrue(backoff.shouldSkip(capFailure + 749_999_999L));
		assertFalse(backoff.shouldSkip(capFailure + 1_250_000_000L));

		// 网络恢复：一次健康成功立即复位（慢 insert 修复不牺牲正常吞吐恢复速度）
		backoff.recordSuccess();
		assertFalse(backoff.shouldSkip(capFailure + 1L));
	}
}
