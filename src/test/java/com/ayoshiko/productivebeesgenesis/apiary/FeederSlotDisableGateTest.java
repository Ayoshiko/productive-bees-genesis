package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 喂食槽禁用位掩码与服务端请求边界。 */
class FeederSlotDisableGateTest {

	private static final String PAYLOAD_HANDLERS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/ApiaryPayloadHandlers.java";
	private static final String BATCH_PAYLOAD =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/SetAllFeederSlotsDisabledPayload.java";

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

	/** 服务端权威：索引边界 + 交互距离 + 限频，缺一即可被恶意客户端滥用 */
	@Test
	void toggleHandlerValidatesRequest() throws Exception {
		String source = Files.readString(Path.of(PAYLOAD_HANDLERS));
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
		assertTrue(handlers.contains("apiary.setAllFeederSlotsDisabled(payload.disabled())"));

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
