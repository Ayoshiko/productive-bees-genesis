package com.ayoshiko.productivebeesgenesis.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 对字符串、注释和正则保持词法边界；不解释模板插值和表达式。 */
final class BeeDesignerScriptTokens {
	private static final Pattern NUMBER = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

	record Token(String text, String string, int start, int end, int line) {
		boolean is(String value) {
			return string == null && text.equals(value);
		}
	}

	private BeeDesignerScriptTokens() {
	}

	static List<Token> read(String source) {
		List<Token> tokens = new ArrayList<>();
		var numbers = NUMBER.matcher(source);
		int line = 1;
		for (int index = 0; index < source.length();) {
			char value = source.charAt(index);
			if (Character.isWhitespace(value) || value == '\uFEFF') {
				if (value == '\n') {
					line++;
				}
				index++;
				continue;
			}
			if (source.startsWith("//", index) || source.startsWith("/*", index)) {
				boolean block = source.charAt(index + 1) == '*';
				int end = source.indexOf(block ? "*/" : "\n", index + 2);
				if (end < 0 && block) {
					throw new IllegalArgumentException("Unclosed script comment");
				}
				end = end < 0 ? source.length() : end + (block ? 2 : 0);
				line += (int)source.substring(index, end).chars().filter(c -> c == '\n').count();
				index = end;
				continue;
			}
			int start = index++;
			int startLine = line;
			String string = null;
			if (value == '\'' || value == '"' || value == '`') {
				boolean escaped = false;
				boolean closed = false;
				while (index < source.length()) {
					char current = source.charAt(index++);
					if ((current == '\n' || current == '\r') && value != '`') {
						throw new IllegalArgumentException("Multiline string requires review at line " + line);
					}
					if (current == '\n') {
						line++;
					}
					if (!escaped && current == value) {
						closed = true;
						break;
					}
					escaped = !escaped && current == '\\';
				}
				if (!closed) {
					throw new IllegalArgumentException("Unclosed script string at line " + startLine);
				}
				String raw = source.substring(start, index);
				string = value == '`' ? raw : decode(raw);
			} else if (value == '/' && regexContext(tokens)) {
				boolean escaped = false;
				boolean bracket = false;
				boolean closed = false;
				while (index < source.length()) {
					char current = source.charAt(index++);
					if (!escaped) {
						if (current == '[') {
							bracket = true;
						} else if (current == ']') {
							bracket = false;
						} else if (current == '/' && !bracket) {
							closed = true;
							break;
						} else if (current == '\n') {
							throw new IllegalArgumentException("Unclosed regular expression at line " + startLine);
						}
					}
					escaped = !escaped && current == '\\';
				}
				if (!closed) {
					throw new IllegalArgumentException("Unclosed regular expression at line " + startLine);
				}
				while (index < source.length() && Character.isLetter(source.charAt(index))) {
					index++;
				}
				string = source.substring(start, index);
			} else if ((Character.isDigit(value) || value == '-' || value == '.') && numbers.region(start, source.length()).lookingAt()) {
				index = numbers.end();
			} else if (Character.isJavaIdentifierStart(value)) {
				while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) {
					index++;
				}
			} else if (source.startsWith("=>", start)) {
				index++;
			}
			tokens.add(new Token(source.substring(start, index), string, start, index, startLine));
			if (tokens.size() > 200_000) {
				throw new IllegalArgumentException("Script token budget exceeded");
			}
		}
		return List.copyOf(tokens);
	}

	private static boolean regexContext(List<Token> tokens) {
		return tokens.isEmpty() || List.of("=", "(", ",", ":", "[", "=>", "return", "?").stream().anyMatch(tokens.getLast()::is);
	}

	private static String decode(String raw) {
		StringBuilder value = new StringBuilder();
		for (int index = 1; index < raw.length() - 1; index++) {
			char current = raw.charAt(index);
			if (current != '\\') {
				value.append(current);
				continue;
			}
			char escape = raw.charAt(++index);
			if (escape == 'u' || escape == 'x') {
				int length = escape == 'u' ? 4 : 2;
				if (index + length >= raw.length() - 1) {
					throw new IllegalArgumentException("Invalid script string escape");
				}
				value.append((char)Integer.parseInt(raw.substring(index + 1, index + length + 1), 16));
				index += length;
			} else {
				value.append(switch (escape) {
					case '\\', '\'', '"', '/' -> escape;
					case 'n' -> '\n';
					case 'r' -> '\r';
					case 't' -> '\t';
					case 'b' -> '\b';
					case 'f' -> '\f';
					default -> throw new IllegalArgumentException("Unsupported script escape: " + escape);
				});
			}
		}
		return value.toString();
	}
}
