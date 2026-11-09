package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DesignerCatalogSelectionTest {
	@Test
	void shiftRangeWaitsForAllPagesAndKeepsAnchorForReverseSelection() {
		DesignerCatalogSelection selection = new DesignerCatalogSelection();
		selection.toggle(30, "bee30");
		assertTrue(selection.range(34, false));
		assertEquals(32, selection.missing(index -> index < 32 ? "bee" + index : null));
		assertFalse(selection.resolve(index -> index < 32 ? "bee" + index : null));
		assertEquals(Set.of("bee30"), selection.ids());
		assertTrue(selection.resolve(index -> "bee" + index));
		assertEquals(Set.of("bee30", "bee31", "bee32", "bee33", "bee34"), selection.ids());
		assertTrue(selection.range(28, false));
		assertTrue(selection.resolve(index -> "bee" + index));
		assertEquals(Set.of("bee28", "bee29", "bee30"), selection.ids());
	}

	@Test
	void draggingReplacesTheRectangleAndCancelRestoresExactSelection() {
		DesignerCatalogSelection selection = new DesignerCatalogSelection();
		selection.toggle(1, "bee1");
		selection.toggle(8, "bee8");
		selection.beginBox(true);
		assertTrue(selection.box(List.of(3, 4, 5), index -> "bee" + index));
		assertEquals(Set.of("bee1", "bee8", "bee3", "bee4", "bee5"), selection.ids());
		assertTrue(selection.box(List.of(4), index -> "bee" + index));
		assertEquals(Set.of("bee1", "bee8", "bee4"), selection.ids());
		selection.cancelBox();
		assertEquals(Set.of("bee1", "bee8"), selection.ids());
		selection.beginBox(false);
		selection.box(List.of(), index -> "bee" + index);
		assertEquals(0, selection.size());
		selection.cancelBox();
		assertEquals(Set.of("bee1", "bee8"), selection.ids());
	}

	@Test
	void choosingVisibleItemsCancelsAnOlderUnresolvedRange() {
		DesignerCatalogSelection selection = new DesignerCatalogSelection();
		selection.toggle(30, "bee30");
		selection.range(34, false);
		assertTrue(selection.waiting());
		assertTrue(selection.add(List.of("bee7", "bee8")));
		assertFalse(selection.waiting());
		assertFalse(selection.resolve(index -> "bee" + index));
		assertEquals(Set.of("bee30", "bee7", "bee8"), selection.ids());
	}

	@Test
	void overLimitRangesAreAtomicAndRefreshCancelsPendingRanges() {
		DesignerCatalogSelection selection = new DesignerCatalogSelection();
		selection.toggle(0, "bee0");
		assertFalse(selection.range(DesignerCatalogSelection.LIMIT, false));
		assertEquals(Set.of("bee0"), selection.ids());
		selection.beginBox(false);
		assertFalse(selection.box(IntStream.rangeClosed(0, DesignerCatalogSelection.LIMIT).boxed().toList(), index -> "bee" + index));
		assertEquals(Set.of("bee0"), selection.ids());
		selection.cancelBox();
		assertTrue(selection.range(DesignerCatalogSelection.LIMIT - 1, false));
		assertTrue(selection.resolve(index -> "bee" + index));
		assertEquals(DesignerCatalogSelection.LIMIT, selection.size());
		assertFalse(selection.toggle(DesignerCatalogSelection.LIMIT, "overflow"));
		assertEquals(DesignerCatalogSelection.LIMIT, selection.size());
		selection.clear();
		selection.toggle(2, "bee2");
		selection.range(8, false);
		selection.resetOrder();
		assertFalse(selection.waiting());
		assertEquals(Set.of("bee2"), selection.ids());
	}
}
