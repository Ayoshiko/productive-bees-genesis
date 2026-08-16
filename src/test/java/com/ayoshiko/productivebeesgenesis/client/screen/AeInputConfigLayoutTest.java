package com.ayoshiko.productivebeesgenesis.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AeInputConfigLayoutTest {

	@Test
	void controlRowUsesWindowRelativeYAndStaysInsideWindow() {
		int relativeY = 73;
		int controlY = AeInputConfigLayout.controlY(relativeY);

		assertEquals(relativeY + AeInputConfigLayout.CTRL_Y, controlY);
		assertTrue(controlY >= relativeY);
		assertTrue(controlY + AeInputConfigLayout.CTRL_BTN_HEIGHT
				<= relativeY + AeInputConfigLayout.WINDOW_HEIGHT);
	}
}
