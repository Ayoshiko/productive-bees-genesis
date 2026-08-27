package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Renders large item counts using compact suffixes and one consistent slot scale. */
public final class LargeStackCountRenderer {

	private static final float COUNT_SCALE = 0.7F;
	private static final float COUNT_INVERSE_SCALE = 1.0F / COUNT_SCALE;
	/** Keep labels above the +150 item layer without crossing Mekanism's next-window layer at +200. */
	private static final float COUNT_Z_OFFSET = 190.0F;
	private static final int TEXT_COLOR = 0xFFFFFF;

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
	 * Values below 1000 remain unchanged; larger values use K, M, G, T, P, or E.
	 */
	public static String formatCount(long count) {
		return NumberFormatter.formatCompactSlot(Math.max(0L, count));
	}

	private static void renderLabel(GuiGraphics guiGraphics, Font font, int slotX, int slotY, String text) {
		int drawX = Math.round((slotX + 18.0F - font.width(text) * COUNT_SCALE)
				* COUNT_INVERSE_SCALE);
		int drawY = Math.round((slotY + 16.0F - 3.75F) * COUNT_INVERSE_SCALE);
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(0.0F, 0.0F, COUNT_Z_OFFSET);
		pose.scale(COUNT_SCALE, COUNT_SCALE, COUNT_SCALE);
		drawShadowedText(guiGraphics, font, drawX, drawY, text);
		pose.popPose();
	}

	private static void drawShadowedText(GuiGraphics guiGraphics, Font font, int x, int y, String text) {
		guiGraphics.drawString(font, text, x, y, TEXT_COLOR, true);
	}
}
