package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 原位弹层拦截输入，关闭时不把同一次点击传给背后的字段。 */
final class DesignerDropdown {
	private final List<String> values;
	private final Predicate<String> enabled;
	private final Consumer<String> selected;
	private final Rect2i bounds;
	private final int visible;
	private int first;
	private int highlighted;
	private String prefix = "";
	private long typedAt;
	private boolean closed;
	private int lastMouseX = Integer.MIN_VALUE;
	private int lastMouseY = Integer.MIN_VALUE;
	private final long openedAt = net.minecraft.Util.getMillis();
	private final DesignerMotionValue highlightPosition = new DesignerMotionValue();
	private long frameAt;
	private final boolean upward;

	DesignerDropdown(int x, int y, int width, String current, String[] options, Predicate<String> enabled,
			Consumer<String> selected, int screenWidth, int screenHeight) {
		values = List.of(options);
		this.enabled = enabled;
		this.selected = selected;
		visible = Math.max(1, Math.min(values.size(), Math.min(10, (screenHeight - 12) / 18)));
		int popupHeight = visible * 18 + 4;
		int popupWidth = Math.min(screenWidth - 12, Math.max(width, values.stream()
				.mapToInt(value -> Minecraft.getInstance().font.width(DesignerFormScreen.option(value)) + 28).max().orElse(width)));
		int top = y + 18 + popupHeight <= screenHeight - 4 ? y + 18 : Math.max(4, y - popupHeight - 2);
		bounds = new Rect2i(Math.max(4, Math.min(x, screenWidth - popupWidth - 4)), top, popupWidth, popupHeight);
		upward = top < y;
		highlighted = Math.max(0, values.indexOf(current));
		first = Math.max(0, Math.min(highlighted - visible / 2, values.size() - visible));
		highlightPosition.snap(highlighted - first);
	}

	void render(GuiGraphics graphics, int mouseX, int mouseY) {
		var font = Minecraft.getInstance().font;
		long now = net.minecraft.Util.getMillis();
		double progress = DesignerPreferences.animate() && DesignerPreferences.enabled("motion_menus")
				? DesignerMotionValue.easeOut((now - openedAt) / 130.0) : 1;
		int visibleHeight = Math.max(4, bounds.getHeight() - (int) Math.round(8 * (1 - progress)));
		int visibleTop = upward ? bounds.getY() + bounds.getHeight() - visibleHeight : bounds.getY();
		if ((mouseX != lastMouseX || mouseY != lastMouseY) && bounds.contains(mouseX, mouseY)) {
			highlighted = Math.max(first, Math.min(first + visible - 1, first + (mouseY - bounds.getY() - 2) / 18));
		}
		highlightPosition.advance(highlighted - first, frameAt == 0 ? 0 : (now - frameAt) / 1000.0, 0.14, DesignerPreferences.animate());
		frameAt = now;
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, 700);
		DesignerTheme.scissor(graphics, bounds.getX(), visibleTop, bounds.getX() + bounds.getWidth(), visibleTop + visibleHeight);
		DesignerTheme.panel(graphics, "panel", bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
		int selectedY = bounds.getY() + 2 + (int) Math.round(highlightPosition.position() * 18);
		DesignerGlyphs.rounded(graphics, bounds.getX() + 2, selectedY, bounds.getWidth() - 4, 18, 4, DesignerTheme.colors().selected());
		for (int row = 0; row < visible && first + row < values.size(); row++) {
			int index = first + row;
			int y = bounds.getY() + 2 + row * 18;
			if ((mouseX != lastMouseX || mouseY != lastMouseY) && bounds.contains(mouseX, mouseY) && mouseY >= y && mouseY < y + 18) {
				highlighted = index;
			}
			String value = values.get(index);
			graphics.drawString(font, font.plainSubstrByWidth(DesignerFormScreen.option(value).getString(), bounds.getWidth() - 24),
					bounds.getX() + 8, y + 7, enabled.test(value) ? DesignerFormScreen.FOREGROUND : DesignerFormScreen.MUTED, false);
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		if (values.size() > visible) {
			int track = bounds.getHeight() - 8;
			int thumb = Math.max(10, track * visible / values.size());
			int y = bounds.getY() + 4 + (track - thumb) * first / (values.size() - visible);
			graphics.fill(bounds.getX() + bounds.getWidth() - 5, y, bounds.getX() + bounds.getWidth() - 3, y + thumb, DesignerFormScreen.ACCENT);
		}
		graphics.pose().popPose();
		graphics.disableScissor();
	}

	void click(double x, double y, int button) {
		if (button == 0 && bounds.contains((int) x, (int) y)) {
			int index = first + ((int) y - bounds.getY() - 2) / 18;
			choose(index);
		} else {
			closed = true;
		}
	}

	void scroll(double direction) {
		first = Math.max(0, Math.min(values.size() - visible, first + (direction < 0 ? 1 : -1)));
		highlighted = Math.max(first, Math.min(first + visible - 1, highlighted));
	}

	boolean key(int key) {
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB) {
			closed = true;
			return key != GLFW.GLFW_KEY_TAB;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
			choose(highlighted);
		} else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) {
			if (key == GLFW.GLFW_KEY_HOME) {
				highlighted = 0;
			} else if (key == GLFW.GLFW_KEY_END) {
				highlighted = values.size() - 1;
			} else {
				highlighted = Math.max(0, Math.min(values.size() - 1, highlighted + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
			}
			first = Math.max(0, Math.min(Math.max(first, highlighted - visible + 1), highlighted));
		}
		return true;
	}

	void type(char character) {
		long now = System.currentTimeMillis();
		prefix = (now - typedAt > 800 ? "" : prefix) + Character.toLowerCase(character);
		typedAt = now;
		for (int index = 0; index < values.size(); index++) {
			if (DesignerFormScreen.option(values.get(index)).getString().toLowerCase(Locale.ROOT).startsWith(prefix)) {
				highlighted = index;
				first = Math.max(0, Math.min(index, values.size() - visible));
				break;
			}
		}
	}

	private void choose(int index) {
		if (index >= 0 && index < values.size() && enabled.test(values.get(index))) {
			closed = true;
			selected.accept(values.get(index));
		}
	}

	boolean closed() {
		return closed;
	}

	static final class Control extends DesignerTheme.Action {
		Control(int x, int y, int width, Component message, net.minecraft.client.gui.components.Button.OnPress action) {
			super(x, y, width, message, action);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			renderSurface(graphics);
			var font = Minecraft.getInstance().font;
			graphics.drawString(font, font.plainSubstrByWidth(getMessage().getString(), width - 23), getX() + 6, getY() + 4,
					active ? DesignerFormScreen.FOREGROUND : DesignerFormScreen.MUTED, false);
			graphics.drawString(font, "▾", getX() + width - 14, getY() + 4, DesignerFormScreen.ACCENT, false);
		}
	}
}
