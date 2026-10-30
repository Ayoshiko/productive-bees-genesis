package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ibm.icu.text.BreakIterator;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;

/** 字形位置和光标边界共用设计坐标；栅格精度变化不改变文字布局。 */
final class DesignerTextLayout {
	static final FontRenderContext CONTEXT = new FontRenderContext(new AffineTransform(), true, true);
	record Run(int start, int end, Style style) {}
	record Source(String text, List<Run> runs, boolean visualOrder) {}
	record Glyph(DesignerGlyphAtlas.Key key, int codepoint, float x, float y, float advance, Style style) {
		Glyph(java.awt.Font face, int id, int codepoint, float x, float y, float advance, Style style) {
			this(new DesignerGlyphAtlas.Key(face, id), codepoint, x, y, advance, style);
		}

		java.awt.Font face() {
			return key.face();
		}
	}
	record Cluster(int start, int end, float left, float right, boolean rtl) {}
	record Selection(float left, float right) {}
	final Source source;
	final List<Glyph> glyphs;
	final List<Cluster> clusters;
	final int[] boundaries;
	final float[] carets;
	final float width;
	final float baseline;
	final float size;
	private final float[] logicalAdvances;
	private int atlasRevision = -1;
	private int atlasBucket = -1;
	private DesignerGlyphAtlas.Sprite[] sprites;

	private DesignerTextLayout(Source source, List<Glyph> glyphs, List<Cluster> clusters,
			int[] boundaries, float[] carets, float width, float baseline, float size) {
		this.source = source;
		this.glyphs = List.copyOf(glyphs);
		this.clusters = List.copyOf(clusters);
		this.boundaries = boundaries;
		this.carets = carets;
		this.width = width;
		this.baseline = baseline;
		this.size = size;
		logicalAdvances = new float[source.text().length() + 1];
		for (Cluster cluster : clusters) {
			logicalAdvances[cluster.end()] += cluster.right() - cluster.left();
		}
		for (int index = 1; index < logicalAdvances.length; index++) {
			logicalAdvances[index] += logicalAdvances[index - 1];
		}
	}

	DesignerGlyphAtlas.Sprite[] sprites(DesignerGlyphAtlas atlas, int bucket) {
		if (sprites == null || atlasRevision != atlas.revision() || atlasBucket != bucket) {
			if (sprites == null) {
				sprites = new DesignerGlyphAtlas.Sprite[glyphs.size()];
			}
			for (int index = 0; index < glyphs.size(); index++) {
				Glyph glyph = glyphs.get(index);
				sprites[index] = glyph.face() == null ? null : atlas.glyph(glyph, bucket);
			}
			atlas.upload();
			atlasRevision = atlas.revision();
			atlasBucket = bucket;
		}
		atlas.touch(sprites, bucket);
		return sprites;
	}

	static Source source(String text, Style style) {
		Builder builder = new Builder();
		StringDecomposer.iterateFormatted(text, style, builder::accept);
		return builder.finish(false);
	}

	static Source source(FormattedText text, Style initial) {
		Builder builder = new Builder();
		text.visit((style, content) -> {
			StringDecomposer.iterateFormatted(content, style, builder::accept);
			return Optional.empty();
		}, initial);
		return builder.finish(false);
	}

	static Source source(FormattedCharSequence text) {
		Builder builder = new Builder();
		text.accept(builder::accept);
		return builder.finish(!(text instanceof LogicalSequence));
	}

	static Source plain(String text, Style style) {
		return new Source(text, List.of(new Run(0, text.length(), style)), false);
	}

	static DesignerTextLayout shape(Source source, DesignerFont font) {
		String text = source.text();
		int[] boundaries = boundaries(text);
		float[] carets = new float[text.length() + 1];
		Arrays.fill(carets, Float.NaN);
		List<Glyph> glyphs = new ArrayList<>();
		List<Cluster> clusters = new ArrayList<>();
		float pen = 0;
		char[] chars = text.toCharArray();
		boolean logicalBidi = !source.visualOrder() && !font.code();
		Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
		int runCount = text.isEmpty() ? 0 : bidi.getRunCount();
		if (!logicalBidi && !text.isEmpty()) {
			runCount = 1;
		}
		Object[] order = new Object[runCount];
		byte[] levels = new byte[runCount];
		for (int index = 0; index < runCount; index++) {
			order[index] = index;
			levels[index] = logicalBidi ? (byte) bidi.getRunLevel(index) : 0;
		}
		if (runCount > 1) {
			Bidi.reorderVisually(levels, 0, order, 0, runCount);
		}
		for (int runIndex = 0; runIndex < runCount; runIndex++) {
			int ordered = (int) order[runIndex];
			int start = logicalBidi ? bidi.getRunStart(ordered) : 0;
			int end = logicalBidi ? bidi.getRunLimit(ordered) : text.length();
			boolean rtl = logicalBidi && (bidi.getRunLevel(ordered) & 1) != 0;
			List<FaceRun> faces = faceRuns(source, font, boundaries, start, end);
			if (rtl) {
				java.util.Collections.reverse(faces);
			}
			for (FaceRun run : faces) {
				if (run.face() == null) {
					float left = pen;
					for (int offset = run.start(); offset < run.end();) {
						int point = text.codePointAt(offset);
						offset += Character.charCount(point);
						if (Character.getType(point) == Character.FORMAT || point >= 0xFE00 && point <= 0xFE0F
								|| point >= 0xE0100 && point <= 0xE01EF) {
							continue;
						}
						float advance = DesignerFonts.fallbackAdvance(point, run.style(), font.size());
						glyphs.add(new Glyph(null, point, point, pen, 0, advance, run.style()));
						pen += advance;
					}
					clusters.add(new Cluster(run.start(), run.end(), left, pen, rtl));
					carets[run.start()] = rtl ? pen : left;
					carets[run.end()] = rtl ? left : pen;
					continue;
				}
				GlyphVector vector = run.face().layoutGlyphVector(CONTEXT, chars, run.start(), run.end(),
						rtl ? java.awt.Font.LAYOUT_RIGHT_TO_LEFT : java.awt.Font.LAYOUT_LEFT_TO_RIGHT);
				int count = vector.getNumGlyphs();
				TreeMap<Integer, float[]> spans = new TreeMap<>();
				for (int index = 0; index < count; index++) {
					int position = floorBoundary(boundaries, Math.min(run.end() - 1, run.start() + vector.getGlyphCharIndex(index)));
					var point = vector.getGlyphPosition(index);
					var next = vector.getGlyphPosition(index + 1);
					float left = pen + (float) Math.min(point.getX(), next.getX());
					float right = pen + (float) Math.max(point.getX(), next.getX());
					float[] span = spans.computeIfAbsent(position, ignored -> new float[]{left, right});
					span[0] = Math.min(span[0], left);
					span[1] = Math.max(span[1], right);
					glyphs.add(new Glyph(run.face(), vector.getGlyphCode(index), text.codePointAt(position),
							pen + (float) point.getX(), (float) point.getY(), right - left, run.style()));
				}
				for (var entry : spans.entrySet()) {
					int first = entry.getKey();
					Integer following = spans.higherKey(first);
					int last = following == null ? run.end() : following;
					float[] span = entry.getValue();
					Cluster cluster = new Cluster(first, last, span[0], span[1], rtl);
					clusters.add(cluster);
					carets[first] = rtl ? span[1] : span[0];
					carets[last] = rtl ? span[0] : span[1];
					for (int offset = first + 1; offset < last; offset++) {
						carets[offset] = carets[first];
					}
				}
				pen += (float) vector.getGlyphPosition(count).getX();
			}
		}
		float previous = 0;
		for (int index = 0; index < carets.length; index++) {
			if (Float.isNaN(carets[index])) {
				carets[index] = previous;
			}
			previous = carets[index];
		}
		return new DesignerTextLayout(source, glyphs, clusters, boundaries, carets, pen, font.baseline(), font.size());
	}

	private record FaceRun(int start, int end, java.awt.Font face, Style style) {}

	private static List<FaceRun> faceRuns(Source source, DesignerFont font, int[] boundaries, int start, int end) {
		List<FaceRun> result = new ArrayList<>();
		int position = start;
		while (position < end) {
			int next = Math.min(end, nextBoundary(boundaries, position));
			Style style = styleAt(source, position);
			java.awt.Font face = font.face(source.text(), position, next, style);
			if (!result.isEmpty()) {
				FaceRun last = result.getLast();
				if (face != null && face.equals(last.face()) && last.style().equals(style) && last.end() == position) {
					result.set(result.size() - 1, new FaceRun(last.start(), next, face, style));
					position = next;
					continue;
				}
			}
			result.add(new FaceRun(position, next, face, style));
			position = next;
		}
		return result;
	}

	static Style styleAt(Source source, int position) {
		for (Run run : source.runs()) {
			if (position >= run.start() && position < run.end()) {
				return run.style();
			}
		}
		return Style.EMPTY;
	}

	static int[] boundaries(String text) {
		BreakIterator breaks = BreakIterator.getCharacterInstance(Locale.ROOT);
		breaks.setText(text);
		List<Integer> values = new ArrayList<>();
		for (int position = breaks.first(); position != BreakIterator.DONE; position = breaks.next()) {
			values.add(position);
		}
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	static int nextBoundary(int[] boundaries, int position) {
		int found = Arrays.binarySearch(boundaries, position);
		int next = found >= 0 ? found + 1 : -found - 1;
		return boundaries[Math.min(next, boundaries.length - 1)];
	}

	static int floorBoundary(int[] boundaries, int position) {
		int found = Arrays.binarySearch(boundaries, position);
		return boundaries[found >= 0 ? found : Math.max(0, -found - 2)];
	}

	static int floorIndex(String text, int position) {
		int clamped = Math.max(0, Math.min(text.length(), position));
		if (clamped == 0 || clamped == text.length()) {
			return clamped;
		}
		return floorBoundary(boundaries(text), clamped);
	}

	static String truncate(String text, int limit) {
		if (text.length() <= limit) {
			return text;
		}
		return text.substring(0, floorIndex(text, limit));
	}

	int boundary(int position, int direction) {
		return moveBoundary(boundaries, position, direction);
	}

	static int moveBoundary(int[] boundaries, int position, int direction) {
		int clamped = Math.max(0, Math.min(boundaries[boundaries.length - 1], position));
		int found = Arrays.binarySearch(boundaries, clamped);
		int index = found >= 0 ? found + direction : -found - (direction < 0 ? 2 : 1);
		return boundaries[Math.max(0, Math.min(boundaries.length - 1, index))];
	}

	float caret(int position) {
		return carets[Math.max(0, Math.min(carets.length - 1, position))];
	}

	List<Selection> selection(int start, int end) {
		List<Selection> spans = new ArrayList<>();
		for (Cluster cluster : clusters) {
			if (cluster.start() < end && cluster.end() > start && cluster.right() > cluster.left()) {
				spans.add(new Selection(cluster.left(), cluster.right()));
			}
		}
		spans.sort(java.util.Comparator.comparingDouble(Selection::left));
		List<Selection> merged = new ArrayList<>();
		for (Selection span : spans) {
			if (!merged.isEmpty() && span.left() <= merged.getLast().right() + 0.001F) {
				Selection last = merged.removeLast();
				merged.add(new Selection(last.left(), Math.max(last.right(), span.right())));
			} else {
				merged.add(span);
			}
		}
		return merged;
	}

	int hit(float x) {
		int selected = 0;
		float distance = Float.MAX_VALUE;
		for (int boundary : boundaries) {
			float current = Math.abs(caret(boundary) - x);
			if (current < distance) {
				selected = boundary;
				distance = current;
			}
		}
		return selected;
	}

	int head(float available) {
		return headFrom(0, available);
	}

	int headFrom(int start, float available) {
		int found = Arrays.binarySearch(boundaries, start);
		int first = found >= 0 ? found : -found - 1;
		int last = start;
		for (int index = first; index < boundaries.length; index++) {
			int boundary = boundaries[index];
			if (logicalAdvances[boundary] - logicalAdvances[start] > available + 0.001F) {
				break;
			}
			last = boundary;
		}
		return last;
	}

	int tail(float available) {
		int first = source.text().length();
		for (int index = boundaries.length - 1; index >= 0; index--) {
			int boundary = boundaries[index];
			if (width - logicalAdvances[boundary] > available + 0.001F) {
				break;
			}
			first = boundary;
		}
		return first;
	}

	static FormattedText slice(Source source, int start, int end) {
		List<FormattedText> parts = new ArrayList<>();
		for (Run run : source.runs()) {
			int first = Math.max(start, run.start());
			int last = Math.min(end, run.end());
			if (first < last) {
				parts.add(FormattedText.of(source.text().substring(first, last), run.style()));
			}
		}
		return FormattedText.composite(parts);
	}

	static Source sliceSource(Source source, int start, int end) {
		List<Run> parts = new ArrayList<>();
		for (Run run : source.runs()) {
			int first = Math.max(start, run.start());
			int last = Math.min(end, run.end());
			if (first < last) {
				parts.add(new Run(first - start, last - start, run.style()));
			}
		}
		return new Source(source.text().substring(start, end), List.copyOf(parts), source.visualOrder());
	}

	static FormattedCharSequence sequence(FormattedText text) {
		Source source = source(text, Style.EMPTY);
		return sequence(source);
	}

	static FormattedCharSequence plainSequence(String text, Style style) {
		return sequence(plain(text, style));
	}

	static FormattedCharSequence sequence(Source source) {
		return (LogicalSequence) sink -> {
			for (Run run : source.runs()) {
				for (int index = run.start(); index < run.end();) {
					int codepoint = source.text().codePointAt(index);
					if (!sink.accept(index, run.style(), codepoint)) {
						return false;
					}
					index += Character.charCount(codepoint);
				}
			}
			return true;
		};
	}

	private interface LogicalSequence extends FormattedCharSequence {}

	private static final class Builder {
		private final StringBuilder text = new StringBuilder();
		private final List<Run> runs = new ArrayList<>();

		private boolean accept(int ignored, Style style, int codepoint) {
			int start = text.length();
			text.appendCodePoint(codepoint);
			if (!runs.isEmpty() && runs.getLast().style().equals(style)) {
				Run previous = runs.removeLast();
				runs.add(new Run(previous.start(), text.length(), style));
			} else {
				runs.add(new Run(start, text.length(), style));
			}
			return true;
		}

		private Source finish(boolean visualOrder) {
			return new Source(text.toString(), List.copyOf(runs), visualOrder);
		}
	}
}
