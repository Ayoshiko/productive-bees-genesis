package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签过滤在 AE2 拉取链路上的接线校验（源码级断言，无需 Minecraft 运行时）。
 * <br/>
 * 目的：这些接线点一旦被后续重构改掉，标签过滤会静默失效（拉取照旧、无报错），
 * 单靠纯逻辑单测无法发现，故用源码断言把关键调用点钉住。
 */
class Ae2TagFilterWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("smelt 分类经过标签门，且蜜脾判定仍在标签门之前")
	void candidatePolicyAppliesTagGateAfterCombAndRecipe() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2InputCandidatePolicy.java");
		int combIndex = source.indexOf("CombFuzzyMatcher.isCombItem(key)");
		int recipeIndex = source.indexOf("smeltingCache.contains(level, key)");
		int gateIndex = source.indexOf("tagGate.allows(key)");
		assertTrue(combIndex >= 0 && recipeIndex >= 0 && gateIndex >= 0);
		// 顺序保证：蜜脾 -> SMELTING 配方 -> 标签门；标签门只能收窄 smelt 输入
		assertTrue(combIndex < recipeIndex);
		assertTrue(recipeIndex < gateIndex);
		assertTrue(source.contains("SmeltingTagGate.ALLOW_ALL"));
	}

	@Test
	@DisplayName("拉取器未配置标签过滤时使用零开销 ALLOW_ALL，并把代号纳入候选缓存失效条件")
	void pullerWiresTagGateAndGeneration() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(source.contains("Ae2TagFilter tagFilter = holder.getAeTagFilter()"));
		assertTrue(source.contains("Ae2InputCandidatePolicy.SmeltingTagGate.ALLOW_ALL"));
		assertTrue(source.contains("buffers.tagFilterCache.allows(tagFilter, key)"));
		// 表达式变更必须立刻重建候选列表，而不是等 10 tick 刷新窗口
		assertTrue(source.contains("recipeVersion, smeltingEnabled, tagGeneration)"));
		assertTrue(source.replaceAll("\\s+", " ")
				.contains("markScanCandidateRefresh(availableStacks, currentTick, recipeVersion, "
						+ "smeltingEnabled, tagGeneration)"));
	}

	@Test
	@DisplayName("标签过滤表达式随方块实体持久化，旧存档回退空表达式")
	void perTileCodecPersistsTagFilter() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2PerTileStateNbtCodec.java");
		assertTrue(source.contains("holder.getAeTagFilter().save(tagFilterTag)"));
		assertTrue(source.contains("tag.contains(Ae2NbtKeys.NBT_KEY_AE_INPUT_TAG_FILTER)"));
		assertTrue(source.contains("holder.getAeTagFilter().reset()"));
	}

	@Test
	@DisplayName("网格拓扑变化会清空标签判定缓存，避免跨网络复用陈旧结果")
	void gridChangeClearsTagFilterCache() throws Exception {
		// 缓冲区已从 Ae2OutputPusher 内部类拆为顶层 Ae2PushBuffers（原文件超 500 行阈值）
		String buffers = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PushBuffers.java");
		assertTrue(buffers.contains("tagFilterCache.clear()"));
		assertTrue(buffers.contains("final Ae2TagFilterCache tagFilterCache = new Ae2TagFilterCache()"));
		// 网格回调必须真正调用失效入口，否则缓存永不清理
		String holder = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2OutputStateHolder.java");
		assertTrue(holder.contains("buffers.invalidateScanCandidateCache()"));
	}

	@Test
	@DisplayName("服务端 handler 校验表达式长度、容器一致性、交互距离与限频")
	void serverHandlerValidatesRequest() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/network/"
				+ "Ae2TagFilterPayloadHandlers.java");
		assertTrue(source.contains("NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH"));
		assertTrue(source.contains("Ae2PayloadHandlers.validateContainerMatch"));
		assertTrue(source.contains("PayloadRateLimiter.tryAccept"));
		assertTrue(source.contains("NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ"));
	}

	@Test
	@DisplayName("候选视图并入方块标签，且不使用已废弃的 builtInRegistryHolder")
	void candidateIncludesBlockTags() throws Exception {
		// 候选面定义已抽到 Ae2ItemTagView，供服务端判定与客户端选取器共用（避免两处枚举逻辑漂移）
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2ItemTagView.java");
		// c:storage_blocks/honeycombs 这类 id 只在方块标签树上，不并入会「表达式看着对却拉不到」
		assertTrue(source.contains("BuiltInRegistries.BLOCK.wrapAsHolder"));
		assertTrue(source.contains("item instanceof BlockItem"));
		// builtInRegistryHolder() 已废弃，javac 警告会让 gradle 构建失败；注释里提及不算调用
		assertTrue(!source.contains(".builtInRegistryHolder()"));
		// 判定缓存必须走同一候选面，不得自建一份
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2TagFilterCache.java");
		assertTrue(cache.contains("Ae2ItemTagView.candidateOf(key.getItem())"));
	}

	@Test
	@DisplayName("等级升级（工厂安装器）随升级数据保留标签过滤表达式")
	void upgradeDataCarriesTagFilter() throws Exception {
		String data = read("src/main/java/com/ayoshiko/productivebeesgenesis/apiary/CentrifugeUpgradeData.java");
		assertTrue(data.contains("public final CompoundTag aeTagFilterNbt"));
		String helper = read("src/main/java/com/ayoshiko/productivebeesgenesis/apiary/"
				+ "CentrifugeUpgradeDataHelper.java");
		// 标签过滤不依赖 AE2 类，必须在 isAe2Loaded 守卫之外无条件保存/恢复
		assertTrue(helper.contains("ae2StateHolder.getAeTagFilter().save(aeTagFilterNbt)"));
		assertTrue(helper.contains("ae2StateHolder.getAeTagFilter().load(data.aeTagFilterNbt.copy())"));
		assertTrue(helper.contains("ae2StateHolder.getAeTagFilter().reset()"));
	}

	@Test
	@DisplayName("标签过滤窗口放行物品栏点击，样品槽才能接收背包物品")
	void tagFilterWindowAllowsInventoryClicks() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/"
				+ "GuiAeInputTagFilterConfig.java");
		// InteractionStrategy.NONE 会让 GuiWindow.mouseClicked 无条件返回 true 吞掉窗口外点击，
		// 玩家因此拿不起背包物品，样品槽只能靠 JEI 拖拽填充
		assertTrue(source.contains("this.interactionStrategy = InteractionStrategy.CONTAINER"));
		assertTrue(!source.contains("InteractionStrategy.NONE"));
		String slot = read("src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/"
				+ "TagSampleSlotWidget.java");
		// 三条取样途径：光标持物、JEI 拖拽、空手取主手物品（窗口遮住物品栏时的兜底）
		assertTrue(slot.contains("gui().getCarriedItem()"));
		assertTrue(slot.contains("getGhostHandler()"));
		assertTrue(slot.contains("player.getMainHandItem()"));
	}

	@Test
	@DisplayName("选取器候选按表达式增量刷新，不在每帧无条件重算")
	void pickerRefreshIsIncremental() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/"
				+ "TagPickerWidget.java");
		// tick 是每帧路径：表达式未变必须整轮跳过，否则每帧扫描表达式 + 重建 tooltip 列表
		assertTrue(source.contains("if (blacklistTarget == lastTargetWasBlacklist "
				+ "&& expression.equals(lastExpression)) return;"));
		// 可加候选须剔除已写入项（精妙存储同语义），可删候选来自表达式词法扫描
		String state = read("src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/"
				+ "TagPickerState.java");
		assertTrue(state.contains("TagExpressionText.containsLiteral(expression, tag)"));
		assertTrue(state.contains("TagExpressionText.listLiterals(expression)"));
	}

	@Test
	@DisplayName("保留库存对经过标签过滤的 smelt 候选同样生效：三条候选路径 + 唯一抽取点")
	void reserveAppliesToTagFilteredSmeltingCandidates() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");

		// 1) 三条候选收集路径都带 tagGate 分类：directOnly 精确白名单快路径、
		//    直连 network-stock 探测路径、缓存库存优先级扫描路径。
		//    少一条就会出现「标签过滤放行了但另一条路径绕过」的不一致。
		String normalized = puller.replaceAll("\\s+", " ");
		int gateUses = normalized.split(
				"Ae2InputCandidatePolicy\\.classify\\( level, ", -1).length - 1;
		assertTrue(gateUses >= 3,
				"标签门必须覆盖全部候选路径，当前 classify(level, ...) 调用点数=" + gateUses);
		assertTrue(normalized.contains("smeltingInputCache, tagGate).isAllowed()"),
				"getPullCandidateAmount（扫描路径谓词）必须传入 tagGate");

		// 2) reserveFloor 对 pullList 中每个条目无条件计算 —— 与该条目是蜜脾还是
		//    标签过滤放行的 smelt 输入无关，保留库存因此对两类候选一致生效。
		assertTrue(normalized.contains("entry.reserveFloor = filter == null ? -1L "
				+ ": filter.getReserveFloorForKey(entry.key, sortIgnoreNbt);"),
				"每个拉取条目都必须携带 reserveFloor，不得按候选种类区分");

		// 3) 抽取前的实时闸门：liveExtractable → reserveSafeRequest → extract，
		//    且 extract 只有这一处（全模组唯一 AE2 输入抽取点）。
		assertTrue(normalized.contains("amount = Ae2FilterPullPolicy.reserveSafeRequest("
				+ "amount, liveExtractable, reserveFloor);"),
				"抽取前必须用实时可抽取量收口，KeyCounter 到 tick 末才刷新");
		int extractPoints = normalized.split(
				"meStorage\\.extract\\(key, amount, Actionable\\.MODULATE", -1).length - 1;
		assertTrue(extractPoints == 1,
				"AE2 输入抽取点必须唯一（当前 " + extractPoints + " 处），否则新路径会绕过保留闸门");

		// 4) 候选谓词对 reserve 守卫键先按 MAX_VALUE 放行，由第 3 步收口 ——
		//    这样外部存储（其模拟库存不在 KeyCounter 里）不会被误判为 0 而永不拉取。
		assertTrue(normalized.contains("long available = reserveGuarded ? Long.MAX_VALUE "
				+ ": availableStacks.get(key);"),
				"reserve 守卫键必须在候选阶段放行，最终由实时闸门裁剪");

		// 5) 非直连（模糊/未配置）候选也受全局 reserve 约束
		String policy = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2FilterPullPolicy.java");
		assertTrue(policy.replaceAll("\\s+", " ").contains(
				"if (!directFound) return reserveFloor < 0L ? -1L : Math.max(0L, visibleStock - reserveFloor);"),
				"未命中直连条目的候选仍必须扣除全局保留量");
	}
}
