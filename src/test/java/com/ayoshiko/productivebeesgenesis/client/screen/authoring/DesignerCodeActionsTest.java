package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerCodeActionsTest {
	private static String apply(String source, DesignerCodeActions.Edit edit) {
		return source.substring(0, edit.start()) + edit.text() + source.substring(edit.end());
	}

	@Test
	void multilineIndentAndOutdentPreserveLinesAndSelectionDirection() {
		String original = "{\n  \"first\": 1,\n  \"second\": 2\n}";
		int first = original.indexOf("  ");
		int end = original.lastIndexOf('\n');
		var indent = DesignerCodeActions.indent(original, end, first, false);
		String edited = apply(original, indent);
		assertEquals("{\n    \"first\": 1,\n    \"second\": 2\n}", edited);
		assertTrue(indent.anchor() > indent.cursor());
		assertEquals(original, apply(edited, DesignerCodeActions.indent(edited, indent.anchor(), indent.cursor(), true)));
	}

	@Test
	void pairedInputAndNewlinesRespectStringsAndEscapes() {
		var pair = DesignerCodeActions.type("", 0, 0, '{');
		assertEquals("{}", apply("", pair));
		assertEquals(1, pair.cursor());
		var newline = DesignerCodeActions.newline("{}", 1, 1);
		assertEquals("{\n  \n}", apply("{}", newline));
		assertEquals(4, newline.cursor());
		assertEquals("", apply("{}", DesignerCodeActions.backspacePair("{}", 1)));
		assertEquals("\"[\"", apply("\"\"", DesignerCodeActions.type("\"\"", 1, 1, '[')));
		var closing = DesignerCodeActions.type("\"bee\"", 4, 4, '"');
		assertEquals("\"bee\"", apply("\"bee\"", closing));
		assertEquals(5, closing.cursor());
		String escaped = "\"a\\\"b\"";
		assertEquals("\"a\\\"b{\"", apply(escaped, DesignerCodeActions.type(escaped, 5, 5, '{')));
		assertEquals(2, DesignerCodeActions.bracketPairs("{\"text\":\"[ignored]\"}").size());
	}

	@Test
	void duplicateAndDeleteOperateOnLogicalLines() {
		String source = "one\ntwo\nthree";
		assertEquals("one\ntwo\ntwo\nthree", apply(source, DesignerCodeActions.duplicate(source, 5, 5)));
		assertEquals("one\nthree", apply(source, DesignerCodeActions.deleteLines(source, 5, 5)));
		assertEquals("one\ntwo", apply(source, DesignerCodeActions.deleteLines(source, 10, 10)));
	}

	@Test
	void literalSearchWrapsAndReplacementIsAtomicWithinLimit() {
		var search = new DesignerCodeSearch();
		String source = "{\"bee\":\"A.b\", \"copy\":\"a.B\"}";
		search.query("a.b", false, source);
		assertEquals(2, search.matches().size());
		assertEquals(0, search.next(source.length(), source.length(), false));
		assertEquals(1, search.next(0, 0, true));
		assertEquals("{\"bee\":\"$1\", \"copy\":\"$1\"}", search.replaceAll(source, "$1", 128));
		assertThrows(IllegalArgumentException.class, () -> search.replaceAll(source, "x".repeat(100), 50));
		search.query("a.b", true, source);
		assertEquals(0, search.matches().size());
		search.query("", false, source);
		assertEquals(0, search.matches().size());
	}
}
