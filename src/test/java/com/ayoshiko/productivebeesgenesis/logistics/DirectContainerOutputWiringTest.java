package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「产物直通相邻容器」的接线校验（源码断言，无需启动游戏）。
 * <br/>
 * 覆盖三件容易漏的事：
 * <ol>
 *   <li>蜂箱产出路径与缓冲区排空路径都接上了直通，且按实际接收量记账；</li>
 *   <li>per-tile 开关在弹出器 Mixin 处与全局开关是 AND 关系；</li>
 *   <li>per-tile 开关走完蜂箱五条持久化路径与离心机状态持有者三条路径。</li>
 * </ol>
 */
class DirectContainerOutputWiringTest {

	private static final String SRC = "src/main/java/com/ayoshiko/productivebeesgenesis/";

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(SRC + relativePath));
	}

	@Test
	@DisplayName("蜂箱产出：离心机直连之后、写输出槽之前执行直通，蜜脾 hold 不直通")
	void apiaryProduceUsesDirectOutputBeforeSlots() throws Exception {
		String processor = read("apiary/BeeProduceProcessor.java");

		// 直通开关由本轮 flush 的机器级快照提供（见 ApiaryBatchUpgradeSnapshot）：
		// processBatchProduce 按蜂种分组逐组调用，per-tile 开关逐组读取会随分组数放大。
		int directIndex = processor.indexOf(
				"allItems = pushProducedToNeighbors(allItems, upgrades.directContainerOutputEnabled());");
		int distributeIndex = processor.indexOf("outputDispatcher.distribute(slotManager.getOutputSlots()");
		int centrifugeIndex = processor.indexOf("apiary.directTransferProducedToCentrifuges(allItems)");
		assertTrue(directIndex > 0, "产出路径必须调用直通");
		assertTrue(centrifugeIndex > 0 && centrifugeIndex < directIndex,
				"离心机直连优先于相邻容器直通，否则离心机优先会被外部容器抢走蜜脾");
		assertTrue(directIndex < distributeIndex, "直通必须在写输出槽之前，才能跳过输出槽中转");
		assertTrue(processor.contains("if (!apiary.shouldHoldForCentrifuge(stack)) {"),
				"待离心蜜脾不参与直通");
		assertTrue(processor.contains("if (accepted > 0) stack.shrink(accepted);"),
				"外部容器写入不可回滚，必须按实际接收量原地扣减");
	}

	@Test
	@DisplayName("缓冲区排空：与「缓冲区直推 AE」对称，成功后复位退避")
	void bufferDrainMirrorsAePath() throws Exception {
		String drain = read("apiary/ApiaryDirectContainerOutput.java");

		assertTrue(drain.contains("if (buffer.getBufferedGroupCount() <= 0) return;"),
				"缓冲区为空必须 O(1) 短路，不进入 synchronized 遍历");
		assertTrue(drain.contains("buffer.pushToSink(this::pushOne, tile::shouldHoldForCentrifuge)"),
				"排空需复用缓冲区通用推送并保留离心机优先 hold 过滤");
		assertTrue(drain.contains("backoff.recordSuccess()") && drain.contains("backoff.recordFailure(now)"),
				"相邻容器塞满时需退避，成功后立即复位");
		assertTrue(drain.contains("buffer.resetBackoff();"),
				"缓冲腾出空间后要解除缓冲区自身的回注退避");

		String tile = read("apiary/TileEntityMekApiary.java");
		int ejectIndex = tile.indexOf("directEjectHandler.tryDirectEject()");
		int drainIndex = tile.indexOf("directContainerOutput.drainBuffer()");
		int aeIndex = tile.indexOf("ae2HostAdapter.pushOutputs()");
		assertTrue(ejectIndex > 0 && drainIndex > ejectIndex && aeIndex > drainIndex,
				"tick 顺序必须是 离心机直连 → 相邻容器直通 → AE 推送");
	}

	@Test
	@DisplayName("per-tile 开关与全局开关 AND：Mixin 两侧都检查")
	void mixinAndsGlobalWithPerTileSwitch() throws Exception {
		String mixin = read("mixin/mek/TileComponentEjectorFastPathMixin.java");

		int globalIndex = mixin.indexOf("ExternalLogisticsSettings.directContainerOutput(level.getGameTime())");
		int perTileIndex = mixin.indexOf("if (!productivebeesgenesis$perTileDirectOutput(tile)) return 0;");
		assertTrue(globalIndex > 0 && perTileIndex > globalIndex,
				"全局开关先短路，再检查 per-tile 开关");
		assertTrue(mixin.contains("centrifuge.productivebeesgenesis$isDirectContainerOutputEnabled()")
						&& mixin.contains("apiary.productivebeesgenesis$isDirectContainerOutputEnabled()"),
				"离心机与蜂箱都要参与 per-tile 判定");
	}

	@Test
	@DisplayName("蜂箱开关持久化：存档 / 拆卸 / 等级升级 / 配置卡 / 容器同步 五条路径齐全")
	void apiarySwitchCoversAllPersistencePaths() throws Exception {
		String serializer = read("apiary/ApiaryNbtSerializer.java");
		assertTrue(serializer.contains("nbt.putBoolean(NBT_KEY_DIRECT_CONTAINER_OUTPUT,"
						+ " tile.isDirectContainerOutputEnabled());"),
				"存档与扳手拆卸共用的 writeApiaryStateTo 必须写入");
		assertTrue(serializer.contains("tile.setDirectContainerOutputEnabled("
						+ "nbt.getBoolean(NBT_KEY_DIRECT_CONTAINER_OUTPUT));"),
				"加载路径必须恢复；旧存档无键时保持默认开启");
		assertTrue(serializer.contains("tile.setDirectContainerOutputEnabled(data.directContainerOutputEnabled);"),
				"等级升级必须恢复");

		String persistence = read("apiary/ApiaryTilePersistence.java");
		assertTrue(persistence.contains("data.putBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_CONTAINER_OUTPUT,")
						&& persistence.contains("data.getBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_CONTAINER_OUTPUT)"),
				"配置卡需读写该开关，否则复制配置会静默重置");

		String trackers = read("apiary/ApiaryContainerTrackers.java");
		assertTrue(trackers.contains("tile::isDirectContainerOutputEnabled")
						&& trackers.contains("tile::setDirectContainerOutputEnabled"),
				"容器 tracker 必须无条件注册，保证客户端/服务端数量一致");
	}

	@Test
	@DisplayName("离心机开关：状态持有者 + NBT 编解码 + 两处 tracker + 等级升级")
	void centrifugeSwitchCoversHolderPaths() throws Exception {
		String holder = read("mek/ae2/Ae2OutputStateHolder.java");
		assertTrue(holder.contains("private volatile boolean directContainerOutputEnabled = true;"),
				"默认开启，保持既有直通行为");
		assertTrue(holder.contains("directContainerOutputEnabled = true;"),
				"clear() 需重置为默认值，防止方块重建后残留");

		String codec = read("mek/ae2/Ae2PerTileStateNbtCodec.java");
		assertTrue(codec.contains("tag.putBoolean(Ae2NbtKeys.NBT_KEY_DIRECT_CONTAINER_OUTPUT,"
						+ " holder.isDirectContainerOutputEnabled());"),
				"离心机 per-tile 状态需写 NBT（同时覆盖存档/拆卸/配置卡三路）");
		assertTrue(codec.contains("? tag.getBoolean(Ae2NbtKeys.NBT_KEY_DIRECT_CONTAINER_OUTPUT) : true);"),
				"旧存档缺键时回退 true");

		assertTrue(read("mek/MekCentrifugeAe2Handler.java").contains("holder::isDirectContainerOutputEnabled"),
				"基础离心机 tracker 必须注册");
		assertTrue(read("mek/CentrifugeFactoryCommonLogic.java")
						.contains("ae2StateHolder::isDirectContainerOutputEnabled"),
				"三类工厂离心机 tracker 必须注册");
		assertTrue(read("apiary/CentrifugeUpgradeDataHelper.java")
						.contains("ae2StateHolder.setDirectContainerOutputEnabled(data.directContainerOutputEnabled);"),
				"等级升级必须恢复，且不能放在 AE2 加载守卫内");
	}

	@Test
	@DisplayName("网络包：非 AE2 功能必须无条件注册，并校验容器/距离/全局开关")
	void payloadIsRegisteredOutsideAe2Guard() throws Exception {
		String payloads = read("network/ModPayloads.java");
		int directIndex = payloads.indexOf("ToggleDirectContainerOutputPayload.TYPE");
		int guardIndex = payloads.indexOf("if (Ae2IntegrationLoader.isAe2Loaded()) {");
		assertTrue(directIndex > 0 && guardIndex > 0 && directIndex < guardIndex,
				"直通开关与 AE2 无关，注册必须在 AE2 守卫之前");

		String handler = read("network/DirectContainerOutputPayloadHandler.java");
		assertTrue(handler.contains("serverPlayer.containerMenu instanceof MekanismTileContainer<?> tileContainer")
						&& handler.contains("getBlockPos().equals(payload.pos())"),
				"必须校验玩家当前打开的机器与坐标一致");
		assertTrue(handler.contains("NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ"),
				"必须校验交互距离");
		assertTrue(handler.contains("ExternalLogisticsSettings.directContainerOutput("
						+ "serverPlayer.level().getGameTime())"),
				"全局关闭时服务端也要拒绝切换");
	}

	@Test
	@DisplayName("按钮坐标不与既有侧面配置按钮重叠，且在 156×135 窗口内")
	void buttonOffsetDoesNotCollide() throws Exception {
		String overlay = read("client/screen/DirectContainerOutputOverlay.java");
		assertTrue(overlay.contains("BUTTON_X_OFFSET = 120") && overlay.contains("BUTTON_Y_OFFSET = 78"),
				"新按钮固定在 (120,78)");

		// 同列既有按钮：AE 输出(6)、AE 输入 / 蜂箱直输 AE(24)、熔炼兼容(42)、离心机直输 AE(60)
		assertTrue(read("client/screen/AeOutputOverlay.java").contains("BUTTON_Y_OFFSET = 6"));
		assertTrue(read("client/screen/AeInputOverlay.java").contains("BUTTON_Y_OFFSET = 24"));
		assertTrue(read("client/screen/SmeltingCompatOverlay.java").contains("BUTTON_Y_OFFSET = 42"));
		assertTrue(read("client/screen/CentrifugeDirectAeOutputOverlay.java").contains("BUTTON_Y_OFFSET = 60"));
		// 与最近的上方按钮(60)保持 14+4 间距；Mekanism 自身的清除侧面按钮在 (136,95)，不同列
		assertTrue(78 - 60 >= 14 + 4, "与上方按钮至少保持 4px 间距");
		assertTrue(120 + 14 <= 156 && 78 + 14 <= 135, "按钮必须落在侧面配置窗口内");
	}
}
