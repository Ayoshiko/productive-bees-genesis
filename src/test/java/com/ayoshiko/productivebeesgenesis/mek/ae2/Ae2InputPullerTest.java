package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class Ae2InputPullerTest {

	@Test
	void disablingSmeltingCompatSchedulesOneShotInputReturnCheck() {
		Ae2OutputStateHolder holder = new Ae2OutputStateHolder();

		holder.toggleSmeltingCompatEnabled();
		assertFalse(holder.isUnprocessableInputReturnCheckPending(), "开启熔炼兼容不应触发扫描");
		holder.toggleSmeltingCompatEnabled();
		assertTrue(holder.isUnprocessableInputReturnCheckPending(), "关闭熔炼兼容应安排一次扫描");
		holder.setUnprocessableInputReturnCheckPending(false);
		assertFalse(holder.isUnprocessableInputReturnCheckPending(), "完成扫描后应清除请求");
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
	void cursorMissingFromNetworkFallsBackToFullScan() {
		List<String> keys = List.of("a", "b");
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collect(out, keys, "zzz", 8, key -> true);
		// 游标键已不在网络中：主扫描不命中，回绕从头收集全部可拉取键
		assertEquals(List.of("a", "b"), out);
	}

	@Test
	void prioritizedScanAppliesReserveToSmeltingAndCombCandidates() {
		List<String> out = new ArrayList<>();
		List<String> prefixScratch = new ArrayList<>();
		Map<String, Long> stock = Map.of(
				"smelt_floor", 1_000L, "smelt_above", 1_100L,
				"comb_floor", 1_000L, "comb_above", 1_100L);

		Ae2CursorScan.collectPrioritized(out, prefixScratch,
				List.of("smelt_floor", "smelt_above"), List.of("comb_floor", "comb_above"),
				null, 4, key -> Ae2FilterPullPolicy.effectiveLimit(
						true, false, 0L, stock.get(key), false, 0L,
						false, false, true, 1_000L, Long.MAX_VALUE) > 0L);

		assertEquals(List.of("smelt_above", "comb_above"), out);
	}

	@Test
	void boundedCandidateScanPrioritizesSmeltingBeforeCombFallback() {
		List<String> out = new ArrayList<>();
		var seen = new java.util.HashSet<String>();

		int smeltingSelected = Ae2CursorScan.collectPrioritizedRotating(out, seen,
				List.of("smelt_a", "smelt_b", "smelt_c"), List.of("comb_a", "comb_b"),
				null, null, 2, key -> true);
		assertEquals(2, smeltingSelected);
		assertEquals(List.of("smelt_a", "smelt_b"), out,
				"当熔炼候选填满有界窗口时蜜脾不能抢在熔炼前");

		out.clear();
		seen.clear();
		smeltingSelected = Ae2CursorScan.collectPrioritizedRotating(out, seen,
				List.of("smelt_a", "smelt_b"), List.of("comb_a", "comb_b", "comb_c"),
				"smelt_a", "comb_a", 3, key -> true);
		assertEquals(2, smeltingSelected);
		assertEquals(List.of("smelt_b", "smelt_a", "comb_b"), out,
				"蜜脾只能补足熔炼候选未占用的窗口");
	}

	@Test
	void combFallbackCursorRotatesOnlyAfterNoSmeltingCandidateIsAccepted() {
		List<String> out = new ArrayList<>();
		var seen = new java.util.HashSet<String>();
		int smeltingSelected = Ae2CursorScan.collectPrioritizedRotating(out, seen,
				List.of("smelt_a", "smelt_b"), List.of("comb_a", "comb_b", "comb_c"),
				"smelt_a", "comb_b", 3, key -> key.startsWith("comb"));
		assertEquals(0, smeltingSelected);
		assertEquals(List.of("comb_c", "comb_a", "comb_b"), out,
				"熔炼键不再可用时蜜脾回退游标也应持续轮转");
	}

}
