package com.ayoshiko.productivebeesgenesis.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiaryDirectEjectOverlayLayoutTest {

	@Test
	void centrifugePriorityButtonIsBelowDirectEjectButton() {
		assertEquals(
				ApiaryDirectEjectLayout.BUTTON_X_OFFSET,
				ApiaryDirectEjectLayout.CENTRIFUGE_PRIORITY_BUTTON_X_OFFSET);
		assertEquals(2,
				ApiaryDirectEjectLayout.CENTRIFUGE_PRIORITY_BUTTON_Y_OFFSET
						- ApiaryDirectEjectLayout.BUTTON_Y_OFFSET
						- ApiaryDirectEjectLayout.BUTTON_SIZE);
	}
}
