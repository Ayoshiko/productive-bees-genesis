package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** JSON 文本编辑的选区变换，不解析执行作者内容。 */
final class DesignerCodeActions {
	private DesignerCodeActions() {
	}

	record Edit(int start, int end, String text, int anchor, int cursor) {
	}

	static Edit indent(String text, int anchor, int cursor, boolean outdent) {
		int start = Math.min(anchor, cursor);
		int end = Math.max(anchor, cursor);
		if (start == end && !outdent) {
			int width = 2 - (cursor - lineStart(text, cursor)) % 2;
			return new Edit(cursor, cursor, " ".repeat(width), cursor + width, cursor + width);
		}
		int first = lineStart(text, start);
		int last = end > start && text.charAt(end - 1) == '\n' ? end : lineEnd(text, end);
		String selected = text.substring(first, last);
		StringBuilder result = new StringBuilder();
		int firstRemoved = 0;
		for (int position = 0; position < selected.length() || position == 0;) {
			int next = selected.indexOf('\n', position);
			int stop = next < 0 ? selected.length() : next;
			String line = selected.substring(position, stop);
			if (outdent) {
				int removed = 0;
				if (line.startsWith("\t") || line.startsWith(" ")) {
					removed = line.startsWith("  ") ? 2 : 1;
				}
				if (position == 0) {
					firstRemoved = removed;
				}
				result.append(line.substring(removed));
			} else {
				result.append("  ").append(line);
			}
			if (next < 0) {
				break;
			}
			result.append('\n');
			position = next + 1;
			if (position == selected.length()) {
				break;
			}
		}
		if (start == end) {
			int next = Math.max(first, cursor - firstRemoved);
			return new Edit(first, last, result.toString(), next, next);
		}
		int selectionEnd = first + result.length();
		return new Edit(first, last, result.toString(), anchor <= cursor ? first : selectionEnd, anchor <= cursor ? selectionEnd : first);
	}

	static Edit newline(String text, int anchor, int cursor) {
		int start = Math.min(anchor, cursor);
		int end = Math.max(anchor, cursor);
		int line = lineStart(text, start);
		int whitespace = line;
		while (whitespace < start && (text.charAt(whitespace) == ' ' || text.charAt(whitespace) == '\t')) {
			whitespace++;
		}
		String indent = text.substring(line, whitespace);
		boolean open = start > 0 && !insideString(text, start) && (text.charAt(start - 1) == '{' || text.charAt(start - 1) == '[');
		String insertion = "\n" + indent + (open ? "  " : "");
		int next = start + insertion.length();
		if (open && end < text.length() && close(text.charAt(start - 1)) == text.charAt(end)) {
			insertion += "\n" + indent;
		}
		return new Edit(start, end, insertion, next, next);
	}

	static Edit type(String text, int anchor, int cursor, char typed) {
		int start = Math.min(anchor, cursor);
		int end = Math.max(anchor, cursor);
		boolean string = insideString(text, start);
		if (start == end && end < text.length() && text.charAt(end) == typed
				&& ((typed == '}' || typed == ']') && !string || typed == '"' && string && !escaped(text, start))) {
			return new Edit(start, end, "", end + 1, end + 1);
		}
		char closing = close(typed);
		if (closing == 0 || string || escaped(text, start)) {
			return new Edit(start, end, Character.toString(typed), start + 1, start + 1);
		}
		String insertion = typed + text.substring(start, end) + closing;
		return new Edit(start, end, insertion, start + 1, start + 1 + end - start);
	}

	static Edit backspacePair(String text, int cursor) {
		if (cursor > 0 && cursor < text.length() && close(text.charAt(cursor - 1)) == text.charAt(cursor)
				&& (text.charAt(cursor - 1) == '"' ? !escaped(text, cursor - 1) : !insideString(text, cursor))) {
			return new Edit(cursor - 1, cursor + 1, "", cursor - 1, cursor - 1);
		}
		return null;
	}

	static Edit duplicate(String text, int anchor, int cursor) {
		int start = Math.min(anchor, cursor);
		int end = Math.max(anchor, cursor);
		if (start != end) {
			String selected = text.substring(start, end);
			return new Edit(end, end, selected, end, end + selected.length());
		}
		start = lineStart(text, cursor);
		end = lineEnd(text, cursor);
		String selected = text.substring(start, end);
		String insertion = end < text.length() ? selected + "\n" : "\n" + selected;
		int at = end < text.length() ? end + 1 : end;
		int next = cursor + selected.length() + 1;
		return new Edit(at, at, insertion, next, next);
	}

	static Edit deleteLines(String text, int anchor, int cursor) {
		int start = lineStart(text, Math.min(anchor, cursor));
		int last = Math.max(anchor, cursor);
		int end = last > start && text.charAt(last - 1) == '\n' ? last : lineEnd(text, last);
		if (end < text.length() && text.charAt(end) == '\n') {
			end++;
		} else if (end == text.length() && start > 0) {
			start--;
		}
		return new Edit(start, end, "", start, start);
	}

	static Map<Integer, Integer> bracketPairs(String text) {
		Map<Integer, Integer> pairs = new HashMap<>();
		ArrayDeque<Integer> stack = new ArrayDeque<>();
		boolean quoted = false;
		boolean escape = false;
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (escape) {
				escape = false;
				continue;
			}
			if (quoted && character == '\\') {
				escape = true;
			} else if (character == '"') {
				quoted = !quoted;
			} else if (!quoted && (character == '{' || character == '[')) {
				stack.push(index);
			} else if (!quoted && (character == '}' || character == ']')) {
				if (!stack.isEmpty() && close(text.charAt(stack.peek())) == character) {
					int opening = stack.pop();
					pairs.put(opening, index);
					pairs.put(index, opening);
				} else {
					stack.clear();
				}
			}
		}
		return Map.copyOf(pairs);
	}

	static int lineStart(String text, int position) {
		return position == 0 ? 0 : text.lastIndexOf('\n', position - 1) + 1;
	}

	static int lineEnd(String text, int position) {
		int end = text.indexOf('\n', position);
		return end < 0 ? text.length() : end;
	}

	private static char close(char opening) {
		return switch (opening) {
			case '{' -> '}';
			case '[' -> ']';
			case '"' -> '"';
			default -> 0;
		};
	}

	private static boolean escaped(String text, int position) {
		int backslashes = 0;
		while (position > 0 && text.charAt(--position) == '\\') {
			backslashes++;
		}
		return (backslashes & 1) == 1;
	}

	private static boolean insideString(String text, int position) {
		boolean quoted = false;
		boolean escape = false;
		for (int index = 0; index < position; index++) {
			char character = text.charAt(index);
			if (escape) {
				escape = false;
			} else if (quoted && character == '\\') {
				escape = true;
			} else if (character == '"') {
				quoted = !quoted;
			}
		}
		return quoted;
	}
}
