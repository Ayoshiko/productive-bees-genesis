package com.ayoshiko.productivebeesgenesis.util.tagfilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 标签过滤表达式（解析 + 通配 + 白/黑名单语义）的纯 JVM 单元测试。 */
class TagFilterSpecTest {

	private static TagCandidate honeycombBlock() {
		return TagCandidate.of("minecraft:honeycomb_block",
				List.of("c:storage_blocks/honeycombs", "minecraft:beacon_base_blocks"));
	}

	private static TagCandidate ironOre() {
		return TagCandidate.of("minecraft:iron_ore", List.of("c:ores", "c:ores/iron", "minecraft:iron_ores"));
	}

	// ===== TagPattern =====

	@Test
	@DisplayName("精确字面量走等值比较，大小写不敏感")
	void exactLiteral() {
		TagPattern pattern = TagPattern.compile("  C:Ores/Iron  ");
		assertNotNull(pattern);
		assertTrue(pattern.isExact());
		assertEquals("c:ores/iron", pattern.literal());
		assertTrue(pattern.matches("c:ores/IRON"));
		assertFalse(pattern.matches("c:ores/gold"));
	}

	@Test
	@DisplayName("通配符支持前缀、后缀、中缀与多段组合")
	void wildcardMatching() {
		assertTrue(TagPattern.compile("c:ores/*").matches("c:ores/iron"));
		assertFalse(TagPattern.compile("c:ores/*").matches("c:ingots/iron"));
		assertTrue(TagPattern.compile("*honeycombs").matches("c:storage_blocks/honeycombs"));
		assertTrue(TagPattern.compile("c:*/iron").matches("c:ores/iron"));
		assertTrue(TagPattern.compile("*").matches("anything"));
		assertTrue(TagPattern.compile("c:*blocks*honey*").matches("c:storage_blocks/honeycombs"));
	}

	@Test
	@DisplayName("末段必须贴住结尾且不与已消费前缀重叠")
	void anchoredEndDoesNotOverlap() {
		// "aa*aa" 不应匹配长度只有 3 的 "aaa"（前后段会重叠）
		assertFalse(TagPattern.compile("aa*aa").matches("aaa"));
		assertTrue(TagPattern.compile("aa*aa").matches("aaxaa"));
	}

	@Test
	@DisplayName("超长字面量与超量通配符被拒绝，防止匹配开销失控")
	void literalLimits() {
		assertNull(TagPattern.compile("a".repeat(TagPattern.MAX_LITERAL_LENGTH + 1)));
		assertNull(TagPattern.compile("*".repeat(TagPattern.MAX_WILDCARDS + 1) + "a"));
		assertNull(TagPattern.compile("   "));
		assertNull(TagPattern.compile(null));
	}

	// ===== 解析器 =====

	@Test
	@DisplayName("运算符优先级为 ! > & > ^ > |")
	void operatorPrecedence() {
		// a | b & c 等价于 a | (b & c)：候选只含 a 时仍应为真
		TagExpressionParser.Result result = TagExpressionParser.parse("c:ores | c:ingots & c:nuggets");
		assertTrue(result.isPresent());
		assertTrue(result.expression().test(TagCandidate.of("x", List.of("c:ores"))));
		assertFalse(result.expression().test(TagCandidate.of("x", List.of("c:ingots"))));
		assertTrue(result.expression().test(TagCandidate.of("x", List.of("c:ingots", "c:nuggets"))));
	}

	@Test
	@DisplayName("括号改变优先级")
	void parenthesesOverridePrecedence() {
		TagExpression expression = TagExpressionParser.parse("(c:ores | c:ingots) & c:iron").expression();
		assertNotNull(expression);
		assertTrue(expression.test(TagCandidate.of("x", List.of("c:ores", "c:iron"))));
		assertFalse(expression.test(TagCandidate.of("x", List.of("c:ores"))));
	}

	@Test
	@DisplayName("非、异或语义正确")
	void notAndXor() {
		TagExpression not = TagExpressionParser.parse("!c:ores").expression();
		assertFalse(not.test(ironOre()));
		assertTrue(not.test(honeycombBlock()));

		TagExpression xor = TagExpressionParser.parse("c:ores ^ c:ores/iron").expression();
		// 两者都命中 → 异或为假
		assertFalse(xor.test(ironOre()));
		TagExpression xorSingle = TagExpressionParser.parse("c:ores ^ c:gems").expression();
		assertTrue(xorSingle.test(ironOre()));
	}

	@Test
	@DisplayName("字面量可直接写物品 id")
	void literalMatchesItemId() {
		TagExpression expression = TagExpressionParser.parse("minecraft:honeycomb_block").expression();
		assertTrue(expression.test(honeycombBlock()));
		assertFalse(expression.test(ironOre()));
	}

	@Test
	@DisplayName("空表达式为「未配置」而非错误")
	void blankIsEmptyNotError() {
		TagExpressionParser.Result result = TagExpressionParser.parse("   ");
		assertFalse(result.isPresent());
		assertFalse(result.isError());
	}

	@Test
	@DisplayName("语法错误返回具体错误键，且不抛异常")
	void syntaxErrors() {
		assertEquals("unclosed_paren", TagExpressionParser.parse("(c:ores").errorKey());
		assertEquals("unexpected_end", TagExpressionParser.parse("c:ores &").errorKey());
		assertEquals("unexpected_token", TagExpressionParser.parse("c:ores )").errorKey());
		assertEquals("too_long",
				TagExpressionParser.parse("a".repeat(TagExpressionParser.MAX_EXPRESSION_LENGTH + 1)).errorKey());
	}

	@Test
	@DisplayName("超深括号嵌套与超多节点被拒绝，防止服务端被恶意表达式拖垮")
	void complexityLimits() {
		String deep = "(".repeat(TagExpressionParser.MAX_DEPTH + 2) + "a"
				+ ")".repeat(TagExpressionParser.MAX_DEPTH + 2);
		assertEquals("too_deep", TagExpressionParser.parse(deep).errorKey());

		StringBuilder wide = new StringBuilder("a");
		for (int i = 0; i < TagExpressionParser.MAX_NODES; i++) wide.append("|a");
		assertEquals("too_complex", TagExpressionParser.parse(wide.toString()).errorKey());
	}

	// ===== TagFilterSpec 语义 =====

	@Test
	@DisplayName("未配置时全部放行")
	void emptySpecAllowsAll() {
		TagFilterSpec spec = TagFilterSpec.compile("", "");
		assertFalse(spec.isActive());
		assertTrue(spec.allows(ironOre()));
		assertSameEmpty(spec);
	}

	private static void assertSameEmpty(TagFilterSpec spec) {
		assertEquals(TagFilterSpec.EMPTY, spec);
	}

	@Test
	@DisplayName("白名单非空时仅表达式为真的物品通过")
	void whitelistRestricts() {
		TagFilterSpec spec = TagFilterSpec.compile("c:storage_blocks/honeycombs_*|c:storage_blocks/honeycombs", "");
		assertTrue(spec.isActive());
		assertTrue(spec.allows(honeycombBlock()));
		assertFalse(spec.allows(ironOre()));
	}

	@Test
	@DisplayName("黑名单优先于白名单")
	void blacklistWinsOverWhitelist() {
		TagFilterSpec spec = TagFilterSpec.compile("c:storage_blocks/*", "minecraft:honeycomb_block");
		assertFalse(spec.allows(honeycombBlock()));
	}

	@Test
	@DisplayName("仅配置黑名单时其余物品全部放行")
	void blacklistOnly() {
		TagFilterSpec spec = TagFilterSpec.compile("", "c:ores");
		assertTrue(spec.isActive());
		assertFalse(spec.allows(ironOre()));
		assertTrue(spec.allows(honeycombBlock()));
	}

	@Test
	@DisplayName("单侧语法错误只让该侧失效，另一侧继续生效")
	void oneSidedErrorDegradesGracefully() {
		TagFilterSpec spec = TagFilterSpec.compile("c:ores", "(c:ingots");
		assertTrue(spec.hasError());
		assertEquals("unclosed_paren", spec.blacklistErrorKey());
		assertNull(spec.whitelistErrorKey());
		// 白名单仍生效，黑名单被忽略
		assertTrue(spec.allows(ironOre()));
		assertFalse(spec.allows(honeycombBlock()));
	}

	@Test
	@DisplayName("null 候选一律不通过；空标签集合只按物品 id 匹配")
	void nullAndEmptyCandidates() {
		TagFilterSpec spec = TagFilterSpec.compile("c:ores", "");
		assertFalse(spec.allows(null));
		assertFalse(spec.allows(TagCandidate.of("minecraft:stone", Set.of())));
		assertTrue(TagFilterSpec.compile("minecraft:stone", "")
				.allows(TagCandidate.of("minecraft:stone", Set.of())));
	}

	@Test
	@DisplayName("Java 风格 && 与 || 被折叠为单字符运算符")
	void doubleCharOperatorsAreNormalized() {
		TagExpressionParser.Result and = TagExpressionParser.parse("c:ores && c:ores/iron");
		assertFalse(and.isError());
		assertTrue(and.expression().test(ironOre()));
		assertFalse(and.expression().test(honeycombBlock()));

		TagExpressionParser.Result or = TagExpressionParser.parse("c:ores || c:storage_blocks/honeycombs");
		assertFalse(or.isError());
		assertTrue(or.expression().test(ironOre()));
		assertTrue(or.expression().test(honeycombBlock()));

		// 连写多个同种运算符同样折叠，不应报语法错误
		assertFalse(TagExpressionParser.parse("c:ores &&& c:ores/iron").isError());
	}
}
