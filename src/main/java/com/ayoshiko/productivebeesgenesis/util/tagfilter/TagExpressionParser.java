package com.ayoshiko.productivebeesgenesis.util.tagfilter;

/**
 * 标签过滤表达式解析器（递归下降，无正则、无回溯）。
 * <p>
 * 语法（与 ExtendedAE / ExtendedAE-Plus 的标签表达式一致）：
 * <pre>
 *   expr    := xor ( '|' xor )*        逻辑或
 *   xor     := and ( '^' and )*        逻辑异或
 *   and     := unary ( '&' unary )*    逻辑与
 *   unary   := '!' unary | primary     逻辑非
 *   primary := '(' expr ')' | literal  括号优先 / 字面量
 *   literal := 任意非运算符字符（含 '*' 通配、':' '/' '_' '.' '-'）
 * </pre>
 * 优先级：{@code ! > & > ^ > |}；字面量既可写标签 id（{@code c:honeycombs}），
 * 也可直接写物品 id（{@code minecraft:honeycomb_block}）。
 * <p>
 * <b>安全上限</b>（防止玩家输入拖垮服务端，客户端与服务端使用同一套常量重复校验）：
 * 表达式长度 ≤ {@value #MAX_EXPRESSION_LENGTH}、AST 节点 ≤ {@value #MAX_NODES}、
 * 递归深度 ≤ {@value #MAX_DEPTH}、单字面量通配符数量由 {@link TagPattern} 限制。
 * <p>
 * 纯静态无状态，无 Minecraft 依赖，线程安全。
 */
public final class TagExpressionParser {

	/** 表达式最大长度。 */
	public static final int MAX_EXPRESSION_LENGTH = 512;

	/** AST 最大节点数，限制单次求值的最坏开销。 */
	public static final int MAX_NODES = 64;

	/** 括号最大嵌套深度，避免深递归造成 StackOverflowError。 */
	public static final int MAX_DEPTH = 16;

	private TagExpressionParser() {
	}

	/**
	 * 解析结果。空表达式返回 {@code empty()}（语义为「未配置」，不是错误），
	 * 语法错误返回 {@code error(reason)}，其中 reason 为可本地化的错误键后缀。
	 */
	public record Result(TagExpression expression, String errorKey) {

		public static Result empty() {
			return new Result(null, null);
		}

		public static Result of(TagExpression expression) {
			return new Result(expression, null);
		}

		public static Result error(String errorKey) {
			return new Result(null, errorKey);
		}

		/** 是否解析成功且存在可用表达式。 */
		public boolean isPresent() {
			return expression != null;
		}

		/** 是否为语法错误。 */
		public boolean isError() {
			return errorKey != null;
		}
	}

	/** 解析表达式；永不抛异常，任何非法输入都以 {@link Result#error} 返回。 */
	public static Result parse(String source) {
		if (source == null) return Result.empty();
		String text = normalizeOperators(source.trim());
		if (text.isEmpty()) return Result.empty();
		if (text.length() > MAX_EXPRESSION_LENGTH) return Result.error("too_long");
		try {
			Parser parser = new Parser(text);
			TagExpression expression = parser.parseOr(0);
			parser.skipSpaces();
			if (!parser.atEnd()) return Result.error("unexpected_token");
			if (expression.nodeCount() > MAX_NODES) return Result.error("too_complex");
			return Result.of(expression);
		} catch (ParseError error) {
			return Result.error(error.errorKey);
		} catch (StackOverflowError error) {
			// 理论上被 MAX_DEPTH 挡住，仍兜底防御，避免异常逃逸到 tick 线程
			return Result.error("too_complex");
		}
	}

	/**
	 * 把 {@code &&}/{@code ||} 折叠为单字符运算符（对齐 ExtendedAE 的 removeExtraSyb）。
	 * <p>
	 * 玩家习惯写 Java 风格的双字符运算符，不容错就会在「表达式看着没问题」时报语法错误。
	 * 手写单趟扫描而不用 {@code String.replace}：后者对长文本会分配两个中间串，
	 * 且本方法处在 GUI 每次按键的校验路径上。
	 */
	private static String normalizeOperators(String text) {
		if (text.indexOf('&') < 0 && text.indexOf('|') < 0) return text;
		StringBuilder builder = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			builder.append(c);
			if (c != '&' && c != '|') continue;
			// 跳过紧随其后的同种运算符，&&& 同样折叠为 &
			while (i + 1 < text.length() && text.charAt(i + 1) == c) i++;
		}
		return builder.toString();
	}

	/** 内部解析器 — 单次使用，非线程安全（每次 parse 新建）。 */
	private static final class Parser {
		private final String text;
		private int index;

		Parser(String text) {
			this.text = text;
		}

		TagExpression parseOr(int depth) {
			TagExpression left = parseXor(depth);
			while (consumeOperator('|')) {
				left = new TagExpression.Or(left, parseXor(depth));
			}
			return left;
		}

		private TagExpression parseXor(int depth) {
			TagExpression left = parseAnd(depth);
			while (consumeOperator('^')) {
				left = new TagExpression.Xor(left, parseAnd(depth));
			}
			return left;
		}

		private TagExpression parseAnd(int depth) {
			TagExpression left = parseUnary(depth);
			while (consumeOperator('&')) {
				left = new TagExpression.And(left, parseUnary(depth));
			}
			return left;
		}

		private TagExpression parseUnary(int depth) {
			skipSpaces();
			if (consumeOperator('!')) {
				return new TagExpression.Not(parseUnary(depth));
			}
			return parsePrimary(depth);
		}

		private TagExpression parsePrimary(int depth) {
			skipSpaces();
			if (atEnd()) throw new ParseError("unexpected_end");
			if (text.charAt(index) == '(') {
				if (depth >= MAX_DEPTH) throw new ParseError("too_deep");
				index++;
				TagExpression inner = parseOr(depth + 1);
				skipSpaces();
				if (atEnd() || text.charAt(index) != ')') throw new ParseError("unclosed_paren");
				index++;
				return inner;
			}
			return new TagExpression.Literal(parseLiteral());
		}

		private TagPattern parseLiteral() {
			int start = index;
			while (!atEnd() && isLiteralChar(text.charAt(index))) index++;
			if (index == start) throw new ParseError("unexpected_token");
			TagPattern pattern = TagPattern.compile(text.substring(start, index));
			if (pattern == null) throw new ParseError("invalid_literal");
			return pattern;
		}

		/** 字面量允许的字符：运算符、括号与空白之外的一切。 */
		private static boolean isLiteralChar(char c) {
			return switch (c) {
				case '&', '|', '^', '!', '(', ')' -> false;
				default -> !Character.isWhitespace(c);
			};
		}

		private boolean consumeOperator(char operator) {
			skipSpaces();
			if (!atEnd() && text.charAt(index) == operator) {
				index++;
				return true;
			}
			return false;
		}

		void skipSpaces() {
			while (!atEnd() && Character.isWhitespace(text.charAt(index))) index++;
		}

		boolean atEnd() {
			return index >= text.length();
		}
	}

	/** 内部语法错误信号 — 不带堆栈，避免解析失败时的性能开销。 */
	private static final class ParseError extends RuntimeException {
		private final String errorKey;

		ParseError(String errorKey) {
			super(errorKey, null, false, false);
			this.errorKey = errorKey;
		}
	}
}
