package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本机不可处理的蜜脾」不得进入 AE2 拉取候选 —— 接线校验（源码级断言，无需 Minecraft 运行时）。
 * <p>
 * 背景：整合包中大量蜜脾只有蜂、没有 PB 离心配方（需别的模组机器处理，如 chemlib 元素蜜脾
 * 走氧化机/分解机）。本整合包实测蜂种并集 489 种，其中 139 种没有任何离心配方。
 * 这些键靠 {@code CombFuzzyMatcher.isCombItem} 的 Item 引用判定仍算「蜜脾」，
 * 一旦进入候选窗口就会每轮空转（toStack + 逐槽 validator + 配方查找）却永远插不进槽，
 * 且因为从不进入 {@code pullBatchForType}，per-key 退避永远不触发 ——
 * 表现为玩家报告的「反复拉取导致严重卡顿，卡但不掉 TPS」。
 * <p>
 * 这些接线点被改掉后行为依旧「正确」（只是又开始空转），纯逻辑单测无法发现，故用源码断言钉住。
 */
class Ae2CombProcessableWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("蜜脾分类经过可处理性门，被拒直接 REJECTED 而不下落 SMELTING")
	void combGateRejectsInsteadOfFallingThroughToSmelting() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2InputCandidatePolicy.java");
		assertTrue(source.contains("interface CombProcessGate"), "蜜脾门必须是独立的函数式抽象");
		assertTrue(source.contains("CombProcessGate.ALLOW_ALL"), "必须提供零开销放行门");

		String normalized = source.replaceAll("\\s+", " ");
		// 关键语义：蜜脾被拒 → REJECTED。若改成 break/fall-through 走到 SMELTING 分支，
		// modularbees 等为 c:honeycombs 注册的熔炼配方会重新抢占 PB 输入并产出错误结果。
		assertTrue(normalized.contains("return combGate == null || combGate.canProcess(key) "
				+ "? CandidateKind.COMB : CandidateKind.REJECTED;"),
				"蜜脾未通过可处理性门时必须直接 REJECTED，不得下落 SMELTING 分支");

		int combIndex = source.indexOf("CombFuzzyMatcher.isCombItem(key)");
		int smeltIndex = source.indexOf("smeltingCache.contains(level, key)");
		assertTrue(combIndex >= 0 && smeltIndex >= 0 && combIndex < smeltIndex,
				"蜜脾判定必须仍在 SMELTING 判定之前");
	}

	@Test
	@DisplayName("三条候选收集路径都接可处理性门，缓存与其他 per-host 缓存同生命周期")
	void pullerWiresCombGateOnEveryCandidatePath() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		String normalized = puller.replaceAll("\\s+", " ");
		assertTrue(normalized.contains("buffers.combProcessableCache.canProcess(host, key, smeltingEnabled)"),
				"可处理性判定必须走 per-host 缓存，不得每次现算");
		// directOnly 精确白名单快路径、直连 network-stock 探测路径、10 tick 候选刷新扫描路径。
		// 少一条就会出现「一条路径挡住了、另一条又把它拉回来」的不一致。
		int gated = normalized.split("smeltingInputCache, tagGate, combGate\\)", -1).length - 1;
		assertTrue(gated >= 3,
				"可处理性门必须覆盖全部候选收集路径，当前带 combGate 的 classify 调用点数=" + gated);

		String buffers = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PushBuffers.java");
		assertTrue(buffers.contains(
				"final Ae2CombProcessableCache combProcessableCache = new Ae2CombProcessableCache()"),
				"缓存必须与其他 per-tile 缓冲同生命周期");
		assertTrue(buffers.contains("combProcessableCache.clear()"),
				"网格拓扑变化必须清空判定缓存，避免跨网络复用陈旧结果");
	}

	@Test
	@DisplayName("判定缓存按 Item+bee_type 记忆、双条件失效、异常按放行、每轮限速")
	void cacheKeyInvalidationAndFailSafeSemantics() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2CombProcessableCache.java");
		// 不能用 AEItemKey 当键：其 equals 走 ItemStack.isSameItemSameComponents，
		// spark vVh8WfPCN3 实测 map 查找 self 时间即 508ms。
		assertTrue(cache.contains("record CombIdentity(Item item, @Nullable ResourceLocation beeType)"),
				"缓存键必须是 (Item, bee_type)，不得用 AEItemKey");
		assertTrue(cache.contains("CombFuzzyMatcher.getBeeType(key)"),
				"bee_type 必须走既有直读实现，不得重复解析组件");
		// 固定蜜脾（ghostly/milky/powdery）与原版蜜脾无 bee_type 组件，其可处理性依赖熔炼兼容开关
		assertTrue(cache.contains("observedRecipeVersion == currentVersion "
				+ "&& observedSmeltingEnabled == smeltingEnabled"),
				"配方版本与熔炼兼容开关必须同时作为失效条件");
		// 判定失败按「可处理」放行：误判为假会永久饿死合法蜜脾（机器停摆），代价远大于空转
		assertTrue(cache.contains("result = true;"), "判定异常必须按可处理放行，不得饿死合法输入");
		assertTrue(cache.contains("MAX_ENTRIES"), "必须有条目上限，防止内存无界增长");
		assertTrue(cache.contains("if (remainingProbes <= 0) return true;"),
				"未缓存判定必须限速，防止配方重载后首轮刷新把全量遍历堆在同一 tick");

		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("buffers.combProcessableCache.beginProbeWindow()"),
				"每次拉取必须重置限速窗口，否则额度用尽后永不恢复");
	}

	@Test
	@DisplayName("宿主可处理性判定委托既有离心配方语义，未知实现者默认放行")
	void hostGateDelegatesToExistingRecipeSemantics() throws Exception {
		String host = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/IAe2InputHost.java");
		String normalized = host.replaceAll("\\s+", " ");
		// default 方法 = 零 mixin 改动：ME/EME 工厂的 AE2 契约由 mixin 注入，只实现 3 个抽象方法
		assertTrue(normalized.contains("default boolean productivebeesgenesis$canProcessInput(ItemStack stack)"),
				"必须是 default 方法，否则两个 compat mixin 与第三方实现者全部编译失败");
		assertTrue(normalized.contains("return !(this instanceof IMekCentrifugeTile tile) "
				+ "|| tile.productivebeesgenesis$isValidInput(stack);"),
				"判定必须委托 IMekCentrifugeTile 既有语义（万象/PB 配方/熔炼兼容），未知实现者放行");
	}

	@Test
	@DisplayName("validator 永久拒绝的键会记 per-key 退避，与「暂时没位置」分开归因")
	void validatorRejectionFeedsKeyBackoff() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		String normalized = puller.replaceAll("\\s+", " ");
		assertTrue(normalized.contains("entry.validatorRejected = true;"),
				"槽位 validator 拒绝必须单独标记，不能与槽满混同");
		assertTrue(normalized.contains("else if (entry.validatorRejected) { "
				+ "keyBackoff.recordFailure(entry.key, System.nanoTime()); }"),
				"这类键从不进入 pullBatchForType，必须在容量规划阶段归因，否则退避永不触发");
		assertTrue(normalized.contains("entry.validatorRejected = false;"),
				"每轮容量规划必须重新归因，避免公平轮结论被补齐轮沿用");
	}
}
