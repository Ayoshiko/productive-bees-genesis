package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerAutoScrollTest {
	@Test
	void startsOnlyOnScrollableContentAndKeepsItsOriginalTarget() {
		DesignerAutoScroll auto = new DesignerAutoScroll();
		DesignerScroll first = scroll();
		DesignerScroll second = scroll();
		assertFalse(auto.start(null, 10, 100));
		assertFalse(auto.start(new DesignerScroll(), 10, 100));
		assertFalse(auto.start(first, Double.NaN, 100));
		assertFalse(auto.start(first, 10, Double.POSITIVE_INFINITY));
		assertTrue(auto.start(first, 10, 100));
		assertFalse(auto.start(second, 20, 200));
		auto.advance(150, 0.02);
		assertTrue(first.position() > 500);
		assertEquals(500, second.position());
		assertEquals(10, auto.originX());
		assertEquals(100, auto.originY());
	}

	@Test
	void centerPausesAndReverseHasNoResidualWheelMotion() {
		DesignerScroll scroll = scroll();
		scroll.by(200);
		DesignerAutoScroll auto = new DesignerAutoScroll();
		assertTrue(auto.start(scroll, 10, 100));
		scroll.advance(0.1, true);
		assertEquals(500, scroll.position());
		auto.advance(105, 0.04);
		assertEquals(500, scroll.position());
		assertEquals(0, auto.direction());
		auto.advance(150, 0.04);
		double lower = scroll.position();
		assertTrue(lower > 500);
		assertEquals(1, auto.direction());
		auto.advance(100, 0.04);
		scroll.advance(0.2, true);
		assertEquals(lower, scroll.position());
		auto.advance(50, 0.04);
		assertEquals(500, scroll.position(), 0.000001);
		assertEquals(-1, auto.direction());
		assertTrue(auto.stop());
		auto.advance(200, 0.1);
		scroll.advance(0.2, false);
		assertEquals(500, scroll.position(), 0.000001);
		assertFalse(auto.active());
		assertFalse(auto.stop());
	}

	@Test
	void speedIncreasesWithDistanceAndIsIndependentOfNormalFrameRate() {
		DesignerScroll fast = scroll();
		DesignerScroll slow = scroll();
		DesignerScroll near = scroll();
		DesignerAutoScroll a = new DesignerAutoScroll();
		DesignerAutoScroll b = new DesignerAutoScroll();
		DesignerAutoScroll c = new DesignerAutoScroll();
		a.start(fast, 10, 100);
		b.start(slow, 10, 100);
		c.start(near, 10, 100);
		for (int frame = 0; frame < 120; frame++) {
			a.advance(150, 1.0 / 120);
		}
		for (int frame = 0; frame < 30; frame++) {
			b.advance(150, 1.0 / 30);
			c.advance(120, 1.0 / 30);
		}
		assertEquals(fast.position(), slow.position(), 0.000001);
		assertTrue(near.position() > 500);
		assertTrue(fast.position() > near.position());
	}

	@Test
	void longFramesAndEdgesRemainBoundedWithoutAccumulatingOvershoot() {
		DesignerScroll scroll = scroll();
		DesignerAutoScroll auto = new DesignerAutoScroll();
		auto.start(scroll, 10, 100);
		auto.advance(100_000, 20);
		assertTrue(scroll.position() > 500 && scroll.position() <= 590);
		scroll.jump(scroll.maximum());
		auto.advance(100_000, 0.05);
		assertEquals(scroll.maximum(), scroll.position());
		auto.advance(50, 0.02);
		assertTrue(scroll.position() < scroll.maximum());
		scroll.jump(0);
		auto.advance(-100_000, 0.05);
		assertEquals(0, scroll.position());
		auto.advance(150, 0.02);
		assertTrue(scroll.position() > 0);
		double current = scroll.position();
		auto.advance(150, Double.NaN);
		auto.advance(150, -1);
		assertEquals(current, scroll.position());
	}

	@Test
	void contentThatNoLongerScrollsAndInvalidPointerCancelTheSession() {
		DesignerScroll scroll = scroll();
		DesignerAutoScroll auto = new DesignerAutoScroll();
		auto.start(scroll, 10, 100);
		scroll.extent(100, 200);
		auto.advance(150, 0.02);
		assertFalse(auto.active());
		assertEquals(0, scroll.position());
		scroll.extent(2000, 200);
		assertTrue(auto.start(scroll, 20, 200));
		auto.advance(Double.NaN, 0.02);
		assertFalse(auto.active());
	}

	private static DesignerScroll scroll() {
		DesignerScroll result = new DesignerScroll();
		result.extent(2000, 200);
		result.jump(500);
		return result;
	}
}
