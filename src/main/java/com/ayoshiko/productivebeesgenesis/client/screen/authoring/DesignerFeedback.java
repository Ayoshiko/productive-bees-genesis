package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Set;

/** 只有明确的普通成功反馈会消退；错误、未知应用与待恢复状态始终保留。 */
final class DesignerFeedback {
	private static final Set<String> TRANSIENT = Set.of(
			"designer.status.draft_saved", "designer.status.preferences_saved", "designer.status.path_copied",
			"designer.status.output_moved", "designer.status.preferences_reset", "designer.status.theme_applied");
	private String observed = "";
	private long started;
	private Component message = Component.empty();

	boolean observe(String status, long now) {
		if (status.equals(observed)) {
			return transientStatus(status);
		}
		observed = status;
		if (transientStatus(status)) {
			started = now;
			message = DesignerFormScreen.statusMessage(status);
		}
		return transientStatus(status);
	}

	static boolean transientStatus(String status) {
		return TRANSIENT.contains(status);
	}

	static boolean error(String status) {
		return status.startsWith("designer.error.") || status.startsWith("Invalid") || status.startsWith("Unsupported");
	}

	void render(GuiGraphics graphics, int left, int bottom, int available, long now) {
		long elapsed = now - started;
		if (message.getString().isBlank() || elapsed < 0 || elapsed > 3200) {
			return;
		}
		Font font = DesignerFonts.body();
		int width = Math.min(available, font.width(message) + 32);
		int x = left + (available - width) / 2;
		int y = bottom - 24;
		if (DesignerPreferences.animate()) {
			y += (int) Math.round(3 * (1 - DesignerMotionValue.easeOut(Math.min(1, elapsed / 160.0))));
		}
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, 420);
		DesignerTheme.panel(graphics, "card", x, y, width, 21);
		DesignerTheme.statusIcon(graphics, x + 7, y + 6, false, DesignerTheme.colors().success());
		graphics.drawString(font, font.plainSubstrByWidth(message.getString(), width - 28), x + 22, y + 6,
				DesignerTheme.colors().text(), false);
		graphics.pose().popPose();
	}
}
