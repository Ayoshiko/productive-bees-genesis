package com.ayoshiko.productivebeesgenesis.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AeInputConfigTextTest {

	@Test
	void formatsUnlimitedAmountWithoutASecondTooltipLine() {
		assertEquals("∞", AeInputConfigText.formatCompactAmount(Long.MAX_VALUE));
	}

	@Test
	void formatsLargeAmountsCompactly() {
		assertEquals("1.2K", AeInputConfigText.formatCompactAmount(1_234L));
		assertEquals("12.3M", AeInputConfigText.formatCompactAmount(12_345_678L));
		assertEquals("3.6T", AeInputConfigText.formatCompactAmount(3_600_000_000_000L));
		assertEquals("9.2E", AeInputConfigText.formatCompactAmount(Long.MAX_VALUE - 1));
	}
}
