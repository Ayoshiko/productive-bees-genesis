package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GPU 图集与 CPU 像素副本各最多 32 MiB；只在帧间释放不再使用的页。 */
final class DesignerGlyphAtlas {
	private static final int PAGE_SIZE = 1024;
	private static final int MAX_PAGES = 8;
	private static final int MAX_GLYPHS_PER_BUCKET = 8192;
	private static final long IDLE_MILLIS = 30000;
	private final Map<Integer, Bucket> buckets = new HashMap<>();
	private int pages;
	private int sequence;
	private boolean exhausted;
	private long frame;
	private int revision;

	record Key(java.awt.Font face, int glyph) {}
	record Sprite(Page page, float u0, float v0, float u1, float v1,
			float left, float top, float right, float bottom) {
		void draw(VertexConsumer consumer, Matrix4f pose, float x, float baseline, int color) {
			quad(consumer, pose, x + left, baseline + top, x + right, baseline + bottom, u0, v0, u1, v1, color);
		}
	}

	Sprite glyph(DesignerTextLayout.Glyph glyph, int size) {
		Bucket bucket = buckets.computeIfAbsent(size, Bucket::new);
		bucket.lastUsed = net.minecraft.Util.getMillis();
		Key key = glyph.key();
		if (bucket.glyphs.containsKey(key)) {
			Sprite sprite = bucket.glyphs.get(key);
			if (sprite != null) {
				sprite.page().lastFrame = frame;
			}
			return sprite;
		}
		if (bucket.glyphs.size() >= MAX_GLYPHS_PER_BUCKET) {
			return null;
		}
		Sprite baked = bake(bucket, key);
		bucket.glyphs.put(key, baked);
		if (baked != null) {
			baked.page().lastFrame = frame;
		}
		return baked;
	}

	int revision() {
		return revision;
	}

	void touch(DesignerGlyphAtlas.Sprite[] sprites, int size) {
		Bucket bucket = buckets.get(size);
		if (bucket != null) {
			bucket.lastUsed = net.minecraft.Util.getMillis();
		}
		for (Sprite sprite : sprites) {
			if (sprite != null) {
				sprite.page().lastFrame = frame;
			}
		}
	}

	private Sprite bake(Bucket bucket, Key key) {
		float scale = bucket.size == 0 ? 8 : bucket.size / 9F;
		java.awt.Font face = key.face().deriveFont(key.face().getSize2D() * scale);
		var vector = face.createGlyphVector(DesignerTextLayout.CONTEXT, new int[]{key.glyph()});
		var bounds = vector.getPixelBounds(DesignerTextLayout.CONTEXT, 0, 0);
		if (bounds.isEmpty()) {
			return null;
		}
		int padding = bucket.size == 0 ? 8 : 1;
		int width = bounds.width + padding * 2;
		int height = bounds.height + padding * 2;
		if (width >= PAGE_SIZE || height >= PAGE_SIZE) {
			return null;
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		var graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, bucket.size == 0
				? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		graphics.setColor(Color.WHITE);
		graphics.drawGlyphVector(vector, padding - bounds.x, padding - bounds.y);
		graphics.dispose();
		byte[] alpha = new byte[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				alpha[y * width + x] = (byte) (image.getRGB(x, y) >>> 24);
			}
		}
		if (bucket.size == 0) {
			alpha = DesignerDistanceField.create(alpha, width, height, padding);
		}
		Page page = null;
		int[] position = null;
		for (Page candidate : bucket.pages) {
			position = candidate.allocate(width, height);
			if (position != null) {
				page = candidate;
				break;
			}
		}
		if (page == null && pages < MAX_PAGES) {
			page = new Page(bucket.size, sequence++);
			bucket.pages.add(page);
			pages++;
			position = page.allocate(width, height);
		}
		if (page == null || position == null) {
			exhausted = true;
			return null;
		}
		int x = position[0];
		int y = position[1];
		page.write(x, y, width, height, alpha);
		return new Sprite(page, x / (float) PAGE_SIZE, y / (float) PAGE_SIZE,
				(x + width) / (float) PAGE_SIZE, (y + height) / (float) PAGE_SIZE,
				(bounds.x - padding) / scale, (bounds.y - padding) / scale,
				(bounds.x + bounds.width + padding) / scale, (bounds.y + bounds.height + padding) / scale);
	}

	void upload() {
		for (Bucket bucket : buckets.values()) {
			for (Page page : bucket.pages) {
				page.upload();
			}
		}
	}

	void betweenFrames() {
		long cutoff = net.minecraft.Util.getMillis() - IDLE_MILLIS;
		Iterator<Bucket> iterator = buckets.values().iterator();
		while (iterator.hasNext()) {
			Bucket bucket = iterator.next();
			if (bucket.lastUsed < cutoff) {
				pages -= bucket.pages.size();
				bucket.close();
				iterator.remove();
				revision++;
			}
		}
		if (exhausted) {
			Page oldest = null;
			Bucket owner = null;
			for (Bucket bucket : buckets.values()) {
				for (Page page : bucket.pages) {
					if (page.lastFrame < frame && (oldest == null || page.lastFrame < oldest.lastFrame)) {
						oldest = page;
						owner = bucket;
					}
				}
			}
			if (oldest != null) {
				Page released = oldest;
				owner.glyphs.values().removeIf(sprite -> sprite == null || sprite.page() == released);
				owner.pages.remove(released);
				released.close();
				pages--;
				revision++;
				buckets.values().forEach(bucket -> bucket.glyphs.values().removeIf(java.util.Objects::isNull));
			}
		}
		exhausted = false;
		frame++;
	}

	void close() {
		buckets.values().forEach(Bucket::close);
		buckets.clear();
		pages = 0;
		revision++;
		exhausted = false;
	}

	long bytes() {
		return (long) pages * PAGE_SIZE * PAGE_SIZE * 4;
	}

	private static final class Bucket {
		private final int size;
		private final Map<Key, Sprite> glyphs = new LinkedHashMap<>();
		private final List<Page> pages = new ArrayList<>();
		private long lastUsed;

		private Bucket(int size) {
			this.size = size;
		}

		private void close() {
			pages.forEach(Page::close);
			pages.clear();
			glyphs.clear();
		}
	}

	static final class Page {
		private final ResourceLocation id;
		private final NativeImage pixels;
		private final DynamicTexture texture;
		final RenderType renderType;
		private int nextX = 2;
		private int nextY = 2;
		private int rowHeight;
		private final boolean smooth;
		private int dirtyLeft = PAGE_SIZE;
		private int dirtyTop = PAGE_SIZE;
		private int dirtyRight;
		private int dirtyBottom;
		private long lastFrame;

		private Page(int bucket, int sequence) {
			smooth = bucket == 0;
			id = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "designer-font/" + sequence);
			pixels = new NativeImage(PAGE_SIZE, PAGE_SIZE, true);
			pixels.fillRect(0, 0, PAGE_SIZE, PAGE_SIZE, 0x00FFFFFF);
			texture = new DynamicTexture(pixels);
			texture.setFilter(bucket == 0, false);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			renderType = DesignerFontRenderTypes.create(id, bucket == 0);
		}

		private int[] allocate(int width, int height) {
			if (nextX + width + 2 > PAGE_SIZE) {
				nextX = 2;
				nextY += rowHeight + 2;
				rowHeight = 0;
			}
			if (nextY + height + 2 > PAGE_SIZE) {
				return null;
			}
			int[] result = {nextX, nextY};
			nextX += width + 2;
			rowHeight = Math.max(rowHeight, height);
			return result;
		}

		private void write(int x, int y, int width, int height, byte[] alpha) {
			for (int row = 0; row < height; row++) {
				for (int column = 0; column < width; column++) {
					pixels.setPixelRGBA(x + column, y + row, (alpha[row * width + column] & 255) << 24 | 0xFFFFFF);
				}
			}
			dirtyLeft = Math.min(dirtyLeft, x);
			dirtyTop = Math.min(dirtyTop, y);
			dirtyRight = Math.max(dirtyRight, x + width);
			dirtyBottom = Math.max(dirtyBottom, y + height);
		}

		private void upload() {
			if (dirtyRight <= dirtyLeft || dirtyBottom <= dirtyTop) {
				return;
			}
			texture.bind();
			pixels.upload(0, dirtyLeft, dirtyTop, dirtyLeft, dirtyTop, dirtyRight - dirtyLeft, dirtyBottom - dirtyTop,
					smooth, true, false, false);
			dirtyLeft = PAGE_SIZE;
			dirtyTop = PAGE_SIZE;
			dirtyRight = 0;
			dirtyBottom = 0;
		}

		private void close() {
			Minecraft.getInstance().getTextureManager().release(id);
		}
	}

	static void quad(VertexConsumer consumer, Matrix4f pose, float left, float top, float right, float bottom,
			float u0, float v0, float u1, float v1, int color) {
		consumer.addVertex(pose, left, top, 0).setUv(u0, v0).setColor(color);
		consumer.addVertex(pose, left, bottom, 0).setUv(u0, v1).setColor(color);
		consumer.addVertex(pose, right, bottom, 0).setUv(u1, v1).setColor(color);
		consumer.addVertex(pose, right, top, 0).setUv(u1, v0).setColor(color);
	}
}
