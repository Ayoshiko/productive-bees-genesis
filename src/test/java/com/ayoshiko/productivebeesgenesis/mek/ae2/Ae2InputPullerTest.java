package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class Ae2InputPullerTest {

	@Test
	void accelerationShortensConfiguredIntervalWithoutDroppingBelowOneGameTick() {
		assertEquals(10, Ae2PullFairnessPolicy.effectiveInterval(10, 1));
		assertEquals(5, Ae2PullFairnessPolicy.effectiveInterval(10, 2));
		assertEquals(1, Ae2PullFairnessPolicy.effectiveInterval(10, 256));
	}

	@Test
	void coalescedBatchMultiplierDoesNotDependOnRepeatedTickerCalls() {
		assertEquals(1024, Ae2PullFairnessPolicy.resolveAccelerationMultiplier(1024, 1, 1));
		assertEquals(256, Ae2PullFairnessPolicy.resolveAccelerationMultiplier(0, 1, 256));
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
		assertTrue(state.tryAcquireAdditionalLocalFluidDrain(100, 2));
		assertTrue(state.tryAcquireAdditionalLocalFluidDrain(100, 2));
		assertFalse(state.tryAcquireAdditionalLocalFluidDrain(100, 2));
		assertTrue(state.tryAcquireAdditionalLocalFluidDrain(101, 2));
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

	@Test
	void mappedScanKeepsWraparoundStorageBounded() {
		List<String> keys = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) keys.add("k" + i);
		List<String> out = new ArrayList<>();
		List<String> prefixScratch = new ArrayList<>();

		Ae2CursorScan.collectMapped(out, prefixScratch, keys, "k500", 3,
				key -> key, key -> true);

		assertEquals(List.of("k500", "k501", "k502"), out);
		assertEquals(3, prefixScratch.size());
	}

	@Test
	void mappedScanStopsFilteringOnceWrapPrefixIsFull() {
		List<String> keys = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) keys.add("k" + i);
		List<String> out = new ArrayList<>();
		List<String> prefixScratch = new ArrayList<>();
		AtomicInteger predicateCalls = new AtomicInteger();

		Ae2CursorScan.collectMapped(out, prefixScratch, keys, "k900", 3,
				key -> key, key -> {
					predicateCalls.incrementAndGet();
					return true;
				});

		assertEquals(List.of("k900", "k901", "k902"), out);
		assertEquals(6, predicateCalls.get());
	}

	@Test
	void mappedScanSkipsUnrelatedSourceTypes() {
		List<Object> keys = List.of(1, "a", 2, "b", "c");
		List<String> out = new ArrayList<>();
		List<String> prefixScratch = new ArrayList<>();

		Ae2CursorScan.collectMapped(out, prefixScratch, keys, "b", 3,
				key -> key instanceof String string ? string : null, key -> true);

		assertEquals(List.of("b", "c", "a"), out);
	}

	@Test
	void prioritizedScanFillsSmeltingCandidatesBeforeCombFallback() {
		List<String> out = new ArrayList<>();
		List<String> prefixScratch = new ArrayList<>();

		Ae2CursorScan.collectPrioritized(out, prefixScratch,
				List.of("smelt_a", "smelt_b"), List.of("comb_a", "comb_b"),
				null, 3, key -> true);

		assertEquals(List.of("smelt_a", "smelt_b", "comb_a"), out);
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
	void prioritizedScanKeepsSingleSmeltingCandidateAtItsCursor() {
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collectPrioritized(out, new ArrayList<>(),
				List.of("raw_gold"), List.of(), "raw_gold", 2, key -> true);
		assertEquals(List.of("raw_gold"), out);
	}

	@Test
	void prioritizedScanKeepsSingleCombCandidateAtItsCursor() {
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collectPrioritized(out, new ArrayList<>(),
				List.of(), List.of("gold_comb"), "gold_comb", 2, key -> true);
		assertEquals(List.of("gold_comb"), out);
	}

	@Test
	void prioritizedScanFindsAllowedKeyAcrossPriorityGroups() {
		List<String> out = new ArrayList<>();
		Ae2CursorScan.collectPrioritized(out, new ArrayList<>(),
				List.of("raw_gold", "raw_iron"), List.of("gold_comb"), "gold_comb", 2,
				key -> key.equals("raw_gold"));
		assertEquals(List.of("raw_gold"), out);
	}

	@Test
	void directEntryCursorRotatesBoundedWhitelistWithoutStarvation() {
		List<String> entries = List.of("raw_gold", "raw_iron", "raw_copper");
		assertEquals(0, Ae2CursorScan.cursorStartIndex(entries, null, key -> key));
		assertEquals(1, Ae2CursorScan.cursorStartIndex(entries, "raw_iron", key -> key));
		assertEquals(0, Ae2CursorScan.cursorStartIndex(entries, "missing", key -> key));
	}

	@Test
	void laneBudgetSplitsSlotsAcrossCompetingTypesAndNeverStarves() {
		// 单类型不设限：吞吐与旧实现完全一致
		assertEquals(Ae2InputLaneFairness.UNLIMITED_LANES,
				Ae2InputLaneFairness.emptyLaneBudget(32, 1));
		// 32 槽 4 类型 → 每类型 8 条车道
		assertEquals(8, Ae2InputLaneFairness.emptyLaneBudget(32, 4));
		// 槽少于类型数时向上取整为 1，否则少数类型永远拿不到槽（饿死）
		assertEquals(1, Ae2InputLaneFairness.emptyLaneBudget(3, 8));
		assertEquals(0, Ae2InputLaneFairness.emptyLaneBudget(0, 4));
	}

	@Test
	void typeQuotaShareDividesRateBudgetAndKeepsAtLeastOne() {
		assertEquals(1_024L, Ae2InputLaneFairness.typeQuotaShare(1_024L, 1));
		assertEquals(256L, Ae2InputLaneFairness.typeQuotaShare(1_024L, 4));
		// 向上取整：3 个类型分 10 个额度，每个至少 4，不会有类型分到 0
		assertEquals(4L, Ae2InputLaneFairness.typeQuotaShare(10L, 3));
		assertEquals(1L, Ae2InputLaneFairness.typeQuotaShare(2L, 8));
		assertEquals(0L, Ae2InputLaneFairness.typeQuotaShare(0L, 4));
	}

	@Test
	void firstCapacityPassAlwaysRunsForSingleAndMultipleTypes() {
		assertTrue(Ae2InputLaneFairness.shouldRunPass(0, false));
		assertTrue(Ae2InputLaneFairness.shouldRunPass(0, true));
		assertFalse(Ae2InputLaneFairness.shouldRunPass(1, false));
		assertTrue(Ae2InputLaneFairness.shouldRunPass(1, true));
	}

	@Test
	void pullerRunsFairPassBeforeUnboundedFillPass() throws Exception {
		String source = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java"));
		// 公平轮 + 补齐轮的双轮结构，且补齐轮只在被截断时才跑（避免重复 SIMULATE 探测）
		assertTrue(source.contains("int passes = typeCount > 1 ? 2 : 1;"));
		assertTrue(source.contains("Ae2InputLaneFairness.shouldRunPass(pass, fairPassTruncated)"));
		// 空槽车道上限与速率份额上限都只在公平轮生效
		assertTrue(source.contains("Ae2InputLaneFairness.emptyLaneBudget(processCount, typeCount)"));
		assertTrue(source.contains("Ae2InputLaneFairness.typeQuotaShare(normalQuota, typeCount)"));
		assertTrue(source.contains("if (slotCapacity > 0L && fairPass && slot.getStack().isEmpty())"));
	}

}
