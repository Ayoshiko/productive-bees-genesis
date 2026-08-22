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
	}
}
