package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link EnergySyncThrottler} 快照刷新策略测试
 * <br/>
 * 仅覆盖 tick 快照刷新逻辑（纯函数，不触及 Mekanism 类）；
 * installTracker 依赖 Mekanism 容器与 Mixin 环境，由游戏内回归验证
 * （识别失败时输出节流日志 energy_sync_throttle_install，可作为回归断言信号）。
 */
class EnergySyncThrottlerTest {

	@Test
	void firstTickRefreshesImmediately() {
		EnergySyncThrottler throttler = new EnergySyncThrottler();
		// 初始 lastSyncTick=MIN_VALUE → 首次调用立即刷新
		throttler.tick(0L, 1234L, 10000L);
		assertEquals(1234L, throttler.syncedEnergy());
	}

	@Test
	void smallChangeWithinIntervalDoesNotRefresh() {
		EnergySyncThrottler throttler = new EnergySyncThrottler();
		throttler.tick(0L, 5000L, 1_000_000L);
		// 变化 100（< 1% 阈值 10000）且间隔 < 5 tick → 不刷新
		throttler.tick(3L, 5100L, 1_000_000L);
		assertEquals(5000L, throttler.syncedEnergy());
		// 间隔到达 5 tick → 刷新
		throttler.tick(5L, 5100L, 1_000_000L);
		assertEquals(5100L, throttler.syncedEnergy());
	}

	@Test
	void largeChangeRefreshesImmediately() {
		EnergySyncThrottler throttler = new EnergySyncThrottler();
		throttler.tick(0L, 5000L, 1_000_000L);
		// 变化 15000（> 1% 阈值 10000）→ 立即刷新（首次填充场景）
		throttler.tick(1L, 20000L, 1_000_000L);
		assertEquals(20000L, throttler.syncedEnergy());
		// 下降方向的大变化同样立即刷新（能量耗尽场景）
		throttler.tick(2L, 5000L, 1_000_000L);
		assertEquals(5000L, throttler.syncedEnergy());
	}

	@Test
	void thresholdUsesMaxEnergyDenominator() {
		EnergySyncThrottler throttler = new EnergySyncThrottler();
		// 容量 1000 → 阈值 10：变化 11 立即刷新
		throttler.tick(0L, 0L, 1000L);
		throttler.tick(1L, 11L, 1000L);
		assertEquals(11L, throttler.syncedEnergy());
		// 容量 1000 → 变化 10（恰好等于阈值，不大于）不刷新
		EnergySyncThrottler throttler2 = new EnergySyncThrottler();
		throttler2.tick(0L, 0L, 1000L);
		throttler2.tick(1L, 10L, 1000L);
		assertEquals(0L, throttler2.syncedEnergy());
	}
}
