package com.ayoshiko.productivebeesgenesis.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * Renders large item counts using the same compact, fixed-size style as the
 * AE2 honeycomb pull window.
 *
 * <p>The text is always rendered at a fixed scale instead of being scaled to
 * its own width. This keeps counts from different magnitude bands visually
 * consistent while the compact K/M/B form keeps the label inside an item slot.
 */
public final class LargeStackCountRenderer {

	private static final float COUNT_SCALE = 0.7F;
	private static final float COUNT_INVERSE_SCALE = 1.0F / COUNT_SCALE;
	private static final int SHADOW_COLOR = 0x414141;
	private static final int TEXT_COLOR = 0xFFFFFF;
	private static final int FULL_BRIGHT_LIGHT = 0xF000F0;

	private LargeStackCountRenderer() {
	}

	/**
	 * Renders a count at the top-left coordinate of an 18x18 item slot.
	 * Counts of zero and one intentionally do not render a label.
	 */
	public static void renderCountAt(GuiGraphics guiGraphics, Font font, int slotX, int slotY, long count) {
		if (count <= 1L) {
			return;
		}
		renderLabel(guiGraphics, font, slotX, slotY, formatCount(count));
	}

	/**
	 * Formats a count using the AE2 honeycomb pull window's compact notation.
	 * Values below 1000 remain unchanged; larger values use K, M, or B.
	 */
	public static String formatCount(long count) {
		if (count < 1000L) {
			return Long.toString(count);
		}
		if (count < 1_000_000L) {
			return formatWithSuffix(count, 1_000L, "K");
		}
		if (count < 1_000_000_000L) {
			return formatWithSuffix(count, 1_000_000L, "M");
		}
		return formatWithSuffix(count, 1_000_000_000L, "B");
	}

	private static String formatWithSuffix(long count, long divisor, String suffix) {
		double value = (double) count / (double) divisor;
		if (value < 10.0D) {
			String formatted = String.format(Locale.ROOT, "%.1f", value);
			if (formatted.endsWith(".0")) {
				formatted = formatted.substring(0, formatted.length() - 2);
			}
			return formatted + suffix;
		}
		return Math.round(value) + suffix;
	}

	private static void renderLabel(GuiGraphics guiGraphics, Font font, int slotX, int slotY, String text) {
		int drawX = (int) ((slotX + 18.0F - font.width(text) * COUNT_SCALE) * COUNT_INVERSE_SCALE);
		int drawY = (int) ((slotY + 16.0F - 3.75F) * COUNT_INVERSE_SCALE);
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(0.0F, 0.0F, 300.0F);
		pose.scale(COUNT_SCALE, COUNT_SCALE, COUNT_SCALE);
		drawShadowedText(pose.last().pose(), font, drawX, drawY, text);
		pose.popPose();
	}

	private static void drawShadowedText(Matrix4f matrix, Font font, int x, int y, String text) {
		MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
		font.drawInBatch(text, x + 1, y + 1, SHADOW_COLOR, false, matrix, buffer,
				Font.DisplayMode.NORMAL, 0, FULL_BRIGHT_LIGHT);
		font.drawInBatch(text, x, y, TEXT_COLOR, false, matrix, buffer,
				Font.DisplayMode.NORMAL, 0, FULL_BRIGHT_LIGHT);
		buffer.endBatch();
	}
}
