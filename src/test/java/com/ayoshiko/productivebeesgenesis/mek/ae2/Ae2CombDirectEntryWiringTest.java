package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 蜜脾标记必须存成「精确指纹条目」的接线校验（源码级断言 + 匹配层行为断言）。
 * <p>
 * 背景（1.0.6 回归）：蜜脾标记曾被服务端强制降级成模糊条目，而
 * {@code GuiAeInputConfig.refreshGhostSlots} 只为指纹条目渲染上方齿轮（拉取数量/库存保留/
 * 无限/库存模式）与下方网络库存行，数量编辑器也用 {@code isDirectEntry} 把关，
 * 于是蜜脾标记退回「只有一个图标」的旧样式；同时非精确模式抹掉 {@code #block} 后缀，
 * 从 JEI 拖蜜脾块会显示成蜜脾。
 * <p>
 * 这条链路断开时界面只是"少了控件"而不会报错，纯逻辑单测发现不了，故用源码断言钉住。
 */
class Ae2CombDirectEntryWiringTest {

	private static final String AE2 = "src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/";
	private static final String NETWORK = "src/main/java/com/ayoshiko/productivebeesgenesis/network/";
	private static final String SCREEN = "src/main/java/com/ayoshiko/productivebeesgenesis/client/screen/";

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("客户端：蜜脾与普通物品共用指纹路径，蜜脾额外附带 beeType 供交叉校验")
	void clientAlwaysSendsFingerprint() throws Exception {
		String gui = read(SCREEN + "GuiAeInputConfig.java");
		String normalized = gui.replaceAll("\\s+", " ");
		assertTrue(normalized.contains("pos, Optional.ofNullable(beeType), Optional.of(fingerprint), isBlock,"),
				"蜜脾必须与指纹一起发送，否则服务端只能存模糊条目");
		assertTrue(normalized.contains("ghostSlots[pageSlotIndex].setDirectEntry(stack, fingerprint);"),
				"本地回显必须用拖入的真实物品，否则蜜脾块会画成蜜脾");
		// 只有指纹编码失败才允许退回模糊条目（普通物品无从退回）
		assertTrue(normalized.contains("if (beeType == null) return; PacketDistributor.sendToServer("),
				"模糊条目只能作为指纹编码失败后的兜底");
	}

	@Test
	@DisplayName("齿轮与库存行只对指纹条目渲染，故蜜脾必须是指纹条目")
	void gearRendersOnlyForDirectEntries() throws Exception {
		String gui = read(SCREEN + "GuiAeInputConfig.java");
		int directBranch = gui.indexOf("info.directFingerprint != null");
		int gearVisible = gui.indexOf("stockButtons[i].visible = true;");
		int fuzzyBranch = gui.indexOf("info != null && info.beeType != null");
		assertTrue(directBranch >= 0 && gearVisible > directBranch && fuzzyBranch > gearVisible,
				"齿轮仍只在精确条目分支点亮：蜜脾若退回模糊条目就没有齿轮，这正是回归的成因");
	}

	@Test
	@DisplayName("服务端：蜜脾条目改存规范指纹，不再降级为模糊条目")
	void serverKeepsCombFingerprint() throws Exception {
		String handlers = read(NETWORK + "Ae2FilterPayloadHandlers.java");
		assertTrue(handlers.contains("directFingerprint = canonicalFingerprint(serverPlayer, directKey);"),
				"两类物品都必须重新编码为规范指纹后落库");
		assertFalse(handlers.contains("directFingerprint = null;"),
				"蜜脾分支不得再把指纹置空（那会降级成模糊条目，齿轮与库存行随之消失）");
		// 交叉校验仍保留：客户端声明的 beeType/isBlock 必须与指纹解出的键一致
		assertTrue(handlers.replaceAll("\\s+", " ").contains(
				"if (beeType == null || !beeType.equals(actualBeeType) "
						+ "|| payload.isBlock() != CombFuzzyMatcher.isCombBlock(directKey)) return;"),
				"必须继续交叉校验声明与指纹，防止伪造请求");
	}

	@Test
	@DisplayName("模糊条目始终保留 #block 形态，切换精确模式不丢信息")
	void fuzzyEntriesKeepBlockForm() throws Exception {
		String filter = read(AE2 + "Ae2InputFilter.java");
		assertTrue(filter.contains("String entry = Ae2FilterEntrySupport.formatEntry(beeType, isBlock);"),
				"写入模糊条目时必须带形态后缀，否则蜜脾块会显示成蜜脾");
		assertFalse(filter.contains("normalized[i] = entry.substring(0, entry.length() - 6);"),
				"关精确模式时不得抹掉 #block：那是不可逆的形态丢失");
		// 保留后缀之所以安全：非精确模式的匹配层本就忽略它
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block",
				"productivebees:iron", false, false),
				"非精确模式下带 #block 的条目仍须匹配非方块蜜脾");
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block",
				"productivebees:iron", true, true),
				"精确模式下带 #block 的条目须匹配蜜脾块");
	}

	@Test
	@DisplayName("存量模糊蜜脾条目在打开配置界面时就地升级为指纹条目")
	void legacyFuzzyCombEntriesAreUpgradedOnOpen() throws Exception {
		String handlers = read(NETWORK + "Ae2FilterPayloadHandlers.java");
		assertTrue(handlers.contains("Ae2LegacyCombEntryUpgrade.upgrade(filter, inventory, player.registryAccess(),"),
				"打开界面时必须升级存量模糊蜜脾条目，否则旧存档仍然没有齿轮");
		assertTrue(handlers.replaceAll("\\s+", " ").contains(
				"if (maintainFilterEntries(host, serverPlayer) && be instanceof TileEntityMekanism mek) "
						+ "{ mek.markForSave();"),
				"维护结果必须落盘，否则重进世界又退回模糊条目");

		String upgrade = read(AE2 + "Ae2LegacyCombEntryUpgrade.java");
		assertTrue(upgrade.contains("if (filter == null || registries == null || !filter.hasFuzzyEntries()) return 0;"),
				"没有模糊条目时必须直接返回，避免每次开界面都全槽扫描");
		assertTrue(upgrade.contains("filter.setDirectEntryFingerprintAt(index, fingerprint);"),
				"升级必须真正写成指纹条目");
		assertTrue(upgrade.contains("filter.resolveDirectKey(index, key);"),
				"升级后应回填已知键，省掉一次指纹解析");
	}

	@Test
	@DisplayName("按蜂种构造的键对齐到网络里真实存在的变体，且保留逐槽设置")
	void synthesizedCombKeysRealignToNetworkVariant() throws Exception {
		String upgrade = read(AE2 + "Ae2LegacyCombEntryUpgrade.java");
		assertTrue(upgrade.contains("Ae2CombKeyAlignment.findReplacement(inventory, key)"),
				"升级时必须优先采用网络里真实存在的键，否则可见库存会读成 0");

		String alignment = read(AE2 + "Ae2CombKeyAlignment.java");
		// findFuzzy 按主键取子索引，只遍历同物品的组件变体；换成全量遍历会把开销带回热路径
		assertTrue(alignment.contains("inventory.findFuzzy(configured, FuzzyMode.IGNORE_ALL)"),
				"必须用 AE2 的 findFuzzy 子索引查同物品变体，不得全网络扫描");
		assertTrue(alignment.contains("!beeType.equals(CombFuzzyMatcher.getBeeType(candidate))"),
				"同 Item 不代表同蜂种，必须逐个核对 bee_type");
		assertTrue(alignment.contains("Ae2CombVariantPolicy.allowsRealign(filter.isPreciseMode())"),
				"精确模式下不得改写玩家选定的变体");
		// 改键必须走 repoint：setDirectEntryFingerprintAt 会把拉取量/保留量/无限/库存模式复位
		assertTrue(alignment.contains("filter.repointDirectEntryAt(index, fingerprint, replacement)"),
				"对齐必须保留逐槽设置");
		assertFalse(alignment.contains("setDirectEntryFingerprintAt"),
				"对齐不得走「放新标记」的入口，那会清掉玩家配置的数值");

		String filter = read(AE2 + "Ae2InputFilter.java");
		String normalized = filter.replaceAll("\\s+", " ");
		assertTrue(normalized.contains("synchronized boolean repointDirectEntryAt(int index, String fingerprint,"),
				"过滤器必须提供只换键的入口");
		// 只动 slots 与 keys 两个数组，四组逐槽状态一律不碰
		int repoint = normalized.indexOf("boolean repointDirectEntryAt(");
		int nextMethod = normalized.indexOf("public synchronized void resolveDirectKey(", repoint);
		String body = normalized.substring(repoint, nextMethod < 0 ? normalized.length() : nextMethod);
		assertFalse(body.contains("Ae2InputFilterSlotOps.setEntry("),
				"repoint 不得复用 setEntry：那会把该槽的拉取量/保留量/无限/库存模式清零");
		assertTrue(body.contains("Ae2InputFilterSlotOps.setKey(keys, index, key)"),
				"repoint 必须同步回填解析后的键");
	}
}
