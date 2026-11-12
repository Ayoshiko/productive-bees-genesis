package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 蜜脾条目的指纹协议与蜜脾块匹配。 */
class Ae2CombDirectEntryWiringTest {

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
	void fuzzyEntriesKeepBlockForm() {
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block",
				"productivebees:iron", false, false),
				"非精确模式下带 #block 的条目仍须匹配非方块蜜脾");
		assertTrue(Ae2FilterEntryMatcher.matches("productivebees:iron#block",
				"productivebees:iron", true, true),
				"精确模式下带 #block 的条目须匹配蜜脾块");
	}

}
