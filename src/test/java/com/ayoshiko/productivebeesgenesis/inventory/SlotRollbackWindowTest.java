package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部退回窗口的时间判定测试。
 * <p>
 * 覆盖 {@link SlotRollbackWindow#isWithinWindow} 的全部边界：这类判定的失效方向是
 * 「错误放行」而不是「错误拒绝」，所以未初始化与时间回拨都必须判为无效。
 */
class SlotRollbackWindowTest {

	private static final long UNSET = ServerTickClock.UNSET;

	@Test
	void acceptsSameTick() {
		assertTrue(SlotRollbackWindow.isWithinWindow(100L, 100L));
	}

	@Test
	void acceptsExactlyAtWindowEdge() {
		long recorded = 100L;
		assertTrue(SlotRollbackWindow.isWithinWindow(recorded, recorded + SlotRollbackWindow.WINDOW_TICKS));
	}

	@Test
	void rejectsOneTickBeyondWindow() {
		long recorded = 100L;
		assertFalse(SlotRollbackWindow.isWithinWindow(recorded,
				recorded + SlotRollbackWindow.WINDOW_TICKS + 1L));
	}

	@Test
	void rejectsWhenRecordedTickUnset() {
		assertFalse(SlotRollbackWindow.isWithinWindow(UNSET, 500L));
	}

	@Test
	void rejectsWhenNowUnset() {
		assertFalse(SlotRollbackWindow.isWithinWindow(100L, UNSET));
	}

	@Test
	void rejectsClockRollback() {
		assertFalse(SlotRollbackWindow.isWithinWindow(500L, 100L));
	}
}
