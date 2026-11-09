package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** 原生输入模型与自绘字体共用宽度、字素边界和换行。 */
final class DesignerTextSplitter extends StringSplitter {
	private final DesignerFont font;

	DesignerTextSplitter(DesignerFont font) {
		super((codepoint, style) -> font.layout(new String(Character.toChars(codepoint)), style).width);
		this.font = font;
	}

	@Override
	public float stringWidth(String text) {
		return text == null ? 0 : font.layout(text, Style.EMPTY).width;
	}

	@Override
	public float stringWidth(FormattedText text) {
		return font.layout(text, Style.EMPTY).width;
	}

	@Override
	public float stringWidth(FormattedCharSequence text) {
		return font.layout(text).width;
	}

	@Override
	public int plainIndexAtWidth(String text, int width, Style style) {
		return font.plainLayout(text, style).head(Math.max(0, width));
	}

	@Override
	public String plainHeadByWidth(String text, int width, Style style) {
		return text.substring(0, plainIndexAtWidth(text, width, style));
	}

	@Override
	public String plainTailByWidth(String text, int width, Style style) {
		return text.substring(font.plainLayout(text, style).tail(Math.max(0, width)));
	}

	@Override
	public int formattedIndexByWidth(String text, int width, Style style) {
		int count = font.layout(text, style).head(Math.max(0, width));
		int[] visible = {0};
		int[] raw = {0};
		StringDecomposer.iterateFormatted(text, style, (index, ink, point) -> {
			visible[0] += Character.charCount(point);
			if (visible[0] > count) {
				return false;
			}
			raw[0] = index + Character.charCount(point);
			return true;
		});
		return raw[0];
	}

	@Override
	public String formattedHeadByWidth(String text, int width, Style style) {
		return text.substring(0, formattedIndexByWidth(text, width, style));
	}

	@Override
	public FormattedText headByWidth(FormattedText text, int width, Style style) {
		var layout = font.layout(text, style);
		return DesignerTextLayout.slice(layout.source, 0, layout.head(width));
	}

	@Override
	public Style componentStyleAtWidth(FormattedText text, int width) {
		var layout = font.layout(text, Style.EMPTY);
		if (width < 0 || width >= layout.width) {
			return null;
		}
		return DesignerTextLayout.styleAt(layout.source, layout.hit(width));
	}

	@Override
	public Style componentStyleAtWidth(FormattedCharSequence text, int width) {
		var layout = font.layout(text);
		if (width < 0 || width >= layout.width) {
			return null;
		}
		return DesignerTextLayout.styleAt(layout.source, layout.hit(width));
	}

	@Override
	public void splitLines(String text, int width, Style style, boolean includeDelimiter, LinePosConsumer consumer) {
		for (Line line : lines(DesignerTextLayout.plain(text, style), width)) {
			if (line.start() < text.length() || text.isEmpty()) {
				consumer.accept(style, line.start(), includeDelimiter ? line.next() : line.end());
			}
		}
	}

	@Override
	public List<FormattedText> splitLines(String text, int width, Style style) {
		return splitLines(FormattedText.of(text, style), width, style);
	}

	@Override
	public int findLineBreak(String text, int width, Style style) {
		return lines(DesignerTextLayout.plain(text, style), width).getFirst().end();
	}

	@Override
	public List<FormattedText> splitLines(FormattedText text, int width, Style style) {
		var source = DesignerTextLayout.source(text, style);
		List<FormattedText> result = new ArrayList<>();
		for (Line line : lines(source, width)) {
			result.add(DesignerTextLayout.slice(source, line.start(), line.end()));
		}
		return result;
	}

	@Override
	public void splitLines(FormattedText text, int width, Style style, BiConsumer<FormattedText, Boolean> consumer) {
		var source = DesignerTextLayout.source(text, style);
		boolean continuation = false;
		for (Line line : lines(source, width)) {
			consumer.accept(DesignerTextLayout.slice(source, line.start(), line.end()), continuation);
			continuation = line.next() == line.end();
		}
	}

	private record Line(int start, int end, int next) {}

	private List<Line> lines(DesignerTextLayout.Source source, int width) {
		List<Line> result = new ArrayList<>();
		String text = source.text();
		int start = 0;
		int paragraph = -1;
		int limit = 0;
		DesignerTextLayout layout = null;
		while (start < text.length()) {
			if (layout == null || start > limit) {
				int newline = text.indexOf('\n', start);
				limit = newline < 0 ? text.length() : newline;
				paragraph = start;
				layout = DesignerFonts.layout(font, DesignerTextLayout.sliceSource(source, paragraph, limit));
			}
			if (start == limit) {
				result.add(new Line(start, start, start + 1));
				start++;
				continue;
			}
			int end = paragraph + layout.headFrom(start - paragraph, Math.max(1, width));
			if (end == start) {
				end = paragraph + layout.boundary(start - paragraph, 1);
			}
			end = Math.min(limit, end);
			if (end < limit) {
				int space = end;
				while (space > start && text.charAt(space) != ' ') {
					space--;
				}
				if (space > start) {
					end = space;
				}
			}
			int next = end;
			if (next < text.length() && (text.charAt(next) == '\n' || text.charAt(next) == ' ')) {
				next++;
			}
			result.add(new Line(start, end, next));
			start = next;
		}
		if (text.isEmpty() || text.endsWith("\n")) {
			result.add(new Line(text.length(), text.length(), text.length()));
		}
		return result;
	}
}
