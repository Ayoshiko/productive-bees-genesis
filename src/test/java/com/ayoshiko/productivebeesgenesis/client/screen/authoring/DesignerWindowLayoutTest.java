package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerWindowLayoutTest {
	@Test
	void keepsSmallScreenAndJeiOutsideInventory() {
		DesignerWindowLayout layout = new DesignerWindowLayout();
		var small = layout.fit(320, 240, 0);
		assertEquals(304, small.width());
		assertEquals(132, small.height());
		assertEquals(136, small.bottom());
		var withJei = layout.fit(640, 360, 128);
		assertTrue(withJei.left() >= 128 + 8);
		assertTrue(withJei.right() <= 640 - 128 - 8);
		assertEquals(640, withJei.left() + withJei.right());
		assertTrue(withJei.bottom() < 360 - 100);
	}

	@Test
	void jeiGuidesInitialPlacementButDoesNotClampMovement() {
		DesignerWindowLayout layout = new DesignerWindowLayout();
		var initial = layout.fit(960, 540, 128);
		layout.press(DesignerWindowLayout.MOVE, initial.left() + 40, initial.top() + 12);
		assertTrue(layout.drag(10000, initial.top() + 12));
		layout.release();
		var moved = layout.fit(960, 540, 128);
		assertEquals(952, moved.right());
		assertEquals(DesignerWindowLayout.EAST, layout.edgeAt(moved.right() + 2, moved.top() + 30));
	}

	@Test
	void resizeAndMoveClampAllEdges() {
		DesignerWindowLayout layout = new DesignerWindowLayout();
		var original = layout.fit(960, 540, 0);
		layout.press(DesignerWindowLayout.EAST | DesignerWindowLayout.SOUTH, original.right(), original.bottom());
		assertTrue(layout.drag(original.left() - 1000, original.top() - 1000));
		layout.release();
		var resized = layout.fit(960, 540, 0);
		assertEquals(280, resized.width());
		assertEquals(132, resized.height());
		layout.press(DesignerWindowLayout.MOVE, resized.left(), resized.top());
		layout.drag(10000, 10000);
		layout.release();
		var moved = layout.fit(960, 540, 0);
		assertEquals(952, moved.right());
		assertEquals(436, moved.bottom());
		layout.press(DesignerWindowLayout.WEST | DesignerWindowLayout.NORTH, moved.left(), moved.top());
		layout.drag(-10000, -10000);
		layout.release();
		var expanded = layout.fit(960, 540, 0);
		assertEquals(8, expanded.left());
		assertEquals(4, expanded.top());
		assertEquals(952, expanded.right());
		assertEquals(436, expanded.bottom());
	}

	@Test
	void pinCancelsGestureAndResetRestoresAutomaticSize() {
		DesignerWindowLayout layout = new DesignerWindowLayout();
		var original = layout.fit(960, 540, 164);
		assertTrue(layout.press(DesignerWindowLayout.EAST, original.right(), original.top()));
		layout.togglePin();
		assertFalse(layout.dragging());
		assertEquals(0, layout.edgeAt(original.left(), original.top()));
		assertFalse(layout.press(DesignerWindowLayout.MOVE, original.left(), original.top()));
		layout.reset();
		assertFalse(layout.pinned());
		assertEquals(original, layout.fit(960, 540, 164));
	}

	@Test
	void temporaryScreenShrinkDoesNotLosePreferredSize() {
		DesignerWindowLayout layout = new DesignerWindowLayout();
		var original = layout.fit(1200, 800, 164);
		layout.press(DesignerWindowLayout.EAST | DesignerWindowLayout.SOUTH, original.right(), original.bottom());
		layout.drag(original.right() - 80, original.bottom() - 80);
		layout.release();
		var preferred = layout.fit(1200, 800, 164);
		var small = layout.fit(320, 240, 0);
		assertTrue(small.right() <= 312);
		assertTrue(small.bottom() <= 136);
		assertEquals(preferred, layout.fit(1200, 800, 164));
	}

	@Test
	void queryCombinesModTagAndLocalizedText() {
		var tags = java.util.List.of("minecraft:flowers", "c:flowers/small");
		assertTrue(DesignerSearch.matches("@minecraft #flowers 蒲公英", "minecraft", "minecraft:dandelion 蒲公英", tags));
		assertTrue(DesignerSearch.matches("#minecraft:flowers", "minecraft", "minecraft:dandelion", tags));
		assertFalse(DesignerSearch.matches("@mekanism #flowers", "minecraft", "minecraft:dandelion", tags));
		assertFalse(DesignerSearch.matches("#ores", "minecraft", "minecraft:dandelion", tags));
	}

	@Test
	void historyRestoresKeepsRedoAndBoundsSnapshots() {
		var history = new DesignerDraftHistory();
		var document = new com.google.gson.JsonObject();
		document.addProperty("name", "first");
		history.reset(document);
		document.addProperty("name", "second");
		history.capture(document);
		assertEquals("name", history.changes(1).getFirst().path());
		assertEquals("first", history.changes(1).getFirst().before());
		assertEquals("second", history.changes(1).getFirst().after());
		assertEquals("first", history.move(-1).get("name").getAsString());
		assertTrue(history.canRedo());
		assertEquals("second", history.move(1).get("name").getAsString());
		var branch = history.select(0);
		branch.addProperty("name", "branch");
		history.capture(branch);
		assertFalse(history.canRedo());
		for (int index = 0; index < 80; index++) {
			branch.addProperty("index", index);
			history.capture(branch);
		}
		assertEquals(64, history.times().size());
		assertEquals(16, history.select(0).get("index").getAsInt());
	}
}
