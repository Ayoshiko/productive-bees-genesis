package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输入条目可加工标记的网络编码与数组长度合同。 */
class Ae2FilterProcessabilityWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
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

}
