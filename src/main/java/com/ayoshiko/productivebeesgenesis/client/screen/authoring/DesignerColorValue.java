package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.Locale;

/** 使用 24 位 sRGB 存储，HSV 仅作为编辑表示，不改变 PB 的颜色格式。 */
public final class DesignerColorValue {
	private DesignerColorValue() {
	}

	public static int parse(String input) {
		String value = input.strip().toLowerCase(Locale.ROOT);
		if (value.startsWith("#") || value.startsWith("0x")) {
			String hex = value.substring(value.startsWith("#") ? 1 : 2);
			if (hex.matches("[0-9a-f]{3}")) {
				hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
			}
			if (!hex.matches("[0-9a-f]{6}")) {
				throw new IllegalArgumentException("designer.error.color");
			}
			return Integer.parseInt(hex, 16);
		}
		boolean hsv = value.startsWith("hsv(");
		if ((!hsv && !value.startsWith("rgb(")) || !value.endsWith(")")) {
			throw new IllegalArgumentException("designer.error.color");
		}
		String[] parts = value.substring(4, value.length() - 1).split(",", -1);
		if (parts.length != 3) {
			throw new IllegalArgumentException("designer.error.color");
		}
		try {
			if (hsv) {
				float h = channel(parts[0], 360, false);
				float s = channel(parts[1], 100, true);
				float v = channel(parts[2], 100, true);
				return rgb(h / 360, s / 100, v / 100);
			}
			int result = 0;
			for (String part : parts) {
				float channel = channel(part, 255, false);
				if (channel != Math.round(channel)) {
					throw new IllegalArgumentException("designer.error.color");
				}
				result = result << 8 | (int) channel;
			}
			return result;
		} catch (NumberFormatException invalid) {
			throw new IllegalArgumentException("designer.error.color", invalid);
		}
	}

	private static float channel(String text, int maximum, boolean percent) {
		String clean = text.strip();
		if (percent && clean.endsWith("%")) {
			clean = clean.substring(0, clean.length() - 1);
		}
		float value = Float.parseFloat(clean);
		if (!Float.isFinite(value) || value < 0 || value > maximum) {
			throw new IllegalArgumentException("designer.error.color");
		}
		return value;
	}

	public static float[] hsv(int color) {
		float r = (color >> 16 & 255) / 255F;
		float g = (color >> 8 & 255) / 255F;
		float b = (color & 255) / 255F;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float h = 0;
		if (delta != 0) {
			if (max == r) {
				h = (g - b) / delta;
			} else if (max == g) {
				h = 2 + (b - r) / delta;
			} else {
				h = 4 + (r - g) / delta;
			}
			h = (h / 6 + 1) % 1;
		}
		return new float[]{h, max == 0 ? 0 : delta / max, max};
	}

	public static int rgb(float h, float s, float v) {
		float hue = (h % 1 + 1) % 1 * 6;
		int sector = (int) hue;
		float fraction = hue - sector;
		float p = v * (1 - s);
		float q = v * (1 - s * fraction);
		float t = v * (1 - s * (1 - fraction));
		float[] channels = switch (sector) {
			case 0 -> new float[]{v, t, p};
			case 1 -> new float[]{q, v, p};
			case 2 -> new float[]{p, v, t};
			case 3 -> new float[]{p, q, v};
			case 4 -> new float[]{t, p, v};
			default -> new float[]{v, p, q};
		};
		return Math.round(channels[0] * 255) << 16 | Math.round(channels[1] * 255) << 8 | Math.round(channels[2] * 255);
	}

	public static String format(int color, String format) {
		return switch (format) {
			case "RGB" -> String.format(Locale.ROOT, "rgb(%d, %d, %d)", color >> 16 & 255, color >> 8 & 255, color & 255);
			case "HSV" -> {
				float[] hsv = hsv(color);
				yield String.format(Locale.ROOT, "hsv(%.2f, %.2f%%, %.2f%%)", hsv[0] * 360, hsv[1] * 100, hsv[2] * 100);
			}
			default -> String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
		};
	}
}
