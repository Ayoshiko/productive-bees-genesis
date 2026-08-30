package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * operationsPerTick 每游戏刻记忆化的行为与接线断言。
 * <p>
 * 背景：spark XnLugba3Cw 里 {@code TileEntityEMExtraMekCentrifugeFactory.operationsPerTick}
 * 自耗 776ms / 2.59%，为本模组首位热点 —— 它被 CachedRecipe 当作 baselineMaxOperations
 * 供应商持有，JDTE 1024 倍加速下每真实刻被调用上千次。
 */
class OperationsPerTickCacheTest {

	@Test
	void sameGameTickComputesOnce() {
		OperationsPerTickCache cache = new OperationsPerTickCache();
		AtomicInteger calls = new AtomicInteger();
		for (int i = 0; i < 1024; i++) {
			assertEquals(256, cache.get(100L, () -> {
				calls.incrementAndGet();
				return 256;
			}));
		}
		assertEquals(1, calls.get());
	}

	@Test
	void newGameTickRecomputes() {
		OperationsPerTickCache cache = new OperationsPerTickCache();
		AtomicInteger calls = new AtomicInteger();
		cache.get(100L, () -> { calls.incrementAndGet(); return 1; });
		cache.get(101L, () -> { calls.incrementAndGet(); return 2; });
		assertEquals(2, calls.get());
	}

	@Test
	void invalidateTakesEffectWithinTheSameGameTick() {
		OperationsPerTickCache cache = new OperationsPerTickCache();
		assertEquals(1, cache.get(100L, () -> 1));
		// 升级安装/卸载在同一刻内改变操作数，必须立即可见
		cache.invalidate();
		assertEquals(64, cache.get(100L, () -> 64));
	}

	@Test
	void unavailableLevelBypassesCaching() {
		OperationsPerTickCache cache = new OperationsPerTickCache();
		AtomicInteger calls = new AtomicInteger();
		cache.get(Long.MIN_VALUE, () -> { calls.incrementAndGet(); return 1; });
		cache.get(Long.MIN_VALUE, () -> { calls.incrementAndGet(); return 1; });
		assertEquals(2, calls.get());
	}

	@Test
	void allFourCentrifugeVariantsUseTheCacheAndInvalidateOnUpgradeChange() throws Exception {
		String prefix = "src/main/java/com/ayoshiko/productivebeesgenesis/";
		for (String path : new String[] {
				prefix + "compat/emextras/TileEntityEMExtraMekCentrifugeFactory.java",
				prefix + "compat/mekanism_extras/TileEntityExtraMekCentrifugeFactory.java",
				prefix + "mek/AbstractMekCentrifugeFactory.java",
				prefix + "mek/TileEntityMekCentrifuge.java" }) {
			String source = Files.readString(Path.of(path));
			assertTrue(source.contains("new OperationsPerTickCache()"), "field missing: " + path);
			// recalculateUpgrades 必须失效缓存，否则装/卸升级后一整刻仍用旧并行数
			assertTrue(source.contains("PerTickCache.invalidate()"), "invalidate missing: " + path);
		}
	}
}
