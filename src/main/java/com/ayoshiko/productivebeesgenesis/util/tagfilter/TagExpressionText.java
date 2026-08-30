package com.ayoshiko.productivebeesgenesis.util.tagfilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 表达式文本编辑辅助（纯静态，无 MC 依赖，可单测）。
 * <p>
 * 职责（SRP）：只处理「表达式文本 ↔ 字面量集合」的增删查 —— 边界感知的去重判断、
 * 长度上限保护、按字面量删除后的语法自愈。解析语义仍归 {@link TagExpressionParser}，
 * 界面交互归 GUI 层。
 * <p>
 * <b>为什么不用 {@code String.contains}</b>：{@code c:ores} 是 {@code c:ores/iron} 的
 * 子串，直接 contains 会把「已包含更长标签」误判为「已包含本标签」，导致玩家点加号毫无反应。
 * 必须检查左右邻居是否为字面量字符，才能得到「整词命中」。
 */
public final class TagExpressionText {

	/** 删除后语法自愈的最大迭代轮数（每轮至少删掉一个 token，纯保险上限防死循环）。 */
	private static final int MAX_CLEANUP_ROUNDS = 64;

	private TagExpressionText() {
	}

	/**
	 * 判断表达式中是否已存在该字面量（整词匹配，忽略大小写）。
	 *
	 * @param expression 表达式文本；null/空返回 false
	 * @param literal    标签或物品 id
	 */
	public static boolean containsLiteral(String expression, String literal) {
		return indexOfLiteral(expression, literal) >= 0;
	}

	/**
	 * 把字面量追加到表达式尾部。
	 * <p>
	 * 连接符由调用方给出：{@code '|'} 对应「命中任一即生效」，{@code '&'} 对应「必须全部命中」，
	 * 与精妙存储的「匹配任意标签 / 匹配所有标签」开关是同一语义。
	 * 已存在该字面量或会超出长度上限时原样返回，调用方据此判断是否需要提示。
	 *
	 * @param expression 现有表达式（null 视为空）
	 * @param literal    待追加字面量
	 * @param operator   连接符（{@code '|'} 或 {@code '&'}）
	 * @param maxLength  结果长度上限
	 * @return 追加后的表达式；无变化时返回原文本（trim 后）
	 */
	public static String appendLiteral(String expression, String literal, char operator, int maxLength) {
		String current = expression == null ? "" : expression.trim();
		if (literal == null || literal.isBlank()) return current;
		String trimmed = literal.trim();
		if (containsLiteral(current, trimmed)) return current;
		if (current.isEmpty()) {
			return trimmed.length() <= maxLength ? trimmed : current;
		}
		String appended = current + " " + operator + " " + trimmed;
		return appended.length() <= maxLength ? appended : current;
	}

	/**
	 * 列出表达式中出现的全部字面量（按出现顺序去重，保留原始大小写）。
	 * <p>
	 * 供「移除标签」交互列出可删项：玩家看到的候选必须和表达式里真实存在的一致，
	 * 因此这里做的是词法扫描而不是重新解析 AST（语法错误的中间态也要能删）。
	 */
	public static List<String> listLiterals(String expression) {
		if (expression == null || expression.isBlank()) return List.of();
		LinkedHashSet<String> literals = new LinkedHashSet<>();
		for (String token : tokenize(expression)) {
			if (isLiteralToken(token)) literals.add(token);
		}
		return List.copyOf(literals);
	}

	/**
	 * 从表达式中删除一个字面量，并修掉删除后留下的悬空运算符/空括号。
	 * <p>
	 * <b>为什么要自愈而不是简单替换</b>：直接把 {@code a | b} 中的 {@code b} 抹掉会剩下
	 * {@code a |}，玩家下次保存就会撞上 {@code unexpected_end} 报错。这里在 token 级删除后
	 * 反复清理「开头/结尾/紧邻括号的二元运算符」「悬空的 !」「空括号对」直到稳定。
	 * <p>
	 * 最后用 {@link TagExpressionParser#parse} 复核：自愈结果若仍非法则原样返回，
	 * 宁可这一次删除无效，也不把玩家的表达式改坏。
	 *
	 * @return 删除后的表达式；未命中或结果非法时返回原文本（trim 后）
	 */
	public static String removeLiteral(String expression, String literal) {
		String current = expression == null ? "" : expression.trim();
		if (indexOfLiteral(current, literal) < 0) return current;
		String needle = literal.trim().toLowerCase(Locale.ROOT);
		List<String> tokens = new ArrayList<>(tokenize(current));
		tokens.removeIf(token -> isLiteralToken(token) && token.toLowerCase(Locale.ROOT).equals(needle));
		String rebuilt = join(cleanup(tokens));
		if (rebuilt.isEmpty()) return "";
		return TagExpressionParser.parse(rebuilt).isError() ? current : rebuilt;
	}

	/** 返回字面量在表达式中的整词起始下标；未命中返回 -1。 */
	private static int indexOfLiteral(String expression, String literal) {
		if (expression == null || literal == null) return -1;
		String trimmed = literal.trim();
		if (trimmed.isEmpty()) return -1;
		String haystack = expression.toLowerCase(Locale.ROOT);
		String needle = trimmed.toLowerCase(Locale.ROOT);
		int from = 0;
		while (true) {
			int index = haystack.indexOf(needle, from);
			if (index < 0) return -1;
			boolean leftFree = index == 0 || !isLiteralChar(haystack.charAt(index - 1));
			int end = index + needle.length();
			boolean rightFree = end >= haystack.length() || !isLiteralChar(haystack.charAt(end));
			if (leftFree && rightFree) return index;
			from = index + 1;
		}
	}

	/** 词法切分：字面量整段成一个 token，运算符与括号各自单独成 token，空白丢弃。 */
	private static List<String> tokenize(String expression) {
		List<String> tokens = new ArrayList<>();
		int index = 0;
		int length = expression.length();
		while (index < length) {
			char character = expression.charAt(index);
			if (Character.isWhitespace(character)) {
				index++;
			} else if (isLiteralChar(character)) {
				int start = index;
				while (index < length && isLiteralChar(expression.charAt(index))) index++;
				tokens.add(expression.substring(start, index));
			} else {
				tokens.add(String.valueOf(character));
				index++;
			}
		}
		return tokens;
	}

	/** 反复清理悬空运算符与空括号，直到 token 序列稳定。 */
	private static List<String> cleanup(List<String> tokens) {
		for (int round = 0; round < MAX_CLEANUP_ROUNDS; round++) {
			if (!cleanupOnce(tokens)) break;
		}
		return tokens;
	}

	private static boolean cleanupOnce(List<String> tokens) {
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			String previous = i > 0 ? tokens.get(i - 1) : null;
			String next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
			if (isBinaryOperator(token)) {
				// 二元运算符缺左操作数或缺右操作数
				boolean noLeft = previous == null || "(".equals(previous)
						|| isBinaryOperator(previous) || "!".equals(previous);
				boolean noRight = next == null || ")".equals(next) || isBinaryOperator(next);
				if (noLeft || noRight) {
					tokens.remove(i);
					return true;
				}
			} else if ("!".equals(token)) {
				// 取反缺操作数
				if (next == null || ")".equals(next) || isBinaryOperator(next)) {
					tokens.remove(i);
					return true;
				}
			} else if ("(".equals(token) && ")".equals(next)) {
				// 空括号对
				tokens.remove(i + 1);
				tokens.remove(i);
				return true;
			}
		}
		return false;
	}

	/** 重新拼回文本：括号内侧不加空格，其余 token 之间用空格分隔，保证可读又可解析。 */
	private static String join(List<String> tokens) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if (builder.length() > 0 && !")".equals(token)
					&& !"(".equals(previousOf(tokens, i)) && !"!".equals(previousOf(tokens, i))) {
				builder.append(' ');
			}
			builder.append(token);
		}
		return builder.toString().trim();
	}

	private static String previousOf(List<String> tokens, int index) {
		return index > 0 ? tokens.get(index - 1) : null;
	}

	private static boolean isLiteralToken(String token) {
		return !token.isEmpty() && isLiteralChar(token.charAt(0));
	}

	private static boolean isBinaryOperator(String token) {
		return "&".equals(token) || "|".equals(token) || "^".equals(token);
	}

	/** 字面量允许字符：标签/物品 id 的组成字符与通配符，不含运算符与空白。 */
	private static boolean isLiteralChar(char character) {
		if (Character.isLetterOrDigit(character)) return true;
		return switch (character) {
			case ':', '/', '_', '-', '.', '*' -> true;
			default -> false;
		};
	}
}
