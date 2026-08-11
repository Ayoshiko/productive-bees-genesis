package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Ae2InputCooldownTest {

	@Test
	void successUsesShortCooldown() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		assertEquals(5, cd.current());
		cd.onSuccess(false);
		assertEquals(5, cd.current());
		cd.onSuccess(true);
		assertEquals(1, cd.current());
	}

	@Test
	void normalModeFailuresStayAtFiveTicks() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		cd.onFail(false);
		assertEquals(5, cd.current());
		cd.onFail(false);
		assertEquals(5, cd.current());
	}

	@Test
	void normalModeSuccessResetsExtendedCooldown() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		for (int i = 0; i < 100; i++) {
			cd.onFail(true);
		}
		assertEquals(40, cd.current());
		cd.onSuccess(false);
		assertEquals(5, cd.current());
		cd.onFail(false);
		assertEquals(5, cd.current());
	}

	@Test
	void unlimitedModeFailuresBackOffLinearly() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		cd.onSuccess(true);
		assertEquals(1, cd.current());
		cd.onFail(true);
		assertEquals(2, cd.current());
		cd.onFail(true);
		assertEquals(3, cd.current());
		for (int i = 0; i < 100; i++) {
			cd.onFail(true);
		}
		assertEquals(40, cd.current());
	}

	@Test
	void supplyAwareSuccessExtendsIntervalWhenQuotaNotReached() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		cd.onSuccess(false, 10L, 100L);
		assertEquals(10, cd.current());
		cd.onSuccess(false, 100L, 100L);
		assertEquals(5, cd.current());
		cd.onSuccess(true, 1L, 100L);
		assertEquals(1, cd.current());
	}

	@Test
	void supplyAwareIntervalStaysBounded() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		for (int i = 0; i < 20; i++) {
			cd.onSuccess(false, 1L, 100L);
		}
		assertEquals(10, cd.current());
	}

	@Test
	void resetRestoresDefaultCooldown() {
		Ae2InputCooldown cd = new Ae2InputCooldown();
		cd.onFail(true);
		cd.onFail(true);
		cd.reset();
		assertEquals(5, cd.current());
	}
}
