package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.List;

/** 编辑器专用 Font；覆写度量和绘制，避免全局字体模组替换两者中的一半。 */
final class DesignerFont extends Font {
	private final boolean code;
	private final float size;
	private final DesignerTextSplitter splitter;

	DesignerFont(boolean code, float size) {
		super(id -> {
			throw new IllegalStateException("Editor font must use its own layout and renderer");
		}, false);
		this.code = code;
		this.size = size;
		splitter = new DesignerTextSplitter(this);
	}

	boolean code() {
		return code;
	}

	float size() {
		return size;
	}

	float baseline() {
		return 7.25F + Math.max(0, size - 8) * 0.8F;
	}

	java.awt.Font face(String text, int start, int end, Style style) {
		return DesignerFonts.face(text, start, end, style, code, size);
	}

	DesignerTextLayout layout(String text, Style style) {
		return DesignerFonts.layout(this, code ? DesignerTextLayout.plain(text, style) : DesignerTextLayout.source(text, style));
	}

	DesignerTextLayout plainLayout(String text, Style style) {
		return DesignerFonts.layout(this, DesignerTextLayout.plain(text, style));
	}

	DesignerTextLayout layout(FormattedText text, Style style) {
		return DesignerFonts.layout(this, DesignerTextLayout.source(text, style));
	}

	DesignerTextLayout layout(FormattedCharSequence text) {
		return DesignerFonts.layout(this, DesignerTextLayout.source(text));
	}

	@Override
	public int width(String text) {
		return (int) Math.ceil(splitter.stringWidth(text));
	}

	@Override
	public int width(FormattedText text) {
		return (int) Math.ceil(splitter.stringWidth(text));
	}

	@Override
	public int width(FormattedCharSequence text) {
		return (int) Math.ceil(splitter.stringWidth(text));
	}

	@Override
	public String plainSubstrByWidth(String text, int width) {
		return splitter.plainHeadByWidth(text, width, Style.EMPTY);
	}

	@Override
	public String plainSubstrByWidth(String text, int width, boolean backwards) {
		return backwards ? splitter.plainTailByWidth(text, width, Style.EMPTY) : plainSubstrByWidth(text, width);
	}

	@Override
	public FormattedText substrByWidth(FormattedText text, int width) {
		return splitter.headByWidth(text, width, Style.EMPTY);
	}

	@Override
	public int wordWrapHeight(String text, int width) {
		return splitter.splitLines(FormattedText.of(text), width, Style.EMPTY).size() * lineHeight;
	}

	@Override
	public int wordWrapHeight(FormattedText text, int width) {
		return splitter.splitLines(text, width, Style.EMPTY).size() * lineHeight;
	}

	@Override
	public List<FormattedCharSequence> split(FormattedText text, int width) {
		return splitter.splitLines(text, width, Style.EMPTY).stream().map(DesignerTextLayout::sequence).toList();
	}

	@Override
	public DesignerTextSplitter getSplitter() {
		return splitter;
	}

	@Override
	public String bidirectionalShaping(String text) {
		return text;
	}

	@Override
	public int drawInBatch(String text, float x, float y, int color, boolean shadow, Matrix4f pose,
			MultiBufferSource source, DisplayMode mode, int background, int light) {
		return DesignerFonts.draw(layout(text, Style.EMPTY), x, y, color, shadow, pose, source, background);
	}

	@Override
	public int drawInBatch(String text, float x, float y, int color, boolean shadow, Matrix4f pose,
			MultiBufferSource source, DisplayMode mode, int background, int light, boolean bidi) {
		return drawInBatch(text, x, y, color, shadow, pose, source, mode, background, light);
	}

	@Override
	public int drawInBatch(Component text, float x, float y, int color, boolean shadow, Matrix4f pose,
			MultiBufferSource source, DisplayMode mode, int background, int light) {
		return DesignerFonts.draw(layout(text, Style.EMPTY), x, y, color, shadow, pose, source, background);
	}

	@Override
	public int drawInBatch(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f pose,
			MultiBufferSource source, DisplayMode mode, int background, int light) {
		return DesignerFonts.draw(layout(text), x, y, color, shadow, pose, source, background);
	}

	@Override
	public void drawInBatch8xOutline(FormattedCharSequence text, float x, float y, int color, int outline,
			Matrix4f pose, MultiBufferSource source, int light) {
		var layout = layout(text);
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (dx != 0 || dy != 0) {
					DesignerFonts.draw(layout, x + dx * 0.5F, y + dy * 0.5F, outline, false, pose, source, 0);
				}
			}
		}
		DesignerFonts.draw(layout, x, y, color, false, pose, source, 0);
	}
}
