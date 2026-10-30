package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mixin.client.DesignerFontAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;

import java.awt.FontFormatException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅供编辑器使用的字体、布局和图集；资源重载清代际，不持有世界或实体。 */
final class DesignerFonts {
	static final ResourceLocation BODY_ID = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "designer_body");
	static final ResourceLocation CODE_ID = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "designer_code");
	private static final ResourceLocation BODY_FILE = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "font/designer_cjk.otf");
	private static final ResourceLocation CODE_FILE = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "font/designer_mono.ttf");
	private static final ResourceLocation LATIN_FILE = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "font/designer_sans.ttf");
	private static final int MAX_LAYOUTS = 2048;
	private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
	private static final DesignerFont BODY = new DesignerFont(false, 8.5F);
	private static final DesignerFont CODE = new DesignerFont(true, 8.5F);
	private static final DesignerFont TITLE = new DesignerFont(false, 10.5F);
	private static final DesignerGlyphAtlas ATLAS = new DesignerGlyphAtlas();
	private static final LinkedHashMap<Key, DesignerTextLayout> LAYOUTS = new LinkedHashMap<>(256, 0.75F, true);
	private static final Map<FaceKey, java.awt.Font> FACES = new LinkedHashMap<>();
	private static java.awt.Font body;
	private static java.awt.Font code;
	private static java.awt.Font latin;
	private static java.awt.Font systemSans;
	private static java.awt.Font systemCode;
	private static boolean invalidated;
	private static int cachedBytes;
	private static int generation;
	private static String language = "";
	private static String fontMode = "";

	private record Key(DesignerFont font, DesignerTextLayout.Source source) {}
	private record FaceKey(java.awt.Font base, int style, float size) {}
	private record LoadedFaces(java.awt.Font body, java.awt.Font code, java.awt.Font latin,
			java.awt.Font systemSans, java.awt.Font systemCode) {}

	private DesignerFonts() {
	}

	static Font body() {
		return DesignerPreferences.text("font_mode").equals("vanilla") ? Minecraft.getInstance().font : BODY;
	}

	static Font code() {
		return DesignerPreferences.text("font_mode").equals("vanilla") ? Minecraft.getInstance().font : CODE;
	}

	static Font title() {
		return DesignerPreferences.text("font_mode").equals("vanilla") ? Minecraft.getInstance().font : TITLE;
	}

	static PreparableReloadListener reloadListener() {
		return new SimplePreparableReloadListener<LoadedFaces>() {
			@Override
			protected LoadedFaces prepare(ResourceManager manager, ProfilerFiller profiler) {
				return new LoadedFaces(load(manager, BODY_FILE), load(manager, CODE_FILE), load(manager, LATIN_FILE),
						logicalFace(java.awt.Font.SANS_SERIF), logicalFace(java.awt.Font.MONOSPACED));
			}

			@Override
			protected void apply(LoadedFaces loaded, ResourceManager manager, ProfilerFiller profiler) {
				body = loaded.body();
				code = loaded.code();
				latin = loaded.latin();
				systemSans = loaded.systemSans();
				systemCode = loaded.systemCode();
				invalidate();
			}
		};
	}

	private static java.awt.Font load(ResourceManager manager, ResourceLocation file) {
		try (var stream = manager.open(file)) {
			return java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream);
		} catch (IOException | FontFormatException failure) {
			ProductiveBeesGenesis.LOGGER.error("编辑器字体加载失败，保留原生字形回退：{}", file, failure);
			return null;
		}
	}

	private static java.awt.Font logicalFace(String family) {
		java.awt.Font font = new java.awt.Font(family, java.awt.Font.PLAIN, 1);
		// 在资源准备线程触发逻辑字体初始化，绘制不枚举系统字体或读取字体文件。
		font.canDisplay('中');
		return font;
	}

	static java.awt.Font face(String text, int start, int end, Style style, boolean monospaced, float size) {
		ResourceLocation id = style.getFont();
		if (!id.equals(Style.DEFAULT_FONT) && !id.equals(BODY_ID) && !id.equals(CODE_ID)) {
			return null;
		}
		boolean codeText = monospaced || id.equals(CODE_ID);
		java.awt.Font base = codeText ? code : latin;
		if (!supports(base, text, start, end)) {
			base = body;
		}
		if (!supports(base, text, start, end)) {
			base = codeText ? systemCode : systemSans;
		}
		if (!supports(base, text, start, end) && codeText) {
			base = systemSans;
		}
		if (!supports(base, text, start, end)) {
			return null;
		}
		int flags = java.awt.Font.PLAIN;
		if (style.isBold()) {
			flags |= java.awt.Font.BOLD;
		}
		if (style.isItalic()) {
			flags |= java.awt.Font.ITALIC;
		}
		FaceKey key = new FaceKey(base, flags, size);
		return FACES.computeIfAbsent(key, value -> value.base().deriveFont(value.style(), value.size()));
	}

	private static boolean supports(java.awt.Font face, String text, int start, int end) {
		if (face == null) {
			return false;
		}
		for (int offset = start; offset < end;) {
			int point = text.codePointAt(offset);
			if (!face.canDisplay(point)) {
				return false;
			}
			offset += Character.charCount(point);
		}
		return true;
	}

	private static FontSet fallback(Style style) {
		ResourceLocation id = style.getFont();
		if (id.equals(Style.DEFAULT_FONT) || id.equals(CODE_ID)) {
			id = BODY_ID;
		}
		return ((DesignerFontAccess) Minecraft.getInstance().font).productivebeesgenesis$fontSet(id);
	}

	static float fallbackAdvance(int codepoint, Style style, float size) {
		return fallback(style).getGlyphInfo(codepoint, false).getAdvance(style.isBold()) * size / 8.5F;
	}

	static DesignerTextLayout layout(DesignerFont font, DesignerTextLayout.Source source) {
		Key key = new Key(font, source);
		DesignerTextLayout existing = LAYOUTS.get(key);
		if (existing != null) {
			return existing;
		}
		DesignerTextLayout created = DesignerTextLayout.shape(source, font);
		int bytes = layoutBytes(created);
		if (bytes <= MAX_TEXT_BYTES) {
			while (!LAYOUTS.isEmpty() && (LAYOUTS.size() >= MAX_LAYOUTS || cachedBytes + bytes > MAX_TEXT_BYTES)) {
				var removed = LAYOUTS.pollFirstEntry();
				cachedBytes -= layoutBytes(removed.getValue());
			}
			LAYOUTS.put(key, created);
			cachedBytes += bytes;
		}
		return created;
	}

	private static int layoutBytes(DesignerTextLayout layout) {
		return 160 + layout.source.text().length() * 2 + layout.glyphs.size() * 88 + layout.clusters.size() * 40
				+ layout.carets.length * 12 + layout.boundaries.length * 4 + layout.source.runs().size() * 40;
	}

	static int draw(DesignerTextLayout layout, float x, float y, int color, boolean shadow,
			Matrix4f pose, MultiBufferSource source, int background) {
		int alpha = color >>> 24;
		if (alpha == 0) {
			color |= 0xFF000000;
		}
		int bucket = bucket(pose);
		var sprites = layout.sprites(ATLAS, bucket);
		if (background != 0) {
			fill(source, pose, x - 1, y - 1, x + layout.width + 1, y + 10, background);
		}
		if (shadow) {
			int shade = color & 0xFF000000 | (color & 0xFCFCFC) >> 2;
			emit(layout, sprites, pose, source, x + 0.7F, y + 0.7F, shade, false, bucket);
		}
		emit(layout, sprites, pose, source, x, y, color, true, bucket);
		return (int) Math.ceil(x + layout.width);
	}

	private static void emit(DesignerTextLayout layout, DesignerGlyphAtlas.Sprite[] sprites, Matrix4f pose,
			MultiBufferSource source, float x, float y, int color, boolean styledColor, int bucket) {
		float density = bucket / 9F;
		for (int index = 0; index < layout.glyphs.size(); index++) {
			var glyph = layout.glyphs.get(index);
			var sprite = sprites[index];
			float left = x + glyph.x();
			float top = y + layout.baseline + glyph.y();
			if (bucket != 0) {
				left = snap(left, pose.m30(), pose.m00(), density);
				top = snap(top, pose.m31(), pose.m11(), density);
			}
			int ink = styledColor && glyph.style().getColor() != null
					? color & 0xFF000000 | glyph.style().getColor().getValue() : color;
			if (sprite != null) {
				sprite.draw(source.getBuffer(sprite.page().renderType), pose, left, top, ink);
			} else if (glyph.face() == null || glyph.advance() > 0 && !Character.isWhitespace(glyph.codepoint())) {
				drawFallback(glyph, layout, source, pose, left, y, ink);
			}
			if (glyph.style().isUnderlined() || glyph.style().isStrikethrough()) {
				if (glyph.style().isUnderlined()) {
					fill(source, pose, left, y + layout.baseline + 1, left + glyph.advance(), y + layout.baseline + 1.6F, ink);
				}
				if (glyph.style().isStrikethrough()) {
					fill(source, pose, left, y + layout.baseline - layout.size * 0.35F,
							left + glyph.advance(), y + layout.baseline - layout.size * 0.35F + 0.6F, ink);
				}
			}
		}
	}

	private static void drawFallback(DesignerTextLayout.Glyph glyph, DesignerTextLayout layout,
			MultiBufferSource source, Matrix4f pose, float x, float y, int color) {
		var set = fallback(glyph.style());
		var baked = set.getGlyph(glyph.codepoint());
		float height = layout.size / 8.5F;
		float advance = set.getGlyphInfo(glyph.codepoint(), false).getAdvance(glyph.style().isBold());
		float horizontal = advance > 0 ? glyph.advance() / advance : height;
		Matrix4f transform = new Matrix4f(pose).translate(x, y + layout.baseline - 7 * height, 0).scale(horizontal, height, 1);
		var consumer = source.getBuffer(baked.renderType(Font.DisplayMode.NORMAL));
		float red = (color >> 16 & 255) / 255F;
		float green = (color >> 8 & 255) / 255F;
		float blue = (color & 255) / 255F;
		float alpha = (color >>> 24) / 255F;
		baked.render(glyph.style().isItalic(), 0, 0, transform, consumer, red, green, blue, alpha, 0xF000F0);
		if (glyph.style().isBold()) {
			baked.render(glyph.style().isItalic(), 1, 0, transform, consumer, red, green, blue, alpha, 0xF000F0);
		}
	}

	private static float snap(float value, float translation, float scale, float density) {
		float offset = Math.abs(scale) < 1.0e-5F ? 0 : translation / scale;
		return Math.round((value + offset) * density) / density - offset;
	}

	static void fill(MultiBufferSource source, Matrix4f pose, float left, float top, float right, float bottom, int color) {
		var consumer = source.getBuffer(RenderType.gui());
		consumer.addVertex(pose, left, top, 0).setColor(color);
		consumer.addVertex(pose, left, bottom, 0).setColor(color);
		consumer.addVertex(pose, right, bottom, 0).setColor(color);
		consumer.addVertex(pose, right, top, 0).setColor(color);
	}

	private static int bucket(Matrix4f pose) {
		float scaleX = (float) Math.hypot(pose.m00(), pose.m10());
		float scaleY = (float) Math.hypot(pose.m01(), pose.m11());
		float deviceHeight = 9 * scaleY * (float) Minecraft.getInstance().getWindow().getGuiScale();
		String mode = DesignerPreferences.text("font_mode");
		if (!DesignerFontRenderTypes.available()) {
			return Math.max(6, Math.min(96, Math.round(deviceHeight)));
		}
		if (mode.equals("sdf") || Math.abs(pose.m01()) > 0.0001F || Math.abs(pose.m10()) > 0.0001F
				|| Math.abs(scaleX - scaleY) > 0.0001F || deviceHeight > 96
				|| Math.abs(deviceHeight - Math.round(deviceHeight)) > 0.05F) {
			return 0;
		}
		return Math.max(6, Math.round(deviceHeight));
	}

	static void selection(GuiGraphics graphics, Font font, String text, int start, int end, int x, int y, int color) {
		if (!(font instanceof DesignerFont designer)) {
			graphics.fill(x + font.width(text.substring(0, start)), y,
					x + font.width(text.substring(0, end)), y + 9, color);
			return;
		}
		var layout = designer.plainLayout(text, Style.EMPTY);
		for (var cluster : layout.clusters) {
			if (cluster.start() < end && cluster.end() > start) {
				graphics.fill((int) Math.floor(x + cluster.left()), y,
						(int) Math.ceil(x + cluster.right()), y + 9, color);
			}
		}
	}

	static float caret(Font font, String text, int position) {
		if (font instanceof DesignerFont designer) {
			return designer.plainLayout(text, Style.EMPTY).caret(position);
		}
		return font.width(text.substring(0, Math.max(0, Math.min(text.length(), position))));
	}

	static int hit(Font font, String text, float x) {
		if (font instanceof DesignerFont designer) {
			return designer.plainLayout(text, Style.EMPTY).hit(x);
		}
		return font.plainSubstrByWidth(text, Math.max(0, Math.round(x))).length();
	}

	static void drawPlain(GuiGraphics graphics, Font font, String text, float x, float y, int color) {
		graphics.drawString(font, DesignerTextLayout.plainSequence(text, Style.EMPTY), x, y, color, false);
	}

	static void invalidate() {
		LAYOUTS.clear();
		FACES.clear();
		cachedBytes = 0;
		generation++;
		invalidated = true;
	}

	static void betweenFrames() {
		String currentLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
		if (!language.equals(currentLanguage)) {
			language = currentLanguage;
			invalidate();
		}
		String currentMode = DesignerPreferences.text("font_mode");
		if (!fontMode.equals(currentMode)) {
			fontMode = currentMode;
			invalidate();
		}
		if (invalidated) {
			ATLAS.close();
			invalidated = false;
		}
		ATLAS.betweenFrames();
	}

	static int generation() {
		return generation;
	}

	static long atlasBytes() {
		return ATLAS.bytes();
	}
}
