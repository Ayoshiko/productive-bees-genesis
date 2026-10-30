package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Compact machine counts stay inside the icon and prefer an integer screen-pixel scale. */
public final class LargeStackCountRenderer {

	private static final float COUNT_SCALE = 0.75F;
	/** Keep labels above the +150 item layer without crossing Mekanism's next-window layer at +200. */
	private static final float COUNT_Z_OFFSET = 190.0F;
	private static final int TEXT_COLOR = 0xFFFFFF;

	private LargeStackCountRenderer() {
	}

	/**
	 * Renders a count at the top-left coordinate of the 16x16 item icon.
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
	 * Values below 1000 remain unchanged; larger values use K, M, G, T, P, or E.
	 */
	public static String formatCount(long count) {
		return NumberFormatter.formatCompactSlot(Math.max(0L, count));
	}

	private static void renderLabel(GuiGraphics guiGraphics, Font font, int slotX, int slotY, String text) {
		PoseStack pose = guiGraphics.pose();
		var matrix = pose.last().pose();
		float deviceScale = (float) Minecraft.getInstance().getWindow().getGuiScale()
				* (float) Math.hypot(matrix.m00(), matrix.m10());
		if (!Float.isFinite(deviceScale) || deviceScale <= 0) {
			return;
		}
		float fitted = Math.min(COUNT_SCALE, 16F / Math.max(1, font.width(text)));
		float pixels = Math.max(1, Math.round(deviceScale * fitted));
		if (font.width(text) * pixels / deviceScale > 16) {
			pixels = Math.max(1, (float) Math.floor(16F * deviceScale / Math.max(1, font.width(text))));
		}
		float scale = Math.min(16F / Math.max(1, font.width(text)), pixels / deviceScale);
		float left = slotX + 16 - font.width(text) * scale;
		float top = slotY + 16 - font.lineHeight * scale;
		pose.pushPose();
		try {
			pose.translate(left, top, COUNT_Z_OFFSET);
			pose.scale(scale, scale, 1);
			guiGraphics.drawString(font, text, 0, 0, TEXT_COLOR, true);
		} finally {
			pose.popPose();
		}
	}
}
