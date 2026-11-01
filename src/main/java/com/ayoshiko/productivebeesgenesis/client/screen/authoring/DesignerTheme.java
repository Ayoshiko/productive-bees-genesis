package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringUtil;

import java.util.function.Supplier;

/** 编辑器统一配色、柔和选中态和键盘焦点；原生材质保留物品图标。 */
final class DesignerTheme {
	static boolean light;
	private static DesignerPalette palette = DesignerPalette.preset("light");
	private DesignerTheme() {
	}

	static DesignerPalette colors() {
		return palette;
	}

	static void panel(GuiGraphics graphics, String name, int x, int y, int width, int height) {
		int fill = switch (name) {
			case "panel" -> palette.panel();
			case "card" -> palette.card();
			case "input", "slot" -> palette.input();
			case "button_hover", "card_hover" -> palette.hover();
			case "button_disabled" -> DesignerPalette.mix(palette.panel(), palette.button(), 0.35F);
			default -> palette.button();
		};
		boolean card = name.equals("card") || name.equals("card_hover");
		boolean bordered = !card;
		int radius = DesignerMaterials.CONTROL_RADIUS;
		if (name.equals("panel")) {
			radius = DesignerMaterials.WINDOW_RADIUS;
		} else if (card) {
			radius = DesignerMaterials.CARD_RADIUS;
		}
		if (name.equals("panel") || card && height > 24) {
			DesignerMaterials.shadow(graphics, x, y, width, height, name.equals("panel"));
		}
		if (name.equals("panel") && DesignerPreferences.text("surface_material").equals("glass")) {
			fill = fill & 0xFFFFFF | 0xE8000000;
		}
		int border = bordered ? palette.border() : DesignerPalette.mix(fill, palette.border(), 0.25F);
		DesignerMaterials.surface(graphics, x, y, width, height, radius, fill, border);
		if (name.equals("panel")) {
			graphics.fill(x + radius, y + 1, x + width - radius, y + 2,
					light ? 0x70FFFFFF : 0x183C526F);
		}
	}

	static void surface(GuiGraphics graphics, int x, int y, int width, int height, int fill, int border) {
		if (width < 4 || height < 4) {
			return;
		}
		DesignerMaterials.surface(graphics, x, y, width, height, DesignerMaterials.CONTROL_RADIUS, fill, border);
	}

	static void statusIcon(GuiGraphics graphics, int x, int y, boolean error, int color) {
		statusIcon(graphics, x, y, error ? Symbol.WARNING : Symbol.CHECK, color);
	}

	static void statusIcon(GuiGraphics graphics, int x, int y, Symbol symbol, int color) {
		graphics.pose().pushPose();
		graphics.pose().translate(x + 4, y + 4, 0);
		graphics.pose().scale(0.75F, 0.75F, 1);
		DesignerGlyphs.draw(graphics, symbol, color);
		graphics.pose().popPose();
	}

	static void badge(GuiGraphics graphics, Component text, int x, int y, int width, boolean error) {
		int color = error ? palette.error() : palette.accent();
		surface(graphics, x, y, width, 14, DesignerPalette.mix(palette.card(), color, 0.06F),
				DesignerPalette.mix(palette.border(), color, 0.25F));
		statusIcon(graphics, x + 4, y + 3, error ? Symbol.WARNING : Symbol.DOT, color);
		var font = DesignerFonts.body();
		graphics.drawString(font, font.plainSubstrByWidth(text.getString(), width - 23), x + 18, y + 3, palette.text(), false);
	}

	static void dropTarget(GuiGraphics graphics, int x, int y, int width, int height, boolean accepted, boolean replacing, boolean hovered) {
		int color = accepted ? palette.accent() : palette.error();
		if (hovered) {
			DesignerMaterials.mask(graphics, x, y, width, height, DesignerMaterials.CONTROL_RADIUS, color & 0xFFFFFF | 0x28000000);
		}
		stroke(graphics, x, y, width, height, color);
		if (width >= 26 && height >= 14) {
			graphics.pose().pushPose();
			graphics.pose().translate(x + width - 8, y + height / 2F, 0);
			graphics.pose().scale(0.65F, 0.65F, 1);
			Symbol symbol = replacing ? Symbol.REPLACE : Symbol.ADD;
			if (!accepted) {
				symbol = Symbol.WARNING;
			}
			DesignerGlyphs.draw(graphics, symbol, color);
			graphics.pose().popPose();
		}
	}

	static void selected(GuiGraphics graphics, int x, int y, int width, int height) {
		surface(graphics, x, y, width, height, palette.selected(), DesignerPalette.mix(palette.border(), palette.accent(), 0.45F));
	}

	static void errorFrame(GuiGraphics graphics, int x, int y, int width, int height) {
		stroke(graphics, x, y, width, height, DesignerPalette.mix(palette.border(), palette.error(), 0.6F));
	}

	static void stroke(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        DesignerMaterials.surface(graphics, x, y, width, height, DesignerMaterials.CONTROL_RADIUS, 0, color);
    }

	static void apply() {
		palette = DesignerPreferences.palette();
		light = palette.light();
		DesignerFormScreen.FOREGROUND = palette.text();
		DesignerFormScreen.MUTED = palette.muted();
		DesignerFormScreen.ACCENT = palette.accent();
		DesignerAeSkin.refresh();
	}

	static void scissor(GuiGraphics graphics, int x, int y, int right, int bottom) {
		var matrix = graphics.pose().last().pose();
		graphics.enableScissor((int) Math.floor(x * matrix.m00() + matrix.m30()), (int) Math.floor(y * matrix.m11() + matrix.m31()),
				(int) Math.ceil(right * matrix.m00() + matrix.m30()), (int) Math.ceil(bottom * matrix.m11() + matrix.m31()));
	}

	static void scrollbar(GuiGraphics graphics, int x, int y, int width, int height, int thumbY, int thumbHeight, boolean hovered) {
		if (DesignerAeSkin.enabled()) {
			DesignerAeSkin.frame(graphics, x, y, width, height, palette.input(), true);
			DesignerAeSkin.frame(graphics, x, thumbY, width, thumbHeight, hovered ? palette.hover() : palette.button(), false);
			return;
		}
		panel(graphics, "input", x, y, width, height);
		panel(graphics, hovered ? "button_hover" : "button", x, thumbY, width, thumbHeight);
		graphics.fill(x + 2, thumbY + 3, x + width - 2, thumbY + thumbHeight - 3, DesignerFormScreen.ACCENT);
		if (thumbHeight >= 22) {
			int middle = thumbY + thumbHeight / 2;
			for (int offset = -2; offset <= 2; offset += 2) {
				graphics.fill(x + 2, middle + offset, x + width - 2, middle + offset + 1, light ? 0xAAFFFFFF : 0xAA263641);
			}
		}
	}

	static void autoScrollMarker(GuiGraphics graphics, int x, int y, int direction) {
		graphics.pose().pushPose();
		try {
			graphics.pose().translate(x, y, 400);
			int fill = palette.card() & 0xFFFFFF | 0xE6000000;
			int border = DesignerPalette.mix(palette.card(), palette.border(), 0.65F) & 0xFFFFFF | 0xB0000000;
			DesignerMaterials.surface(graphics, -8, -8, 16, 16, DesignerMaterials.CARD_RADIUS, fill, border);
			DesignerMaterials.mask(graphics, -1, -1, 2, 2, DesignerMaterials.CONTROL_RADIUS, palette.muted());
			graphics.pose().scale(0.6F, 0.5F, 1);
			graphics.pose().translate(0, -8, 0);
			DesignerGlyphs.draw(graphics, Symbol.UP, direction < 0 ? palette.accent() : palette.muted());
			graphics.pose().translate(0, 16, 0);
			DesignerGlyphs.draw(graphics, Symbol.DOWN, direction > 0 ? palette.accent() : palette.muted());
		} finally {
			graphics.pose().popPose();
		}
	}

	static void toggle() {
		String theme = DesignerPreferences.text("theme");
		String next = switch (theme) {
			case "ae_light" -> "ae_dark";
			case "ae_dark" -> "ae_light";
			case "custom_light" -> "custom_dark";
			case "custom_dark" -> "custom_light";
			default -> light ? "dark" : "light";
		};
		DesignerPreferences.set("theme", next);
		DesignerPreferences.save(message -> {
			if (!message.equals("designer.status.preferences_saved") && Minecraft.getInstance().screen instanceof DesignerFormScreen screen) {
				screen.status = message;
			}
		});
	}

	static class Action extends Button {
		private boolean primary;
		private boolean danger;
		private boolean subtle;
		protected boolean selected;
		private final DesignerMotionValue hover = new DesignerMotionValue();
		private final DesignerMotionValue press = new DesignerMotionValue();
		private long renderedAt;
		private long pressedUntil;
		private long rippleAt;
		private double hotspotX;
		private double hotspotY;
		private long hintUntil;
		private boolean hintError;

		Action(int x, int y, int width, Component message, Runnable action) {
			this(x, y, width, message, ignored -> action.run());
		}

		Action(int x, int y, int width, Component message, Button.OnPress action) {
			super(x, y, width, 20, message, action, DEFAULT_NARRATION);
			setTooltipDelay((int) DesignerPreferences.number("tooltip_ms"));
			if (message.getContents() instanceof TranslatableContents translated) {
				String key = translated.getKey();
				danger = key.startsWith("designer.remove_") || key.equals("designer.confirm_remove")
						|| key.equals("designer.delete_stage") || key.equals("designer.recycle_purge_world")
						|| key.equals("designer.recycle_delete_local") || key.equals("designer.discard_edit_close")
						|| key.equals("designer.recycle_discard_pending") || key.equals("designer.discard_recovery");
			}
			if (DesignerFonts.body().width(message) > width - 10) {
				setTooltip(Tooltip.create(message));
			}
		}

		Action primary() {
			primary = true;
			return this;
		}

		Action danger() {
			danger = true;
			return this;
		}

		Action subtle() {
			subtle = true;
			return this;
		}

		void highlight(boolean error, long until) {
			hintError = error;
			hintUntil = until;
		}

		protected int textColor() {
			if (!active) {
				return palette.muted();
			}
			if (primary && !DesignerAeSkin.enabled()) {
				return DesignerPalette.contrast(danger ? palette.error() : palette.accent());
			}
			return danger ? palette.error() : palette.text();
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			pressedUntil = net.minecraft.Util.getMillis() + 70;
			if (DesignerPreferences.enabled("animations") && !DesignerPreferences.enabled("reduce_motion")) {
				press.advance(1, 0.012, 0.07, true);
			}
			rippleAt = net.minecraft.Util.getMillis();
			hotspotX = mouseX - getX();
			hotspotY = mouseY - getY();
			super.onClick(mouseX, mouseY);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int offset = renderSurface(graphics);
			Symbol glyph = Symbol.control(getMessage().getString());
			if (glyph != null && width <= 36) {
				graphics.pose().pushPose();
				graphics.pose().translate(getX() + width / 2F, getY() + height / 2F, 0);
				DesignerGlyphs.draw(graphics, glyph, textColor());
				graphics.pose().popPose();
				return;
			}
			var font = DesignerFonts.body();
			String text = getMessage().getString();
			if (font.width(text) > width - 10) {
				text = font.plainSubstrByWidth(text, Math.max(0, width - 10 - font.width("…"))) + "…";
			}
			graphics.drawString(font, text, getX() + (width - font.width(text)) / 2, DesignerFonts.centeredY(font, text, getY(), height) + offset,
					textColor(), false);
		}

		protected int renderSurface(GuiGraphics graphics) {
			long now = net.minecraft.Util.getMillis();
			double seconds = renderedAt == 0 ? 0 : Math.max(0, now - renderedAt) / 1000.0;
			renderedAt = now;
			boolean animate = DesignerPreferences.animate() && !DesignerPreferences.text("motion_feedback").equals("none");
			hover.advance(active && isHoveredOrFocused() ? 1 : 0, seconds, 0.18, animate);
			press.advance(now < pressedUntil ? 1 : 0, seconds, 0.18, animate);
			float amount = (float) Math.min(1, DesignerPreferences.number("motion_amount") / 8);
			int inset = (int) Math.round(press.position() * amount);
			int offset = 0;
			int accent = danger ? palette.error() : palette.accent();
			int fill = primary && active ? accent : selected && active ? palette.selected() : palette.button();
			if (danger && !primary && active) {
				fill = DesignerPalette.mix(fill, accent, 0.045F);
			}
			if (DesignerAeSkin.enabled() && primary) {
				fill = palette.button();
			}
			int border = primary && active ? accent : selected && active
					? DesignerPalette.mix(palette.border(), palette.accent(), 0.45F) : palette.border();
			if (!active) {
				fill = DesignerPalette.mix(palette.panel(), palette.button(), 0.35F);
			} else {
				fill = DesignerPalette.mix(fill, primary ? accent : palette.hover(), (float) hover.position() * 0.75F);
			}
			if (now < hintUntil) {
				int color = hintError ? palette.error() : palette.accent();
				fill = DesignerPalette.mix(fill, color, hintError ? 0.07F : 0.1F);
				border = DesignerPalette.mix(border, color, 0.6F);
			}
			if (DesignerAeSkin.enabled()) {
				DesignerAeSkin.button(graphics, getX(), getY(), width, height, fill, active && isHovered(), isFocused(), now < pressedUntil);
				if (primary && active) {
					graphics.fill(getX() + 3, getY() + 3, getX() + 5, getY() + height - 3, accent);
				}
			} else if (subtle && !primary) {
				int alpha = active ? (int) Math.round(hover.position() * 150) : 0;
				DesignerMaterials.mask(graphics, getX(), getY(), width, height, DesignerMaterials.CONTROL_RADIUS,
						alpha << 24 | palette.hover() & 0xFFFFFF);
				offset = Math.min(1, inset);
			} else {
				surface(graphics, getX() + inset, getY() + inset, width - inset * 2, height - inset * 2, fill, border);
			}
			if (animate && DesignerPreferences.text("motion_feedback").equals("ripple") && now - rippleAt < 260) {
				DesignerGlyphs.ripple(graphics, getX(), getY(), width, height, hotspotX, hotspotY,
						(now - rippleAt) / 260.0, palette.accent());
			}
			if (isFocused() && active) {
				stroke(graphics, getX(), getY(), width, height, DesignerPalette.mix(border, palette.accent(), 0.65F));
			}
			return offset;
		}
	}

	static final class Tab extends Action {
		private final DesignerMotionValue hover = new DesignerMotionValue();
		private long frameAt;
		Tab(int x, int y, int width, Component label, boolean selected, Runnable action) {
			super(x, y, width, label, action);
			this.selected = selected;
			setTooltip(Tooltip.create(label));
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			long now = net.minecraft.Util.getMillis();
			hover.advance(active && isHoveredOrFocused() ? 1 : 0, frameAt == 0 ? 0 : (now - frameAt) / 1000.0, 0.14, DesignerPreferences.animate());
			frameAt = now;
			if (!selected && hover.position() > 0.01) {
				int color = (int) (hover.position() * 48) << 24 | palette.accent() & 0xFFFFFF;
				DesignerGlyphs.rounded(graphics, getX(), getY(), width, height, 5, color);
			}
			if (isFocused() && active) {
				stroke(graphics, getX(), getY(), width, height, palette.accent());
			}
			var font = DesignerFonts.body();
			String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, width - 10));
			int color = !active ? palette.muted() : selected ? palette.text() : palette.muted();
			graphics.drawString(font, text, getX() + (width - font.width(text)) / 2, DesignerFonts.centeredY(font, text, getY(), height), color, false);
		}

	}

	static final class Grip extends Action {
		Grip(int x, int y, Runnable action) {
			super(x, y, 20, Component.literal("⋮⋮"), action);
			setTooltip(Tooltip.create(DesignerFormScreen.tr("output_drag_help")));
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			if (isHoveredOrFocused()) {
				DesignerMaterials.mask(graphics, getX(), getY(), width, height, DesignerMaterials.CONTROL_RADIUS,
						palette.accent() & 0xFFFFFF | 0x18000000);
			}
			graphics.pose().pushPose();
			graphics.pose().translate(getX() + width / 2F, getY() + height / 2F, 0);
			DesignerGlyphs.draw(graphics, Symbol.GRIP, isHoveredOrFocused() ? palette.accent() : palette.muted());
			graphics.pose().popPose();
			if (isFocused()) {
				stroke(graphics, getX(), getY(), width, height, palette.accent());
			}
		}
	}

	static final class GroupHeader extends Action {
		private final boolean expanded;
		private final FormattedCharSequence heading;

		GroupHeader(int x, int y, int width, Component label, boolean expanded, Runnable action) {
			super(x, y, width, label, action);
			this.expanded = expanded;
			var lines = DesignerFonts.body().split(label.copy().withStyle(style -> style.withBold(true)), Math.max(1, width - 28));
			heading = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
			setTooltip(Tooltip.create(label));
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			if (isHoveredOrFocused()) {
				DesignerMaterials.mask(graphics, getX() + 3, getY() + 1, width - 6, height - 2, DesignerMaterials.CONTROL_RADIUS,
						palette.accent() & 0xFFFFFF | 0x10000000);
			}
			var font = DesignerFonts.body();
			graphics.drawString(font, heading, getX() + 6, DesignerFonts.centeredY(font, heading, getY(), height), palette.text(), false);
			graphics.pose().pushPose();
			graphics.pose().translate(getX() + width - 10, getY() + height / 2F, 0);
			if (expanded) {
				graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
			}
			graphics.pose().scale(0.65F, 0.65F, 1);
			DesignerGlyphs.draw(graphics, Symbol.NEXT, palette.muted());
			graphics.pose().popPose();
			if (isFocused()) {
				stroke(graphics, getX(), getY(), width, height, palette.accent());
			}
		}
	}

	enum Symbol {
		THEME("toggle_theme"), PIN("window_pin"), SETTINGS("settings"), CLOSE("close_editor"), BACK("back"), COPY("copy"), DELETE("delete_bee"),
		ADD("add"), MINUS("remove"), CHECK("valid"), WARNING("error"), REPLACE("drag_replace"), SEARCH("search"),
		UP("output_move_up"), DOWN("output_move_down"), PREVIOUS("previous"), NEXT("next"), UNDO("undo"), REDO("redo"), GRIP("output_drag_help"), DOT("status"), REFRESH("refresh");

		private final String label;

		Symbol(String label) {
			this.label = label;
		}

		static Symbol control(String text) {
			return switch (text) {
				case "+" -> ADD;
				case "−", "-" -> MINUS;
				case "×" -> CLOSE;
				case "↑" -> UP;
				case "↓" -> DOWN;
				case "‹", "<", "←" -> PREVIOUS;
				case "›", ">", "→" -> NEXT;
				case "↶" -> UNDO;
				case "↷" -> REDO;
				case "⋮⋮" -> GRIP;
				default -> null;
			};
		}
	}

	static class Icon extends Action {
		final Symbol symbol;
		private final DesignerMotionValue emphasis = new DesignerMotionValue();
		private final DesignerMotionValue click = new DesignerMotionValue();
		private long lastFrame;
		private long clickedAt;

		Icon(int x, int y, Symbol symbol, Runnable action) {
			super(x, y, 22, DesignerFormScreen.tr(symbol.label), action);
			this.symbol = symbol;
			setTooltip(Tooltip.create(DesignerFormScreen.tr(symbol.label)));
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			clickedAt = net.minecraft.Util.getMillis();
			click.snap(1);
			super.onClick(mouseX, mouseY);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			long now = net.minecraft.Util.getMillis();
			double seconds = lastFrame == 0 ? 0 : (now - lastFrame) / 1000.0;
			lastFrame = now;
			if (DesignerAeSkin.enabled()) {
				DesignerAeSkin.button(graphics, getX(), getY(), width, height, palette.button(), isHovered(), isFocused(), now - clickedAt < 65);
				if (!DesignerAeSkin.icon(graphics, symbol, getX() + (width - 16) / 2, getY() + (height - 16) / 2)) {
					graphics.pose().pushPose();
					graphics.pose().translate(getX() + width / 2F, getY() + height / 2F, 0);
					DesignerGlyphs.draw(graphics, symbol, palette.text());
					graphics.pose().popPose();
				}
				return;
			}
			emphasis.advance(isHoveredOrFocused() ? 1 : 0, seconds, 0.16, DesignerPreferences.animate());
			click.advance(now - clickedAt < 65 ? 1 : 0, seconds, 0.18, DesignerPreferences.animate());
			float hover = (float) emphasis.position();
			int base = selected ? palette.selected() : palette.panel();
			int fill = DesignerPalette.mix(base, palette.hover(), hover * 0.85F);
			int border = DesignerPalette.mix(base, palette.border(), hover * 0.55F);
			if (symbol == Symbol.CLOSE || symbol == Symbol.DELETE) {
				fill = DesignerPalette.mix(base, palette.error(), hover * 0.12F);
			}
			DesignerGlyphs.rounded(graphics, getX(), getY(), width, height, 5, border);
			DesignerGlyphs.rounded(graphics, getX() + 1, getY() + 1, width - 2, height - 2, 4, fill);
			int color = selected ? palette.accent() : DesignerPalette.mix(palette.muted(), palette.text(), 0.5F + hover * 0.5F);
			if (symbol == Symbol.CLOSE || symbol == Symbol.DELETE) {
				color = DesignerPalette.mix(color, palette.error(), hover * 0.7F);
			}
			float amplitude = (float) Math.min(1, DesignerPreferences.number("motion_amount") / 8);
			float scale = 1 - (float) click.position() * 0.08F * amplitude;
			graphics.pose().pushPose();
			graphics.pose().translate(getX() + width / 2F, getY() + height / 2F, 0);
			graphics.pose().scale(scale, scale, 1);
			DesignerGlyphs.draw(graphics, symbol, color);
			graphics.pose().popPose();
		}
	}

	static final class Pin extends Icon {

		Pin(int x, int y, boolean pinned, Runnable action) {
			super(x, y, Symbol.PIN, action);
			selected = pinned;
			setMessage(DesignerFormScreen.tr(pinned ? "window_unpin" : "window_pin"));
			setTooltip(Tooltip.create(DesignerFormScreen.tr(pinned ? "window_unpin_help" : "window_pin_help")));
		}

	}

	static final class Step extends Action {
		private final int index;
		Step(int x, int y, int width, int index, boolean selected, Component label, Runnable action) {
			super(x, y, width, label, action);
			this.index = index;
			this.selected = selected;
			setTooltip(Tooltip.create(label));
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			if (selected) {
				selected(graphics, getX(), getY(), width, height);
			} else {
				panel(graphics, isHoveredOrFocused() ? "button_hover" : "card", getX(), getY(), width, height);
			}
			int color = active ? DesignerFormScreen.FOREGROUND : DesignerFormScreen.MUTED;
			var font = DesignerFonts.body();
			graphics.drawString(font, String.format(java.util.Locale.ROOT, "%02d", index + 1), getX() + 9, getY() + 6,
					selected ? DesignerFormScreen.ACCENT : DesignerFormScreen.MUTED, false);
			graphics.drawString(font, font.plainSubstrByWidth(getMessage().getString(), width - 35), getX() + 28, getY() + 6, color, false);
		}
	}

	static final class Swatch extends Action {
		private final Supplier<String> color;

		Swatch(int x, int y, int width, Supplier<String> color, Runnable action) {
			super(x, y, width, DesignerFormScreen.tr("choose_color"), action);
			this.color = color;
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			panel(graphics, isHoveredOrFocused() ? "button_hover" : "button", getX(), getY(), width, height);
			String value = color.get();
			if (value.matches("#[0-9a-fA-F]{6}")) {
				graphics.fill(getX() + 5, getY() + 4, getX() + width - 5, getY() + height - 4,
						0xFF000000 | Integer.parseInt(value.substring(1), 16));
			} else {
				graphics.drawString(DesignerFonts.body(), "?", getX() + width / 2 - 2, getY() + 6, 0xFFFF8B83, false);
			}
		}
	}

	static final class Switch extends Action {
		private boolean value;
		private float position;
		private final DesignerMotionValue slide = new DesignerMotionValue();
		private long lastFrame;

		Switch(int x, int y, int width, boolean value, Runnable action) {
			super(x, y, width, DesignerFormScreen.tr(value ? "on" : "off"), action);
			this.value = value;
			position = value ? 1 : 0;
			slide.snap(position);
		}

		void value(boolean selected) {
			value = selected;
			setMessage(DesignerFormScreen.tr(value ? "on" : "off"));
		}

		@Override
		public void onPress() {
			value(!value);
			super.onPress();
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			panel(graphics, isHoveredOrFocused() ? "button_hover" : "input", getX(), getY(), width, height);
			long now = net.minecraft.Util.getMillis();
			double seconds = lastFrame == 0 ? 0 : Math.max(0, now - lastFrame) / 1000.0;
			lastFrame = now;
			float target = value ? 1 : 0;
			slide.advance(target, seconds, 0.2, DesignerPreferences.animate());
			position = (float) slide.position();
			if (Math.abs(target - position) < 0.002F) {
				position = target;
			}
			int trackX = getX() + width - 31;
			int trackY = getY() + (height - 10) / 2;
			int track = DesignerPalette.mix(palette.border(), palette.accent(), position);
			surface(graphics, trackX, trackY, 26, 11, track, track);
			graphics.pose().pushPose();
			graphics.pose().translate(position * 14, 0, 0);
			int stretch = DesignerPreferences.animate() ? (int) Math.min(2, Math.abs(slide.velocity()) * 0.15) : 0;
			surface(graphics, trackX + 2 - stretch / 2, trackY + 2, 8 + stretch, 7,
					palette.light() ? 0xFFFFFFFF : 0xFFE6ECF7, palette.light() ? 0xFFE5E9EF : 0xFFCCD6E8);
			graphics.pose().popPose();
			String text = DesignerFonts.body().plainSubstrByWidth(getMessage().getString(), width - 39);
			graphics.drawString(DesignerFonts.body(), text, getX() + 6, getY() + (height - 8) / 2, DesignerFormScreen.FOREGROUND, false);
		}
	}

	static final class Input extends EditBox {
		private boolean invalid;
		private Object fieldOwner;
		private String fieldKey;
		private final Font editorFont;
		private int anchor;
		private float scrollX;
		private long focusedAt;
		private int editableColor = DesignerFormScreen.FOREGROUND;
		private int disabledColor = DesignerFormScreen.MUTED;
		private boolean editable = true;
		private Component hint;
		private String suggestion;
		private int characterLimit = 32;

		void bind(Object owner, String key) {
			fieldOwner = owner;
			fieldKey = key;
		}

		boolean bound() {
			return fieldOwner != null;
		}

		boolean sameField(Input other) {
			return fieldOwner != null && fieldOwner == other.fieldOwner && fieldKey.equals(other.fieldKey);
		}

		State state() {
			return new State(getCursorPosition(), anchor, scrollX);
		}

		void restore(State state) {
			setCursorPosition(Math.min(getValue().length(), state.cursor()));
			setHighlightPos(Math.min(getValue().length(), state.anchor()));
			scrollX = state.scroll();
			ensureCursorVisible();
		}

		record State(int cursor, int anchor, float scroll) {}

		void invalid(boolean value) {
			invalid = value;
			setTextColor(value ? palette.error() : palette.text());
		}

		@Override
		public void setResponder(java.util.function.Consumer<String> responder) {
			super.setResponder(value -> {
				invalid(false);
				responder.accept(value);
			});
		}
		Input(Font font, int x, int y, int width, int height, Component label) {
			super(font, x, y - 2, width, Math.max(9, height - 9), label);
			editorFont = font;
			setBordered(false);
			setTextColor(DesignerFormScreen.FOREGROUND);
			setTextColorUneditable(DesignerFormScreen.MUTED);

			setFormatter((text, position) -> net.minecraft.util.FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY));
		}

		@Override
		public void setHighlightPos(int position) {
			anchor = DesignerTextLayout.floorIndex(getValue(), position);
			super.setHighlightPos(anchor);
		}

		@Override
		public void setCursorPosition(int position) {
			super.setCursorPosition(DesignerTextLayout.floorIndex(getValue(), position));
		}

		@Override
		public void setValue(String value) {
			super.setValue(DesignerTextLayout.truncate(value, characterLimit));
		}

		@Override
		public void setMaxLength(int limit) {
			if (limit < 0) {
				throw new IllegalArgumentException("Character limit cannot be negative");
			}
			characterLimit = limit;
			int length = DesignerTextLayout.truncate(getValue(), limit).length();
			if (length < getValue().length()) {
				super.setMaxLength(length);
			}
			super.setMaxLength(limit);
			setCursorPosition(getCursorPosition());
			setHighlightPos(anchor);
		}

		@Override
		public void insertText(String text) {
			int available = characterLimit - getValue().length() + getHighlighted().length();
			super.insertText(DesignerTextLayout.truncate(net.minecraft.SharedConstants.filterText(text), Math.max(0, available)));
		}

		@Override
		public void setFocused(boolean focused) {
			boolean previous = isFocused();
			super.setFocused(focused);
			if (isFocused() && !previous) {
				focusedAt = net.minecraft.Util.getMillis();
			}
		}

		@Override
		public void setTextColor(int color) {
			super.setTextColor(color);
			editableColor = color;
		}

		@Override
		public void setTextColorUneditable(int color) {
			super.setTextColorUneditable(color);
			disabledColor = color;
		}

		@Override
		public void setEditable(boolean value) {
			super.setEditable(value);
			editable = value;
		}

		@Override
		public void setHint(Component value) {
			super.setHint(value);
			hint = value;
		}

		@Override
		public void setSuggestion(String value) {
			super.setSuggestion(value);
			suggestion = value;
		}

		@Override
		public void moveCursor(int amount) {
			int position = getCursorPosition();
			int[] boundaries = DesignerTextLayout.boundaries(getValue());
			for (int step = 0; step < Math.abs(amount); step++) {
				position = DesignerTextLayout.moveBoundary(boundaries, position, Integer.signum(amount));
			}
			moveCursorTo(position);
		}

		@Override
		public void deleteChars(int amount) {
			int position = getCursorPosition();
			int[] boundaries = DesignerTextLayout.boundaries(getValue());
			for (int step = 0; step < Math.abs(amount); step++) {
				position = DesignerTextLayout.moveBoundary(boundaries, position, Integer.signum(amount));
			}
			setHighlightPos(position); insertText("");
			if (getHighlighted().isEmpty()) {
				anchor = getCursorPosition();
			}
		}

		private void ensureCursorVisible() {
			float cursor = DesignerFonts.caret(editorFont, getValue(), getCursorPosition());
			float available = Math.max(1, getWidth() - (invalid ? 12 : 2));
			if (cursor < scrollX) {
				scrollX = cursor;
			} else if (cursor > scrollX + available) {
				scrollX = cursor - available;
			}
			float width = editorFont.getSplitter().stringWidth(DesignerTextLayout.plainSequence(getValue(), Style.EMPTY));
			scrollX = Math.max(0, Math.min(scrollX, Math.max(0, width - available)));
		}

		@Override
		public int getScreenX(int position) {
			return getX() + Math.round(DesignerFonts.caret(editorFont, getValue(), position) - scrollX);
		}

		@Override
		public void onClick(double x, double y) {
			ensureCursorVisible();
			((com.ayoshiko.productivebeesgenesis.mixin.client.DesignerEditBoxAccess)(Object)this).genesis$selecting(Screen.hasShiftDown()); moveCursorTo(DesignerFonts.hit(editorFont, getValue(), (float) (x - getX() + scrollX)));
			focusedAt = net.minecraft.Util.getMillis();
		}

		@Override
		protected void onDrag(double x, double y, double dx, double dy) {
			((com.ayoshiko.productivebeesgenesis.mixin.client.DesignerEditBoxAccess)(Object)this).genesis$selecting(true); moveCursorTo(DesignerFonts.hit(editorFont, getValue(), (float) (x - getX() + scrollX)));
			ensureCursorVisible();
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			if (!isVisible()) {
				return;
			}
			int border = invalid ? DesignerPalette.mix(palette.border(), palette.error(), 0.6F)
					: isFocused() ? DesignerPalette.mix(palette.border(), palette.accent(), 0.65F) : palette.border();
			int fill = invalid ? DesignerPalette.mix(palette.input(), palette.error(), 0.04F) : palette.input();
			if (DesignerAeSkin.enabled()) {
				DesignerAeSkin.frame(graphics, getX() - 4, getY() - 3, getWidth() + 8, getHeight() + 5, fill, true);
				if (invalid || isFocused()) {
					stroke(graphics, getX() - 4, getY() - 3, getWidth() + 8, getHeight() + 5, border);
				}
			} else {
				surface(graphics, getX() - 4, getY() - 3, getWidth() + 8, getHeight() + 5, fill, border);
			}
			DesignerTheme.scissor(graphics, getX(), getY() - 1, getX() + getWidth(), getY() + getHeight() + 1);
			try {
				ensureCursorVisible();
				int origin = getX() - Math.round(scrollX);
				int color = editable ? editableColor : disabledColor;
				if (anchor != getCursorPosition()) {
					DesignerFonts.selection(graphics, editorFont, getValue(), Math.min(anchor, getCursorPosition()),
							Math.max(anchor, getCursorPosition()), origin, getY(), palette.accent() & 0xFFFFFF | 0x40000000);
				}
				DesignerFonts.drawPlain(graphics, editorFont, getValue(), origin, getY(), color);
				if (getValue().isEmpty() && hint != null && !isFocused()) {
					graphics.drawString(editorFont, hint, getX(), getY(), palette.muted(), false);
				}
				if (suggestion != null && getCursorPosition() == getValue().length()) {
					DesignerFonts.drawPlain(graphics, editorFont, suggestion,
							origin + DesignerFonts.caret(editorFont, getValue(), getValue().length()), getY(), palette.muted());
				}
				if (isFocused() && (net.minecraft.Util.getMillis() - focusedAt) / 500 % 2 == 0) {
					int caret = getScreenX(getCursorPosition());
					graphics.fill(caret, getY() - 1, caret + 1, getY() + 9, invalid ? palette.error() : palette.accent());
				}
			} finally {
				graphics.disableScissor();
			}
			if (invalid) {
				DesignerTheme.statusIcon(graphics, getX() + getWidth() - 8, getY() + 1, true, palette.error());
			}
		}
	}
}
