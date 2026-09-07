package com.ayoshiko.productivebeesgenesis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoundedBooleanMemo} 行为单测（纯 Java，无 Minecraft/AE2 运行时依赖）。
 * <p>
 * 该容器替换了 AE2 拉取热路径上三处「synchronized + 访问顺序 LinkedHashMap」缓存
 * （SMELTING 配方判定、标签过滤判定、蜜脾可处理性判定）。它必须同时满足：
 * 三态语义（未知 / false / true）、有界、热键不被顺序扫描冲掉、跨线程失效安全。
 */
class BoundedBooleanMemoTest {

	@Test
	@DisplayName("三态语义：未记录与记录为 false 必须可区分")
	void distinguishesUnknownFromRecordedFalse() {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(8);

		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("missing"));
		memo.remember("rejected", false);
		memo.remember("accepted", true);

		assertEquals(BoundedBooleanMemo.STATE_FALSE, memo.state("rejected"));
		assertEquals(BoundedBooleanMemo.STATE_TRUE, memo.state("accepted"));
		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("missing"));
	}

	@Test
	@DisplayName("remember 原样返回入参，便于在 return 语句链式使用")
	void rememberReturnsValueForChaining() {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(4);
		assertTrue(memo.remember("a", true));
		assertEquals(false, memo.remember("b", false));
	}

	@Test
	@DisplayName("上一代命中即提升回当前代，只有最冷的一代被整体丢弃")
	void promotesVictimHitsAndDropsColdestGeneration() {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(2);
		memo.remember("a", true);
		// 写满当前代 -> a、b 一起降为上一代
		memo.remember("b", false);
		memo.remember("c", true);

		// a 在上一代命中并被提升（这次提升又写满当前代，把 {c,a} 降为上一代）
		assertEquals(BoundedBooleanMemo.STATE_TRUE, memo.state("a"));
		// b 未被访问过，随最冷的一代整体丢弃
		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("b"));
		// 提升过的热键继续驻留
		assertEquals(BoundedBooleanMemo.STATE_TRUE, memo.state("a"));
		assertEquals(BoundedBooleanMemo.STATE_TRUE, memo.state("c"));
	}

	@Test
	@DisplayName("驻留条目数恒不超过单代上限的两倍")
	void residentSizeStaysBounded() {
		int perGeneration = 8;
		BoundedBooleanMemo<Integer> memo = new BoundedBooleanMemo<>(perGeneration);
		for (int i = 0; i < 1_000; i++) {
			memo.remember(i, i % 2 == 0);
			assertTrue(memo.size() <= perGeneration * 2,
					"写入第 " + i + " 条后驻留数 " + memo.size() + " 超出两代上限");
		}
		assertEquals(perGeneration, memo.maxEntriesPerGeneration());
	}

	@Test
	@DisplayName("requestClear 在下一次访问时生效，且不影响之后的新写入")
	void clearRequestIsAppliedLazilyOnNextAccess() {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(4);
		memo.remember("a", true);
		memo.requestClear();

		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("a"));
		memo.remember("b", true);
		assertEquals(BoundedBooleanMemo.STATE_TRUE, memo.state("b"));
		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("a"));
	}

	@Test
	@DisplayName("其它线程投递的失效请求必须被 tick 线程观察到（AE2 网格回调场景）")
	void clearRequestFromAnotherThreadIsObserved() throws Exception {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(4);
		memo.remember("a", true);

		Thread gridCallback = new Thread(memo::requestClear, "grid-callback");
		gridCallback.start();
		gridCallback.join();

		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("a"));
	}

	@Test
	@DisplayName("clearNow 立即清空两代")
	void clearNowDropsBothGenerations() {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(2);
		memo.remember("a", true);
		memo.remember("b", true);
		memo.remember("c", true);
		memo.clearNow();

		assertEquals(0, memo.size());
		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("a"));
		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state("c"));
	}

	@Test
	@DisplayName("null 键按未记录处理，不污染表也不抛异常")
	void nullKeysAreIgnored() {
		BoundedBooleanMemo<String> memo = new BoundedBooleanMemo<>(4);
		assertEquals(BoundedBooleanMemo.STATE_UNKNOWN, memo.state(null));
		assertTrue(memo.remember(null, true));
		assertEquals(0, memo.size());
	}

	@Test
	@DisplayName("非正上限必须立即拒绝")
	void rejectsNonPositiveCapacity() {
		assertThrows(IllegalArgumentException.class, () -> new BoundedBooleanMemo<>(0));
		assertThrows(IllegalArgumentException.class, () -> new BoundedBooleanMemo<>(-1));
	}
}
