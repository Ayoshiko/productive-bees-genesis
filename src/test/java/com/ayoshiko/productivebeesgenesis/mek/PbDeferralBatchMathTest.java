package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 产物种类多于输出槽时的批量压缩数学。 */
class PbDeferralBatchMathTest {

	@Test
	@DisplayName("延迟量已在预算内时不压缩批量")
	void keepsBatchWhenDeferralFitsBudget() {
		assertEquals(1000, PbDeferralBatchMath.shrinkForDeferral(1000, 50, 64));
		assertEquals(1000, PbDeferralBatchMath.shrinkForDeferral(1000, 64, 64));
		assertEquals(1000, PbDeferralBatchMath.shrinkForDeferral(1000, 0, 64));
	}

	@Test
	@DisplayName("单次操作永不压缩 — 保证种类溢出时至少推进一次，机器不会卡死")
	void singleOperationNeverShrinks() {
		assertEquals(1, PbDeferralBatchMath.shrinkForDeferral(1, 4096, 64));
	}

	@Test
	@DisplayName("按延迟量线性比例估算批量")
	void shrinksProportionally() {
		assertEquals(64, PbDeferralBatchMath.shrinkForDeferral(1000, 1000, 64));
		assertEquals(268, PbDeferralBatchMath.shrinkForDeferral(65536, 1_000_000, 4096));
	}

	@Test
	@DisplayName("压缩结果严格递减且不小于 1，保证重试循环收敛")
	void alwaysConvergesTowardsOne() {
		assertEquals(1, PbDeferralBatchMath.shrinkForDeferral(2, 3, 2));
		assertEquals(1, PbDeferralBatchMath.shrinkForDeferral(1000, Integer.MAX_VALUE, 64));
		int trySize = 65536;
		for (int i = 0; i < 64 && trySize > 1; i++) {
			int shrunk = PbDeferralBatchMath.shrinkForDeferral(trySize, trySize * 8, 1);
			assertTrue(shrunk < trySize, "压缩结果必须严格小于原批量，否则重试循环不收敛");
			assertTrue(shrunk >= 1, "压缩结果不得小于 1");
			trySize = shrunk;
		}
		assertEquals(1, trySize);
	}
}
