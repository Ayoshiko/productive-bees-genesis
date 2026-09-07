package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：喂食槽逐格禁用必须覆盖全部"喂食槽 → 蜜蜂产出"通路
 * <br/>
 * 漏掉任一通路的后果：玩家禁用某格后该格物品仍在某条路径上生效
 * （如仍被当作花朵、仍被转化消耗、仍被 lumber/dye 蜜蜂抽样为产物），
 * 表现为"禁用无效"。依赖 Minecraft/Mekanism 运行时的类无法在单测中实例化，
 * 故对纯计算部分做真实断言，对接线部分按源码断言。
 */
class FeederSlotDisableGateTest {

	private static final String FEEDER_MANAGER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederSlotManager.java";
	private static final String FEEDER_SLOT =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederInventorySlot.java";
	private static final String AMBER_HELPER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/AmberEntityFlowerHelper.java";
	private static final String CONVERSION_PROCESSOR =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiaryConversionProcessor.java";
	private static final String CONTAINER_TRACKERS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiaryContainerTrackers.java";
	private static final String PAYLOAD_HANDLERS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/ApiaryPayloadHandlers.java";
	private static final String TOGGLE_SLOT =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/client/FeederDisableToggleSlot.java";
	private static final String MODE_BUTTON =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/client/FeederDisableModeButton.java";
	private static final String STATS_CACHE =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/client/FeederStatsCache.java";
	private static final String FEEDER_WINDOW =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/client/GuiFeederWindow.java";
	private static final String BATCH_PAYLOAD =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/SetAllFeederSlotsDisabledPayload.java";
	private static final String DISABLE_STATE =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederSlotDisableState.java";
	private static final String TAG_SAMPLER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederTagSampler.java";

	/** 同步/持久化位掩码字数量：60 槽 1 字；异常槽位数仍恒定注册 1 字，防两端 tracker 数量漂移 */
	@Test
	void disableMaskWordCountCoversAllSlots() {
		assertEquals(1, FeederSlotDisableState.wordCount(0));
		assertEquals(1, FeederSlotDisableState.wordCount(9));
		assertEquals(1, FeederSlotDisableState.wordCount(60));
		assertEquals(1, FeederSlotDisableState.wordCount(64));
		assertEquals(2, FeederSlotDisableState.wordCount(65));
		assertEquals(2, FeederSlotDisableState.wordCount(128));
		assertEquals(3, FeederSlotDisableState.wordCount(129));
	}

	/** 空格子禁用无意义：槽位变空时自动解除标志，避免放入新物品后被隐形禁用 */
	@Test
	void emptySlotClearsDisableFlag() throws Exception {
		String source = Files.readString(Path.of(FEEDER_SLOT));
		assertTrue(source.contains("public void onContentsChanged()"));
		assertTrue(source.contains("if (isEmpty()) disabled = false;"));
		assertTrue(source.contains("return !disabled && !isEmpty();"));
	}

	/**
	 * 花朵判定与产物抽样的全部扫描路径统一以 isActive 为判据
	 * <br/>
	 * 管理器侧三条：hasAnyFlower / blocks 精确匹配 / 转化原料花朵；
	 * 抽样侧两条：lumber-quarry 方块抽样 / dye 物品抽样。
	 */
	@Test
	void flowerScanPathsSkipDisabledSlots() throws Exception {
		String manager = Files.readString(Path.of(FEEDER_MANAGER));
		// 三条扫描路径各自的门禁写法（逐条断言而非计数，避免注释中的 isActive 干扰计数）
		assertTrue(manager.contains("if (feederSlots.get(i).isActive()) {"), "hasAnyFlower 缺少 isActive 门禁");
		assertTrue(manager.contains("if (!slot.isActive()) continue;"), "blocks 精确匹配缺少 isActive 门禁");
		assertTrue(manager.contains("if (!slot.isActive()) {"), "转化原料花朵扫描缺少 isActive 门禁");
		assertTrue(manager.contains("public boolean toggleSlotDisabled(int index)"));
		assertTrue(manager.contains("invalidateFlowerCache();"));

		String sampler = Files.readString(Path.of(TAG_SAMPLER));
		assertEquals(2, countOccurrences(sampler, "if (!slot.isActive()) continue;"),
				"多花蜜蜂的方块抽样与物品抽样都必须跳过禁用格");

		String state = Files.readString(Path.of(DISABLE_STATE));
		assertTrue(state.contains("if (slot.isEmpty()) return false;"),
				"空格子切换必须被拒绝（格子没有物品时禁用无效）");
	}

	/** entity_types 类蜜蜂（Butcher/Rancher/Wanna）的琥珀扫描同样跳过禁用格 */
	@Test
	void amberScanPathsSkipDisabledSlots() throws Exception {
		String source = Files.readString(Path.of(AMBER_HELPER));
		assertEquals(3, countOccurrences(source, "if (!slot.isActive()) continue;"),
				"琥珀扫描的三处遍历（实体 ID / 实体标签 / 产出快照）必须全部跳过禁用格");
	}

	/** 禁用格不得被转化消耗：匹配扫描与应用阶段都要复查（统一走 isActive，一次查找同时判空与判禁） */
	@Test
	void conversionSkipsDisabledSlots() throws Exception {
		String source = Files.readString(Path.of(CONVERSION_PROCESSOR));
		assertTrue(source.contains("feederManager.getFeederInventorySlots()"));
		assertEquals(2, countOccurrences(source, "if (!slot.isActive())"),
				"匹配扫描与应用阶段都必须以 isActive 门禁禁用格");
	}

	/** 禁用状态需同步到客户端（否则灰色遮罩与 Tooltip 停留在旧值） */
	@Test
	void disableMaskIsTrackedToClient() throws Exception {
		String source = Files.readString(Path.of(CONTAINER_TRACKERS));
		assertTrue(source.contains("getDisabledWordCount()"));
		assertTrue(source.contains("SyncableLong.create("));
		assertTrue(source.contains("feederManager.getDisabledWord(wordIndex)"));
		assertTrue(source.contains("feederManager.setDisabledWord(wordIndex, value)"));
	}

	/** 禁用位掩码必须在槽位内容之后加载，否则被 onContentsChanged 的空槽清理抹掉 */
	@Test
	void disableMaskPersistsAfterSlotContents() throws Exception {
		String source = Files.readString(Path.of(FEEDER_MANAGER));
		int loadSlots = source.indexOf("feederSlots.get(i).deserializeNBT(provider, list.getCompound(i));");
		int loadMask = source.indexOf("disableState.load(nbt);");
		assertTrue(loadSlots > 0 && loadMask > loadSlots, "禁用位掩码必须在槽位内容反序列化之后加载");
		assertTrue(source.contains("disableState.save(nbt);"));
	}

	/** 服务端权威：索引边界 + 交互距离 + 限频，缺一即可被恶意客户端滥用 */
	@Test
	void toggleHandlerValidatesRequest() throws Exception {
		String source = Files.readString(Path.of(PAYLOAD_HANDLERS));
		assertTrue(source.contains("static void handleToggleFeederSlotDisabled("));
		assertTrue(source.contains("slotIndex >= apiary.getFeederSlotManager().getFeederSlotCount()"));
		assertTrue(source.contains("NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ"));
		assertTrue(source.contains("FEEDER_SLOT_TOGGLE_RATE_KEY = \"feeder_slot_toggle\""));
		assertEquals(2, countOccurrences(source, "PayloadRateLimiter.tryAccept(serverPlayer, FEEDER_SLOT_TOGGLE_RATE_KEY"),
				"单格与批量两个 handler 必须共用同一限频预算，否则可交替发包绕过限频");
	}

	/** 批量操作同样是服务端权威，且传绝对目标状态（幂等）而非逐格翻转 */
	@Test
	void batchToggleIsServerAuthoritative() throws Exception {
		String payload = Files.readString(Path.of(BATCH_PAYLOAD));
		assertTrue(payload.contains("ByteBufCodecs.BOOL"), "批量包传绝对目标状态而非翻转标记");

		String handlers = Files.readString(Path.of(PAYLOAD_HANDLERS));
		assertTrue(handlers.contains("static void handleSetAllFeederSlotsDisabled("));
		assertTrue(handlers.contains("apiary.setAllFeederSlotsDisabled(payload.disabled())"));

		String manager = Files.readString(Path.of(FEEDER_MANAGER));
		assertTrue(manager.contains("public boolean setAllSlotsDisabled(boolean disabled)"));
		assertTrue(manager.contains("if (!disableState.setAll(disabled)) return false;"));

		String state = Files.readString(Path.of(DISABLE_STATE));
		assertTrue(state.contains("boolean setAll(boolean disabled)"));
		assertTrue(state.contains("if (slot.isEmpty()) continue;"), "批量操作必须跳过空格子");
	}

	/** Shift + 点击「禁」= 批量；普通点击仍只切换本地编辑模式（不发包） */
	@Test
	void batchGestureIsShiftClickOnModeButton() throws Exception {
		String source = Files.readString(Path.of(MODE_BUTTON));
		assertTrue(source.contains("if (Screen.hasShiftDown())"));
		assertTrue(source.contains("sendBatchRequest(tile)"));
		assertTrue(source.contains("onToggleMode.run()"));
		assertTrue(source.contains("new SetAllFeederSlotsDisabledPayload(tile.getBlockPos(), disableAll)"));
	}

	/**
	 * 喂食槽热路径必须走版本号缓存
	 * <br/>
	 * 位掩码 getter 被 MEK tracker 每 tick 轮询、hasAnyFlower 被转化处理器按蜜蜂类型组轮询、
	 * GUI 统计被每帧轮询；三者都必须以 stateVersion 为键缓存，否则是纯浪费。
	 */
	@Test
	void feederHotPathsAreCached() throws Exception {
		String manager = Files.readString(Path.of(FEEDER_MANAGER));
		assertTrue(manager.contains("private int stateVersion;"));
		assertTrue(manager.contains("stateVersion++;"), "invalidateFlowerCache 必须推进状态版本号");
		assertTrue(manager.contains("if (cachedHasAnyFlowerVersion == stateVersion)"));
		assertTrue(manager.contains("disableState.word(wordIndex, stateVersion)"));
		assertTrue(manager.contains("public int getStateVersion()"));

		String state = Files.readString(Path.of(DISABLE_STATE));
		assertTrue(state.contains("if (cachedWordsVersion != version)"),
				"位掩码打包结果必须按状态版本号缓存（MEK tracker 每 tick 轮询）");

		String statsCache = Files.readString(Path.of(STATS_CACHE));
		assertTrue(statsCache.contains("manager.getStateVersion()"));
		assertTrue(statsCache.contains("if (version == cachedVersion)"), "版本未变时必须直接复用缓存");

		String window = Files.readString(Path.of(FEEDER_WINDOW));
		assertTrue(window.contains("statsCache.refresh(tile)"));
		assertTrue(!window.contains("List<ItemStack> flowerTypes = new ArrayList<>()"),
				"信息面板不得每帧新建统计列表");
	}

	/** 原版容器交互必须保留：只有编辑模式或 Alt 才拦截左键，右键一律交回父类 */
	@Test
	void vanillaSlotInteractionIsPreserved() throws Exception {
		String source = Files.readString(Path.of(TOGGLE_SLOT));
		assertTrue(source.contains("if (button == 0 && isToggleGesture()"));
		assertTrue(source.contains("return super.mouseClicked(mouseX, mouseY, button);"));
		assertTrue(source.contains("disableModeActive.getAsBoolean() || Screen.hasAltDown()"));
		assertTrue(source.contains("!containerSlot.getItem().isEmpty()"));
	}

	private static int countOccurrences(String source, String token) {
		int count = 0;
		int from = 0;
		while (true) {
			int at = source.indexOf(token, from);
			if (at < 0) return count;
			count++;
			from = at + token.length();
		}
	}
}
