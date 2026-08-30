package com.ayoshiko.productivebeesgenesis.util.tagfilter;

import java.util.Locale;

/**
 * 单个字面量的通配匹配（{@code *} 表示任意长度片段）。
 * <p>
 * 故意不使用 {@link java.util.regex.Pattern}：玩家可写出 {@code a*a*a*a*a*b} 这类
 * 模式，正则回溯是指数级的，在拉取热路径上会直接打满主线程。这里用经典的
 * 迭代式 glob 匹配（时间复杂度 O(n·m)，无递归、无回溯栈），并对 {@code *}
 * 数量设上限，保证最坏情况可控。
 * <p>
 * 无 Minecraft 依赖，可用纯 JVM 单测验证。不可变，天然线程安全。
 */
public final class TagPattern {

	/** 单个字面量允许的最大长度，防止超长字符串拖慢匹配。 */
	public static final int MAX_LITERAL_LENGTH = 256;

	/** 单个字面量允许的最大通配符数量，限制 glob 匹配最坏复杂度。 */
	public static final int MAX_WILDCARDS = 8;

	/** 原始字面量文本（已归一化为小写并去除首尾空白）。 */
	private final String literal;

	/** 按 {@code *} 切分后的固定片段；null 表示纯 {@code *}（匹配一切）。 */
	private final String[] segments;

	/** 是否要求开头对齐（字面量不以 {@code *} 起始）。 */
	private final boolean anchoredStart;

	/** 是否要求结尾对齐（字面量不以 {@code *} 结束）。 */
	private final boolean anchoredEnd;

	private TagPattern(String literal, String[] segments, boolean anchoredStart, boolean anchoredEnd) {
		this.literal = literal;
		this.segments = segments;
		this.anchoredStart = anchoredStart;
		this.anchoredEnd = anchoredEnd;
	}

	/**
	 * 编译一个字面量；非法输入返回 null 而不抛异常，
	 * 便于解析器把「非法字面量」当成语法错误统一上报。
	 */
	public static TagPattern compile(String raw) {
		if (raw == null) return null;
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > MAX_LITERAL_LENGTH) return null;

		int wildcards = 0;
		for (int i = 0; i < normalized.length(); i++) {
			if (normalized.charAt(i) == '*') wildcards++;
		}
		if (wildcards > MAX_WILDCARDS) return null;
		if (wildcards == 0) {
			return new TagPattern(normalized, new String[] { normalized }, true, true);
		}

		boolean anchoredStart = normalized.charAt(0) != '*';
		boolean anchoredEnd = normalized.charAt(normalized.length() - 1) != '*';
		String[] parts = splitOnWildcard(normalized);
		if (parts.length == 0) {
			// 形如 "*" / "***"：匹配任意非空文本
			return new TagPattern(normalized, null, false, false);
		}
		return new TagPattern(normalized, parts, anchoredStart, anchoredEnd);
	}

	/** 手写切分，避免 {@code String.split} 的正则开销与空片段语义歧义。 */
	private static String[] splitOnWildcard(String text) {
		int count = 0;
		int start = 0;
		String[] scratch = new String[MAX_WILDCARDS + 1];
		for (int i = 0; i <= text.length(); i++) {
			if (i == text.length() || text.charAt(i) == '*') {
				if (i > start) scratch[count++] = text.substring(start, i);
				start = i + 1;
			}
		}
		String[] result = new String[count];
		System.arraycopy(scratch, 0, result, 0, count);
		return result;
	}

	/** 返回归一化后的字面量文本（用于 GUI 回显与去重）。 */
	public String literal() {
		return literal;
	}

	/** 是否为不含通配符的精确字面量（可走哈希集合快路径）。 */
	public boolean isExact() {
		return segments != null && segments.length == 1 && anchoredStart && anchoredEnd;
	}

	/**
	 * 判定候选文本是否匹配本模式。
	 *
	 * @param candidate 候选文本（标签 id 或物品 id），大小写不敏感
	 */
	public boolean matches(String candidate) {
		if (candidate == null || candidate.isEmpty()) return false;
		String text = candidate.toLowerCase(Locale.ROOT);
		if (segments == null) return true; // 纯 "*"
		if (isExact()) return segments[0].equals(text);

		int cursor = 0;
		for (int i = 0; i < segments.length; i++) {
			String segment = segments[i];
			if (i == 0 && anchoredStart) {
				if (!text.startsWith(segment)) return false;
				cursor = segment.length();
				continue;
			}
			if (i == segments.length - 1 && anchoredEnd) {
				// 末段必须贴住结尾，且不能与已消费的前缀重叠
				int from = text.length() - segment.length();
				return from >= cursor && text.startsWith(segment, from);
			}
			int found = text.indexOf(segment, cursor);
			if (found < 0) return false;
			cursor = found + segment.length();
		}
		return true;
	}

	@Override
	public String toString() {
		return literal;
	}
}
