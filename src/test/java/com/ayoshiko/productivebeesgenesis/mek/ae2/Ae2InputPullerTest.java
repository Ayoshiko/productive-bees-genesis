package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class Ae2InputPullerTest {

	@Test
	void accelerationShortensConfiguredIntervalWithoutDroppingBelowOneGameTick() {
		assertEquals(10, Ae2PullFairnessPolicy.effectiveInterval(10, 1));
		assertEquals(5, Ae2PullFairnessPolicy.effectiveInterval(10, 2));
		assertEquals(1, Ae2PullFairnessPolicy.effectiveInterval(10, 256));
	}

	@Test
	void highAccelerationUsesBoundedPerSlotQuota() {
		assertEquals(8_192L, Ae2PullFairnessPolicy.perSlotQuota(1_024L, 256, 32));
		assertEquals(131_072L, Ae2PullFairnessPolicy.perSlotQuota(16_384L, 256, 32));
		assertEquals(64L, Ae2PullFairnessPolicy.perSlotQuota(1_024L, 1, 32));
	}

	@Test
	void pushStateRotatesSlotsAndDeduplicatesAcceleratedSubTicks() {
		Ae2PushStateHolder state = new Ae2PushStateHolder();
		assertEquals(0, state.getAndAdvanceInputSlotRotation(3));
		assertEquals(1, state.getAndAdvanceInputSlotRotation(3));
		assertEquals(2, state.getAndAdvanceInputSlotRotation(3));
		assertEquals(0, state.getAndAdvanceInputSlotRotation(3));

		assertTrue(state.tryStartItemPush(100));
		assertFalse(state.tryStartItemPush(100));
		assertTrue(state.tryStartItemPush(101));
		assertTrue(state.tryStartFluidPush(100));
		assertFalse(state.tryStartFluidPush(100));
	}


	@Test
	void singleCombTypeNetworkKeepsPullingAfterFirstCursorIsSet() {
		// 回归修复：单蜜脾类型网络中，游标指向唯一键时下一轮仍必须收集到该键。
		// 旧实现命中游标键后 continue 跳过、回绕又在游标键处 break，导致 pullList 恒空、后续不再拉取。
		List<String> keys = List.of("productivebees:iron");
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, keys, "productivebees:iron", 4, key -> true);
		assertEquals(List.of("productivebees:iron"), out);
	}

	@Test
	void cursorRotationCollectsCursorItselfThenWrapAroundPrefix() {
		List<String> keys = List.of("a", "b", "c");
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, keys, "b", 8, key -> true);
		// 主扫描：b（游标自身放行）+ c；回绕：a（b 已在列表中，遇 b 停止）
		assertEquals(List.of("b", "c", "a"), out);
	}

	@Test
	void nullCursorCollectsEverythingInOrder() {
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, List.of("a", "b"), null, 8, key -> true);
		assertEquals(List.of("a", "b"), out);
	}

	@Test
	void scanBudgetRespectsMaxTypesCap() {
		List<String> keys = List.of("a", "b", "c", "d", "e");
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, keys, "c", 2, key -> true);
		// 主扫描收集 c（游标自身）+ d 即达上限，回绕不再执行
		assertEquals(List.of("c", "d"), out);
	}

	@Test
	void rejectedKeysAreSkippedWithoutDisturbingCursorPositioning() {
		List<String> keys = List.of("a", "b", "c");
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, keys, "b", 8, key -> !key.equals("a"));
		assertEquals(List.of("b", "c"), out);
	}

	@Test
	void cursorMissingFromNetworkFallsBackToFullScan() {
		List<String> keys = List.of("a", "b");
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, keys, "zzz", 8, key -> true);
		// 游标键已不在网络中：主扫描不命中，回绕从头收集全部可拉取键
		assertEquals(List.of("a", "b"), out);
	}
}
