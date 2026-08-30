package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AE2 拉取配置在四条持久化路径上的接线校验（源码级断言，不需要 Minecraft 运行时）。
 * <p>
 * 四条路径互不共用代码，历史上多次出现「其中一条漏字段」的静默数据丢失
 * （拉取配额回退默认 64、开关被重置为关闭），且只有玩家实际操作才会暴露。
 * 本测试把四条路径的落盘/恢复入口钉住：
 * <ol>
 *   <li><b>镐子破坏</b>：{@code getDrops → saveToItem → saveAdditional}，
 *       数据随 {@code BLOCK_ENTITY_DATA} 组件进掉落物；</li>
 *   <li><b>扳手拆卸</b>：{@code saveCustomDataForItem}（{@code ICustomDataPersistable}）；</li>
 *   <li><b>工厂安装器等级升级</b>：{@code CentrifugeUpgradeData} +
 *       {@code CentrifugeUpgradeDataHelper.buildUpgradeData/applyUpgradeData}；</li>
 *   <li><b>配置卡复制</b>：{@code writeSustainedData/readSustainedData}。</li>
 * </ol>
 */
class Ae2PersistencePathWiringTest {

	private static final String AE2 = "src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/";
	private static final String MEK = "src/main/java/com/ayoshiko/productivebeesgenesis/mek/";
	private static final String APIARY = "src/main/java/com/ayoshiko/productivebeesgenesis/apiary/";

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("过滤器 NBT 编解码覆盖全部保留库存字段（主链路，四条路径共用）")
	void filterCodecPersistsEveryReserveField() throws Exception {
		String codec = read(AE2 + "Ae2InputFilterNbtCodec.java");
		// 过滤器级默认策略
		assertTrue(codec.contains("tag.putBoolean(\"globalNetworkStock\", globalNetworkStock)"));
		assertTrue(codec.contains("tag.putLong(\"globalReserveAmount\", Math.max(0L, globalReserveAmount))"));
		assertTrue(codec.contains("tag.putBoolean(\"unlimitedAllFallback\", unlimitedAllFallback)"));
		// 每条直连条目：a=拉取量 r=保留量 u=无限拉取 n=库存模式
		assertTrue(codec.contains("entryTag.putLong(\"r\", Math.max(0L, directReserveAmounts[i]))"));
		assertTrue(codec.contains("entryTag.putBoolean(\"n\", directNetworkStock[i])"));
		// 读回：缺 "n" 键的旧存档用 "u" 兜底（历史上二者同一个标志）
		assertTrue(codec.contains("entryTag.contains(\"n\", Tag.TAG_BYTE) ? entryTag.getBoolean(\"n\") : legacyStock"),
				"旧存档没有 n 键时必须回退到 u，否则升级后库存模式全部关闭");

		String filter = read(AE2 + "Ae2InputFilter.java");
		// save/load 必须把这些数组/标志真正传给编解码器
		assertTrue(filter.replaceAll("\\s+", " ").contains(
				"Ae2InputFilterNbtCodec.save(tag, filterMode, preciseMode, slots, directAmounts, "
						+ "directReserveAmounts, directUnlimited, directNetworkStock, unlimitedAllFallback, "
						+ "globalNetworkStock, globalReserveAmount)"));
		assertTrue(filter.contains("globalNetworkStock = result.globalNetworkStock();"));
		assertTrue(filter.contains("globalReserveAmount = result.globalReserveAmount();"));
		assertTrue(filter.contains("directReserveAmounts = result.directReserveAmounts();"));
		assertTrue(filter.contains("directNetworkStock = result.directNetworkStock();"));
	}

	@Test
	@DisplayName("per-tile 状态编解码同时携带过滤器与标签过滤，且 pending 不进配置卡")
	void perTileStatePersistsFilterAndTagFilter() throws Exception {
		String codec = read(AE2 + "Ae2PerTileStateNbtCodec.java");
		assertTrue(codec.contains("filter.save(filterTag)"));
		assertTrue(codec.contains("tag.put(Ae2NbtKeys.NBT_KEY_AE_INPUT_FILTER, filterTag)"));
		assertTrue(codec.contains("filter.load(tag.getCompound(Ae2NbtKeys.NBT_KEY_AE_INPUT_FILTER))"));
		// 无 filter 子标签的旧存档必须重置为干净状态，而不是沿用构造期残留
		assertTrue(codec.contains("filter.resetPersistentState()"));
		// 剩余物所有权记录是运行时状态，刻意与配置分离（配置卡不得复制别人的欠账）
		assertTrue(codec.contains("static void savePendingItems"));
		assertTrue(codec.contains("static void loadPendingItems"));
	}

	@Test
	@DisplayName("镐子破坏与扳手拆卸：离心机工厂两条路径都写 per-tile 状态 + pending")
	void centrifugeFactoryDropAndWrenchPersistAe2State() throws Exception {
		String logic = read(MEK + "CentrifugeFactoryCommonLogic.java");
		// 镐子破坏：saveToItem 只调 saveAdditional
		assertTrue(logic.contains("factory.productivebeesgenesis$getAe2StateHolder().savePerTileState(nbt)"));
		assertTrue(logic.contains("factory.productivebeesgenesis$getAe2StateHolder().savePendingItems(nbt)"));
		// 扳手拆卸：saveCustomDataForItem
		assertTrue(logic.contains("ae2StateHolder.savePerTileState(nbt)"));
		assertTrue(logic.contains("ae2StateHolder.savePendingItems(nbt)"));
		// 放置恢复
		assertTrue(logic.contains("factory.productivebeesgenesis$getAe2StateHolder().loadPerTileState(nbt)"));
		assertTrue(logic.contains("factory.productivebeesgenesis$getAe2StateHolder().loadPendingItems(nbt)"));
		// 配置卡：只复制配置，不复制 pending
		assertTrue(logic.contains("ae2StateHolder.savePerTileState(data)"));
		assertTrue(logic.contains("ae2StateHolder.loadPerTileState(data)"));
	}

	@Test
	@DisplayName("镐子破坏与扳手拆卸：基础离心机同样写 per-tile 状态 + pending")
	void basicCentrifugeDropAndWrenchPersistAe2State() throws Exception {
		String handler = read(MEK + "MekCentrifugeSaveHandler.java");
		assertTrue(handler.contains("ae2Handler.savePerTileState(nbt)"));
		assertTrue(handler.contains("ae2Handler.getStateHolder().savePendingItems(nbt)"));
		assertTrue(handler.contains("ae2Handler.loadPerTileState(nbt)"));
		assertTrue(handler.contains("ae2Handler.getStateHolder().loadPendingItems(nbt)"));
		// 配置卡只走配置
		assertTrue(handler.contains("ae2Handler.savePerTileState(data)"));
		assertTrue(handler.contains("ae2Handler.loadPerTileState(data)"));
	}

	@Test
	@DisplayName("工厂安装器等级升级：完整过滤器快照优先，legacy map 仅作回退")
	void tierInstallerCarriesFullFilterSnapshot() throws Exception {
		String helper = read(APIARY + "CentrifugeUpgradeDataHelper.java");
		// 保存：完整快照（含保留库存全部字段）
		assertTrue(helper.replaceAll("\\s+", " ").contains(
				"aeInputFilterNbt = new CompoundTag(); filter.save(aeInputFilterNbt);"),
				"等级升级必须保存完整过滤器快照，否则保留库存配置丢失");
		// 恢复：有快照走快照，无快照才回退 legacy
		assertTrue(helper.replaceAll("\\s+", " ").contains(
				"if (data.aeInputFilterNbt != null) { filter.load(data.aeInputFilterNbt.copy()); } "
						+ "else { restoreLegacyFilterData(data, filter); }"));
		// 四个 per-tile 开关随升级恢复
		assertTrue(helper.contains("ae2StateHolder.setAeItemInputEnabled(data.aeItemInputEnabled)"));
		assertTrue(helper.contains("ae2StateHolder.setAeInputNbtIgnore(data.aeInputNbtIgnore)"));
		assertTrue(helper.contains("ae2StateHolder.setSmeltingCompatEnabled(data.smeltingCompatEnabled)"));
		assertTrue(helper.contains("ae2StateHolder.setCentrifugeDirectAeOutputEnabled("
				+ "data.centrifugeDirectAeOutputEnabled)"));
		// smeltingCompat 与标签过滤不依赖 AE2 类，必须在 isAe2Loaded 守卫之外恢复
		int guardIndex = helper.indexOf("if (Ae2IntegrationLoader.isAe2Loaded()) {");
		int smeltIndex = helper.indexOf("ae2StateHolder.setSmeltingCompatEnabled(data.smeltingCompatEnabled)");
		assertTrue(smeltIndex >= 0 && guardIndex >= 0 && smeltIndex < guardIndex,
				"smeltingCompat 不依赖 AE2，必须在加载守卫之前恢复");
		// 损坏/恶意升级数据的索引上限
		assertTrue(helper.contains("MAX_FILTER_INDEX"));
	}

	@Test
	@DisplayName("蜂箱：四条路径的开关集合一致（配置卡曾漏喂食槽转化）")
	void apiaryTogglesPersistOnEveryPath() throws Exception {
		String serializer = read(APIARY + "ApiaryNbtSerializer.java");
		String persistence = read(APIARY + "ApiaryTilePersistence.java");
		String upgradeData = read(APIARY + "ApiaryUpgradeData.java");

		// 存档 + 扳手拆卸（writeApiaryStateTo 为二者共用）
		for (String key : new String[] {
			"NBT_KEY_DIRECT_EJECT", "NBT_KEY_DIRECT_AE_OUTPUT",
			"NBT_KEY_CENTRIFUGE_PRIORITY", "NBT_KEY_FEEDER_CONVERSION" }) {
			assertTrue(serializer.contains("nbt.putBoolean(" + key + ","),
					"存档/拆卸路径缺少 " + key);
			assertTrue(persistence.contains("ApiaryNbtSerializer." + key),
					"配置卡路径缺少 " + key);
		}
		// 等级升级
		assertTrue(upgradeData.contains("public final boolean feederConversionEnabled"));
		assertTrue(serializer.contains("tile.setFeederConversionEnabled(data.feederConversionEnabled)"),
				"等级升级必须恢复喂食槽转化开关");
		// 配置卡读回
		assertTrue(persistence.contains(
				"tile.setFeederConversionEnabled(data.getBoolean(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION))"),
				"配置卡必须能恢复喂食槽转化开关");
	}
}
