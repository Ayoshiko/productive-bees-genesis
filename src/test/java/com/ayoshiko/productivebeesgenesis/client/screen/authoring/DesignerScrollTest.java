package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerScrollTest {
	@Test
	void wheelRetainsFractionsAndConvergesIndependentlyOfFrameRate() {
		DesignerScroll fast = scroll();
		DesignerScroll slow = scroll();
		assertTrue(fast.by(2.4));
		assertTrue(slow.by(2.4));
		assertEquals(0, fast.position());
		for (int frame = 0; frame < 24; frame++) {
			fast.advance(1.0 / 120, true);
		}
		for (int frame = 0; frame < 6; frame++) {
			slow.advance(1.0 / 30, true);
		}
		assertEquals(fast.position(), slow.position(), 0.000001);
		assertTrue(fast.position() > 2 && fast.position() <= 2.4);
		fast.advance(0.1, true);
		assertEquals(2.4, fast.position(), 0.01);
	}

	@Test
	void dragReverseAndShorterContentKeepPositionBounded() {
		DesignerScroll scroll = scroll();
		scroll.jump(357.25);
		assertEquals(357.25, scroll.position());
		scroll.by(120);
		scroll.advance(0.01, true);
		double beforeReverse = scroll.position();
		scroll.by(-240);
		scroll.advance(0.01, true);
		assertTrue(scroll.position() < beforeReverse);
		scroll.extent(100, 80);
		assertEquals(20, scroll.position());
		assertEquals(20, scroll.target());
		scroll.by(-1000);
		scroll.advance(0.01, false);
		assertEquals(0, scroll.position());
		assertFalse(scroll.by(-0.5));
		assertFalse(scroll.by(Double.NaN));
	}

	@Test
	void interactionStopsAnimationAndReducedMotionJumps() {
		DesignerScroll scroll = scroll();
		scroll.by(48);
		scroll.advance(0.02, true);
		scroll.stop();
		double pressed = scroll.position();
		scroll.advance(0.2, true);
		assertEquals(pressed, scroll.position());
		scroll.by(24.25);
		scroll.advance(0.01, false);
		assertEquals(pressed + 24.25, scroll.position());
	}

	private DesignerScroll scroll() {
		DesignerScroll result = new DesignerScroll();
		result.extent(1000, 200);
		return result;
	}
}
