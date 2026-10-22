package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
			case "button_hover" -> palette.hover();
			case "button_disabled" -> DesignerPalette.mix(palette.panel(), palette.button(), 0.35F);
			default -> palette.button();
		};
		boolean bordered = !name.equals("card");
		surface(graphics, x, y, width, height, fill, bordered ? palette.border() : fill);
	}

	static void surface(GuiGraphics graphics, int x, int y, int width, int height, int fill, int border) {
		if (width < 4 || height < 4) {
			return;
		}
		graphics.fill(x + 2, y, x + width - 2, y + height, border);
		graphics.fill(x, y + 2, x + width, y + height - 2, border);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
	}

	static void selected(GuiGraphics graphics, int x, int y, int width, int height) {
		surface(graphics, x, y, width, height, palette.selected(), DesignerPalette.mix(palette.border(), palette.accent(), 0.45F));
	}

	static void errorFrame(GuiGraphics graphics, int x, int y, int width, int height) {
		graphics.renderOutline(x, y, width, height, palette.error());
	}

	static void apply() {
		palette = DesignerPreferences.palette();
		light = palette.light();
		DesignerFormScreen.FOREGROUND = palette.text();
		DesignerFormScreen.MUTED = palette.muted();
		DesignerFormScreen.ACCENT = palette.accent();
	}

	static void scissor(GuiGraphics graphics, int x, int y, int right, int bottom) {
		var matrix = graphics.pose().last().pose();
		graphics.enableScissor((int) Math.floor(x * matrix.m00() + matrix.m30()), (int) Math.floor(y * matrix.m11() + matrix.m31()),
				(int) Math.ceil(right * matrix.m00() + matrix.m30()), (int) Math.ceil(bottom * matrix.m11() + matrix.m31()));
	}

	static void scrollbar(GuiGraphics graphics, int x, int y, int width, int height, int thumbY, int thumbHeight, boolean hovered) {
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

	static void toggle() {
		DesignerPreferences.set("theme", light ? "dark" : "light");
		DesignerPreferences.save(message -> {
			if (!message.equals("designer.status.preferences_saved") && Minecraft.getInstance().screen instanceof DesignerFormScreen screen) {
				screen.status = message;
			}
		});
	}

	static class Action extends Button {
		private boolean primary;
		protected boolean selected;
		private final DesignerMotionValue hover = new DesignerMotionValue();
		private final DesignerMotionValue press = new DesignerMotionValue();
		private long renderedAt;
		private long pressedUntil;
		private long rippleAt;
		private double hotspotX;
		private double hotspotY;

		Action(int x, int y, int width, Component message, Runnable action) {
			this(x, y, width, message, ignored -> action.run());
		}

		Action(int x, int y, int width, Component message, Button.OnPress action) {
			super(x, y, width, 20, message, action, DEFAULT_NARRATION);
			setTooltipDelay(java.time.Duration.ofMillis((long) DesignerPreferences.number("tooltip_ms")));
			if (Minecraft.getInstance().font.width(message) > width - 10) {
				setTooltip(Tooltip.create(message));
			}
		}

		Action primary() {
			primary = true;
			return this;
		}

		protected int textColor() {
			if (!active) {
				return palette.muted();
			}
			return primary ? DesignerPalette.contrast(palette.accent()) : palette.text();
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			pressedUntil = net.minecraft.Util.getMillis() + 70;
			press.snap(1);
			rippleAt = net.minecraft.Util.getMillis();
			hotspotX = mouseX - getX();
			hotspotY = mouseY - getY();
			super.onClick(mouseX, mouseY);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int offset = renderSurface(graphics);
			var font = Minecraft.getInstance().font;
			String text = getMessage().getString();
			if (font.width(text) > width - 10) {
				text = font.plainSubstrByWidth(text, Math.max(0, width - 10 - font.width("…"))) + "…";
			}
			graphics.drawString(font, text, getX() + (width - font.width(text)) / 2, getY() + (height - 8) / 2 + offset,
					textColor(), false);
		}

		protected int renderSurface(GuiGraphics graphics) {
			long now = net.minecraft.Util.getMillis();
			double seconds = renderedAt == 0 ? 0 : Math.max(0, now - renderedAt) / 1000.0;
			renderedAt = now;
			boolean animate = DesignerPreferences.animate();
			hover.advance(active && isHoveredOrFocused() ? 1 : 0, seconds, 0.18, animate);
			press.advance(now < pressedUntil ? 1 : 0, seconds, 0.18, animate);
			float amount = (float) Math.min(1, DesignerPreferences.number("motion_amount") / 8);
			int inset = (int) Math.round(press.position() * amount);
			int offset = 0;
			int fill = primary && active ? palette.accent() : selected && active ? palette.selected() : palette.button();
			int border = primary && active ? palette.accent() : selected && active
					? DesignerPalette.mix(palette.border(), palette.accent(), 0.45F) : palette.border();
			if (!active) {
				fill = DesignerPalette.mix(palette.panel(), palette.button(), 0.35F);
			} else {
				fill = DesignerPalette.mix(fill, primary ? palette.accent() : palette.hover(), (float) hover.position() * 0.75F);
			}
			surface(graphics, getX() + inset, getY() + inset, width - inset * 2, height - inset * 2, fill, border);
			if (animate && now - rippleAt < 260) {
				DesignerGlyphs.ripple(graphics, getX(), getY(), width, height, hotspotX, hotspotY,
						(now - rippleAt) / 260.0, palette.accent());
			}
			if (isFocused() && active) {
				surface(graphics, getX() - 1, getY() - 1, width + 2, height + 2, 0, palette.accent());
				surface(graphics, getX() + inset, getY() + inset, width - inset * 2, height - inset * 2, fill, border);
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
			var font = Minecraft.getInstance().font;
			String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, width - 10));
			int color = !active ? palette.muted() : selected ? palette.text() : palette.muted();
			graphics.drawString(font, text, getX() + (width - font.width(text)) / 2, getY() + (height - 8) / 2, color, false);
		}

	}

	enum Symbol {
		THEME("toggle_theme"), PIN("window_pin"), SETTINGS("settings"), CLOSE("close_editor"), BACK("back"), COPY("copy"), DELETE("delete_bee");

		private final String label;

		Symbol(String label) {
			this.label = label;
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
			var font = Minecraft.getInstance().font;
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
				graphics.drawString(Minecraft.getInstance().font, "?", getX() + width / 2 - 2, getY() + 6, 0xFFFF8B83, false);
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
			surface(graphics, trackX + 2, trackY + 2, 8, 7, palette.light() ? 0xFFFFFFFF : 0xFFE6ECF7, palette.light() ? 0xFFE5E9EF : 0xFFCCD6E8);
			graphics.pose().popPose();
			String text = Minecraft.getInstance().font.plainSubstrByWidth(getMessage().getString(), width - 39);
			graphics.drawString(Minecraft.getInstance().font, text, getX() + 6, getY() + (height - 8) / 2, DesignerFormScreen.FOREGROUND, false);
		}
	}

	static final class Input extends EditBox {
		private boolean invalid;

		void invalid(boolean value) {
			invalid = value;
			setTextColor(value ? palette.error() : palette.text());
		}
		Input(Font font, int x, int y, int width, int height, Component label) {
			super(font, x, y - 2, width, Math.max(9, height - 9), label);
			setBordered(false);
			setTextColor(DesignerFormScreen.FOREGROUND);
			setTextColorUneditable(DesignerFormScreen.MUTED);
			setTextShadow(false);
			setFormatter((text, position) -> net.minecraft.util.FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY));
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			panel(graphics, "input", getX() - 4, getY() - 3, getWidth() + 8, getHeight() + 5);
			if (invalid || isFocused()) {
				graphics.renderOutline(getX() - 4, getY() - 3, getWidth() + 8, getHeight() + 5, invalid ? palette.error() : palette.accent());
			}
			DesignerTheme.scissor(graphics, getX(), getY() - 1, getX() + getWidth(), getY() + getHeight() + 1);
			try {
				super.renderWidget(graphics, mouseX, mouseY, partialTick);
			} finally {
				graphics.disableScissor();
			}
		}
	}
}
