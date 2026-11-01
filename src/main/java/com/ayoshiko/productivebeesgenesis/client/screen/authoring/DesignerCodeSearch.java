package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 字面查找只扫描当前有界文本；替换全部一次提交，拒绝超限的部分结果。 */
final class DesignerCodeSearch {
	private String query = "";
	private boolean matchCase;
	private List<Match> matches = List.of();

	void query(String query, boolean matchCase, String text) {
		this.query = query;
		this.matchCase = matchCase;
		update(text);
	}

	void update(String text) {
		if (query.isEmpty()) {
			matches = List.of();
			return;
		}
		var matcher = Pattern.compile(Pattern.quote(query), matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
		List<Match> found = new ArrayList<>();
		while (matcher.find()) {
			found.add(new Match(matcher.start(), matcher.end()));
		}
		matches = List.copyOf(found);
	}

	List<Match> matches() {
		return matches;
	}

	int lowerBound(int position) {
		int first = 0;
		int last = matches.size();
		while (first < last) {
			int middle = (first + last) >>> 1;
			if (matches.get(middle).start < position) {
				first = middle + 1;
			} else {
				last = middle;
			}
		}
		return first;
	}

	int next(int start, int end, boolean backwards) {
		if (matches.isEmpty()) {
			return -1;
		}
		int index = backwards ? lowerBound(start) - 1 : lowerBound(end);
		return Math.floorMod(index, matches.size());
	}

	String replaceAll(String text, String replacement, int limit) {
		long length = text.length();
		for (var match : matches) {
			length += replacement.length() - (match.end - match.start);
		}
		if (length > limit) {
			throw new IllegalArgumentException("designer.code_replace_limit");
		}
		StringBuilder result = new StringBuilder((int) length);
		int start = 0;
		for (var match : matches) {
			result.append(text, start, match.start).append(replacement);
			start = match.end;
		}
		return result.append(text, start, text.length()).toString();
	}

	record Match(int start, int end) {
	}
}
