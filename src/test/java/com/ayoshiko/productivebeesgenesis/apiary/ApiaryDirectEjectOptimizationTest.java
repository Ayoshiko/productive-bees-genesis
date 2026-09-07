package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 蜂箱→离心机直连链路的优化接线校验。
 * <br/>
 * 关注两处高频路径的正确性前提：
 * <ol>
 *   <li>输入槽预扫描同刻复用，但任何绕过 {@code updateSlotAfterTransfer} 的写入都要失效缓存；</li>
 *   <li>可处理性掩码缓存已独立成类，且深比较前有 O(1) 预筛。</li>
 * </ol>
 */
class ApiaryDirectEjectOptimizationTest {

	private static final String SRC = "src/main/java/com/ayoshiko/productivebeesgenesis/apiary/";

	private static String read(String fileName) throws Exception {
		return Files.readString(Path.of(SRC + fileName));
	}

	@Test
	@DisplayName("预扫描同刻复用：hold 判定走 ensureInputScan，批量弹出走 refreshInputScan")
	void inputScanIsReusedWithinOneTick() throws Exception {
		String targets = read("ApiaryDirectEjectTargets.java");
		assertTrue(targets.contains("void ensureInputScan(long gameTick)")
						&& targets.contains("if (!scanDirty && lastScanTick == gameTick) return;"),
				"同刻且未脏时必须跳过重复预扫描");
		assertTrue(targets.contains("void refreshInputScan(long gameTick)"),
				"批量弹出起点仍需无条件重扫，拿到当刻最新状态");
		assertTrue(targets.contains("void markScanDirty()"), "需提供失效入口");

		String handler = read("ApiaryDirectEjectHandler.java");
		assertTrue(handler.contains("target.ensureInputScan(gameTick);"),
				"canAnyTargetAccept 是每产物调用，必须复用同刻预扫描");
		assertTrue(handler.contains("target.refreshInputScan(gameTick);"),
				"tryDirectEject 每批开始必须重扫");
		assertFalse(handler.contains("target.preScanInputSlots()"),
				"旧的无条件预扫描入口不应再被调用");
	}

	@Test
	@DisplayName("绕过预扫描同步的写入必须失效缓存，否则同刻 hold 判定会读到旧空间")
	void bypassingWritesInvalidateScan() throws Exception {
		String handler = read("ApiaryDirectEjectHandler.java");

		int transferMethodIndex = handler.indexOf("private void transferStackToTargetInputs(");
		assertTrue(transferMethodIndex > 0, "产出直连直写方法必须存在");
		int dirtyIndex = handler.indexOf("target.markScanDirty();", transferMethodIndex);
		int loopIndex = handler.indexOf("for (int i = 0; i < slotCount", transferMethodIndex);
		assertTrue(dirtyIndex > 0 && dirtyIndex < loopIndex,
				"产出直连在写入前就要失效预扫描");

		assertTrue(handler.contains("if (transferred > 0) {")
						&& handler.indexOf("target.markScanDirty();",
								handler.indexOf("tryRedistributeToExternalSlots")) > 0,
				"缓冲区直写输入槽成功后也要失效预扫描");
	}

	@Test
	@DisplayName("可处理性掩码：独立缓存类 + 组件 hash 预筛 + 拓扑/配方双失效")
	void acceptanceCacheIsExtractedAndCheap() throws Exception {
		String cache = read("ApiaryCentrifugeAcceptanceCache.java");
		assertTrue(cache.contains("if (componentHashes[i] != componentHash) continue;"),
				"深比较前必须先比 int hash：PB 蜜脾共用同一 Item，isSameItemSameComponents 无法快速失败");
		assertTrue(cache.contains("targetList != targetsRef")
						&& cache.contains("recipeVersion != ProductiveBeesGenesis.RECIPE_VERSION.get()"),
				"拓扑变化与配方重载都要失效");
		assertTrue(cache.contains("catch (Exception | LinkageError e)"),
				"跨方块实体调用需按不可处理降级，不阻断蜂箱 tick");

		String handler = read("ApiaryDirectEjectHandler.java");
		assertTrue(handler.contains("return acceptanceCache.maskFor(stack, targetList);"),
				"直连处理器应委托缓存类，自身不再维护掩码数组");
	}
}
