package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本机不可处理的蜜脾」在 AE2 输入配置界面上的灰显提示 —— 接线校验（源码级断言）。
 * <p>
 * 背景：拉取侧已在候选分类阶段拒绝这类蜜脾（见 {@link Ae2CombProcessableWiringTest}），
 * 但玩家在过滤器里把它配上后界面毫无反应，会误以为过滤器坏了。因此把同一判定
 * （宿主 {@code canProcessInput}）随过滤器同步包下发，在标记槽上叠灰罩 + tooltip 说明。
 * <p>
 * 这条链路一旦断开（少同步一个字段、忘了调 setProcessable、灰罩 z 被物品盖住），
 * 界面只是"没有提示"而非报错，纯逻辑单测发现不了，故用源码断言钉住。
 */
class Ae2FilterProcessabilityWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("判定在服务端完成，且与拉取器共用宿主 canProcessInput 入口")
	void serverSideViewSharesHostEntryPoint() throws Exception {
		String view = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2FilterProcessabilityView.java");
		// 必须走宿主同一入口，否则界面提示会与实际拉取行为不一致
		assertTrue(view.contains("host.productivebeesgenesis$canProcessInput(probe)"),
				"必须复用宿主判定入口，不得自建一套配方判定");
		// 固定蜜脾（ghostly/milky/powdery 与原版/feywild）无 bee_type 组件，必须走 Item 映射
		assertTrue(view.contains("CombFuzzyMatcher.getFixedDisplayStack(beeType, isBlock)"),
				"固定蜜脾必须按 Item 还原，否则造出的探针键在网络里不存在");
		// fail-open：判定不出来时按可加工呈现，避免误标灰让玩家以为配置坏了
		assertTrue(view.replaceAll("\\s+", " ").contains("if (probe.isEmpty()) return true;"),
				"还原不出探针时必须按可加工呈现");

		String handlers = read("src/main/java/com/ayoshiko/productivebeesgenesis/network/"
				+ "Ae2FilterPayloadHandlers.java");
		assertTrue(handlers.contains(
				"Ae2FilterProcessabilityView.canProcess(host, filter, ie.index())"),
				"同步包构建时必须逐条目计算可加工性");
	}

	@Test
	@DisplayName("可加工标记随过滤器同步包下发，客户端按平行数组长度校验")
	void flagIsSyncedAndValidated() throws Exception {
		String payload = read("src/main/java/com/ayoshiko/productivebeesgenesis/network/"
				+ "SyncAeInputFilterEntriesPayload.java");
		assertTrue(payload.contains("List<Boolean> processable"), "同步包必须携带该标记");
		// composite 在本 NeoForge 版本最多六字段，DirectState 已满，故并入 StockDefaults
		assertTrue(payload.contains("ByteBufCodecs.BOOL.apply(ByteBufCodecs.list(1024)),"
				+ " StockDefaults::processable"),
				"标记必须真正进入 StreamCodec，否则客户端永远收到默认值");

		String handlers = read("src/main/java/com/ayoshiko/productivebeesgenesis/network/"
				+ "Ae2FilterPayloadHandlers.java");
		assertTrue(handlers.contains("|| processable.size() != entries.size()) return;"),
				"平行数组长度必须校验，防止恶意服务端发送不一致列表");
		assertTrue(handlers.replaceAll("\\s+", " ").contains(
				"unlimited, networkStock, processable, payload.unlimitedAllFallback()"),
				"客户端快照替换必须带上该标记");
	}

	@Test
	@DisplayName("客户端快照默认全 true，不持久化，越界读回退可加工")
	void clientSnapshotDefaultsToProcessable() throws Exception {
		String filter = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputFilter.java");
		// 首次同步包到达前若默认 false，玩家一打开界面就是满屏灰
		assertTrue(filter.contains("private volatile boolean[] directProcessable = allProcessable("),
				"展示态标记初值必须全 true");
		assertTrue(filter.replaceAll("\\s+", " ").contains(
				"return index < 0 || index >= current.length || current[index];"),
				"越界或未同步必须回退为可加工");
		// 该标记是展示态，不能进 NBT；load 后必须重置，避免读到上个存档的陈旧值
		assertTrue(filter.contains("directProcessable = allProcessable(result.slots().length);"),
				"NBT 加载后必须重置展示态标记");

		String snapshot = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2InputFilterSnapshot.java");
		assertTrue(snapshot.contains("java.util.Arrays.fill(newProcessable, true);"),
				"快照构建必须先全填 true 再按同步值覆盖");
		// 模糊（bee_type）条目也要能标灰，所以不能塞进 isDirectFingerprint 分支里
		assertTrue(snapshot.contains("if (newSlots[index] != null && processableFlags != null"),
				"可加工标记对模糊条目同样生效，不得只在精确条目分支赋值");
	}

	@Test
	@DisplayName("标记槽叠灰罩并抬 z 到物品之上、数量标签之下")
	void ghostSlotRendersOverlayAboveItem() throws Exception {
		String widget = read("src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/"
				+ "GhostItemWidget.java");
		assertTrue(widget.contains("if (!processable) renderUnprocessableOverlay(guiGraphics);"),
				"物品渲染后必须叠灰罩");
		// 物品在 +150 层：不抬 z 的 fill 会被物品完全盖住，表现为「代码写了但看不见」
		assertTrue(widget.contains("pose.translate(0.0F, 0.0F, UNPROCESSABLE_Z_OFFSET);"),
				"灰罩必须抬 z，否则被 +150 的物品层盖住");
		assertTrue(widget.contains("UNPROCESSABLE_Z_OFFSET = 170.0F"),
				"z 必须位于物品层之上、数量标签(190)与下一窗口层(200)之下");
		// clear() 重置为 true，空槽不会残留上一个条目的灰罩
		assertTrue(widget.replaceAll("\\s+", " ").contains(
				"this.directFingerprint = null; this.processable = true; }"),
				"clear 必须重置标记，否则翻页后空槽残留灰罩");
	}

	@Test
	@DisplayName("三种条目形态都设标记，tooltip 追加不可处理说明")
	void configScreenWiresEveryEntryShape() throws Exception {
		String gui = read("src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/"
				+ "GuiAeInputConfig.java");
		String normalized = gui.replaceAll("\\s+", " ");
		assertTrue(normalized.contains("boolean processable = filter.isDirectProcessableAt(globalIdx);"),
				"必须读取同步来的展示态标记");
		// 三条渲染分支：精确条目已解析键、精确条目未解析（只有指纹）、模糊 bee_type 条目
		int marks = normalized.split("ghostSlots\\[i\\]\\.setProcessable\\(processable\\)", -1).length - 1;
		assertTrue(marks >= 3, "三种条目形态都必须设标记，当前调用点数=" + marks);
		assertTrue(gui.contains("productivebeesgenesis.gui.ae_input_config.unprocessable.tooltip"),
				"必须给出可读的原因说明，而不是只有一层灰");
		assertTrue(gui.contains("withUnprocessableHint("),
				"tooltip 必须经统一包装，避免三处各写一遍");

		// 两个语言文件都要有该键，否则会显示原始翻译键
		for (String lang : new String[] {"en_us", "zh_cn"}) {
			String json = read("src/main/resources/assets/productivebeesgenesis/lang/" + lang + ".json");
			assertTrue(json.contains("productivebeesgenesis.gui.ae_input_config.unprocessable.tooltip"),
					lang + " 缺少不可处理提示的翻译");
		}
	}
}
