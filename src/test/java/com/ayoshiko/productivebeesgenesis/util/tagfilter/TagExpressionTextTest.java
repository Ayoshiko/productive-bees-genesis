package com.ayoshiko.productivebeesgenesis.util.tagfilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 标签选取器「追加字面量」逻辑的纯 JVM 单测。 */
class TagExpressionTextTest {

	private static final int MAX = TagExpressionParser.MAX_EXPRESSION_LENGTH;

	@Test
	@DisplayName("空表达式追加后就是该字面量本身")
	void appendToEmpty() {
		assertEquals("c:ores/iron", TagExpressionText.appendLiteral("", "c:ores/iron", '|', MAX));
		assertEquals("c:ores/iron", TagExpressionText.appendLiteral(null, "c:ores/iron", '|', MAX));
	}

	@Test
	@DisplayName("非空表达式用指定运算符连接")
	void appendWithOperator() {
		assertEquals("c:ores | c:ingots",
				TagExpressionText.appendLiteral("c:ores", "c:ingots", '|', MAX));
		assertEquals("c:ores & c:ingots",
				TagExpressionText.appendLiteral("c:ores", "c:ingots", '&', MAX));
	}

	@Test
	@DisplayName("整词去重：更长的同前缀标签不算已存在")
	void wordBoundaryDeduplication() {
		assertTrue(TagExpressionText.containsLiteral("c:ores/iron | c:ingots", "c:ores/iron"));
		// c:ores 是 c:ores/iron 的前缀子串，但不是整词命中
		assertFalse(TagExpressionText.containsLiteral("c:ores/iron", "c:ores"));
		assertEquals("c:ores/iron | c:ores",
				TagExpressionText.appendLiteral("c:ores/iron", "c:ores", '|', MAX));
	}

	@Test
	@DisplayName("已存在的字面量不重复追加（忽略大小写）")
	void skipDuplicate() {
		assertEquals("c:ores | c:ingots",
				TagExpressionText.appendLiteral("c:ores | c:ingots", "c:ingots", '|', MAX));
		assertEquals("c:ores", TagExpressionText.appendLiteral("c:ores", "C:ORES", '|', MAX));
	}

	@Test
	@DisplayName("括号与取反符号旁的字面量同样能被识别为已存在")
	void detectsLiteralNextToOperators() {
		assertTrue(TagExpressionText.containsLiteral("!(c:ores)", "c:ores"));
		assertTrue(TagExpressionText.containsLiteral("(c:ores|c:ingots)", "c:ingots"));
	}

	@Test
	@DisplayName("超过长度上限时原样返回，不产生截断的非法表达式")
	void respectsMaxLength() {
		String base = "a".repeat(20);
		assertEquals(base, TagExpressionText.appendLiteral(base, "bbbb", '|', 21));
		// 空表达式下单个超长字面量也不写入
		assertEquals("", TagExpressionText.appendLiteral("", "a".repeat(30), '|', 10));
	}

	@Test
	@DisplayName("空白字面量被忽略，且返回值已 trim")
	void ignoresBlankLiteral() {
		assertEquals("c:ores", TagExpressionText.appendLiteral("  c:ores  ", "   ", '|', MAX));
		assertEquals("c:ores", TagExpressionText.appendLiteral("  c:ores  ", null, '|', MAX));
	}

	@Test
	@DisplayName("追加结果仍是合法表达式，且语义为「或」")
	void appendedExpressionStaysParsable() {
		String expression = TagExpressionText.appendLiteral("c:ores", "minecraft:iron_ingot", '|', MAX);
		TagExpressionParser.Result result = TagExpressionParser.parse(expression);
		assertFalse(result.isError());
		assertTrue(result.isPresent());
		TagCandidate onlyItem = TagCandidate.of("minecraft:iron_ingot", java.util.List.of());
		TagCandidate onlyTag = TagCandidate.of("minecraft:stone", java.util.List.of("c:ores"));
		TagCandidate neither = TagCandidate.of("minecraft:dirt", java.util.List.of("c:dirt"));
		assertTrue(result.expression().test(onlyItem));
		assertTrue(result.expression().test(onlyTag));
		assertFalse(result.expression().test(neither));
	}

	@Test
	@DisplayName("listLiterals 按出现顺序去重列出全部字面量")
	void listsLiterals() {
		assertEquals(java.util.List.of("c:ores", "c:ingots"),
				TagExpressionText.listLiterals("c:ores | c:ingots"));
		assertEquals(java.util.List.of("c:ores", "c:ingots"),
				TagExpressionText.listLiterals("!(c:ores & c:ingots) ^ c:ores"));
		assertEquals(java.util.List.of(), TagExpressionText.listLiterals("   "));
		assertEquals(java.util.List.of(), TagExpressionText.listLiterals(null));
	}

	@Test
	@DisplayName("removeLiteral 删除字面量并清掉悬空运算符")
	void removeCleansDanglingOperators() {
		assertEquals("c:ores", TagExpressionText.removeLiteral("c:ores | c:ingots", "c:ingots"));
		assertEquals("c:ingots", TagExpressionText.removeLiteral("c:ores | c:ingots", "c:ores"));
		assertEquals("", TagExpressionText.removeLiteral("c:ores", "c:ores"));
		// 删掉唯一操作数后，取反与空括号一并消失
		assertEquals("c:ingots", TagExpressionText.removeLiteral("!c:ores | c:ingots", "c:ores"));
		assertEquals("c:ingots", TagExpressionText.removeLiteral("!(c:ores) | c:ingots", "c:ores"));
	}

	@Test
	@DisplayName("removeLiteral 结果始终可解析，未命中则原样返回")
	void removeKeepsExpressionParsable() {
		String[] sources = {
				"c:ores | c:ingots & c:dust",
				"(c:ores | c:ingots) ^ !c:dust",
				"!(c:ores & c:ingots)",
		};
		for (String source : sources) {
			for (String literal : TagExpressionText.listLiterals(source)) {
				String result = TagExpressionText.removeLiteral(source, literal);
				if (!result.isEmpty()) {
					assertFalse(TagExpressionParser.parse(result).isError(),
							"删除 " + literal + " 后表达式非法：" + result);
				}
				assertFalse(TagExpressionText.containsLiteral(result, literal),
						"删除 " + literal + " 后仍存在：" + result);
			}
		}
		assertEquals("c:ores", TagExpressionText.removeLiteral("c:ores", "c:absent"));
	}

	@Test
	@DisplayName("整词删除：不会误删同前缀的更长标签")
	void removeRespectsWordBoundary() {
		assertEquals("c:ores/iron", TagExpressionText.removeLiteral("c:ores/iron", "c:ores"));
		assertEquals("c:ores", TagExpressionText.removeLiteral("c:ores | c:ores/iron", "c:ores/iron"));
	}
}
