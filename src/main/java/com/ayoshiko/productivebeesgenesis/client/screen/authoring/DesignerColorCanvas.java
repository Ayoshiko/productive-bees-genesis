package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** 饱和度／明度平面、色相条和原色对照共用同一 HSV 状态。 */
final class DesignerColorCanvas extends AbstractWidget {
	private final IntSupplier color;
	private final IntConsumer selected;
	private final int original;
	private float hue;
	private float saturation;
	private float value;
	private int lastColor = -1;
	private boolean hueDragging;

	DesignerColorCanvas(int x, int y, int width, int height, int original, IntSupplier color, IntConsumer selected) {
		super(x, y, width, height, DesignerFormScreen.tr("color_canvas"));
		this.color = color;
		this.selected = selected;
		this.original = original;
		sync();
	}

	private void sync() {
		if (lastColor != color.getAsInt()) {
			lastColor = color.getAsInt();
			float[] hsv = DesignerColorValue.hsv(lastColor);
			if (hsv[1] > 0 && hsv[2] > 0) {
				hue = hsv[0];
			}
			saturation = hsv[1];
			value = hsv[2];
		}
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		sync();
		int planeWidth = width - 74;
		int planeHeight = height - 2;
		for (int column = 0; column < planeWidth; column += 2) {
			int top = 0xFF000000 | DesignerColorValue.rgb(hue, column / (float) (planeWidth - 1), 1);
			graphics.fillGradient(getX() + column, getY(), getX() + Math.min(planeWidth, column + 2), getY() + planeHeight, top, 0xFF000000);
		}
		for (int row = 0; row < planeHeight; row++) {
			graphics.fill(getX() + planeWidth + 6, getY() + row, getX() + planeWidth + 18, getY() + row + 1,
					0xFF000000 | DesignerColorValue.rgb(row / (float) planeHeight, 1, 1));
		}
		int markerX = getX() + Math.round(saturation * (planeWidth - 1));
		int markerY = getY() + Math.round((1 - value) * (planeHeight - 1));
		graphics.renderOutline(markerX - 2, markerY - 2, 5, 5, 0xFF000000);
		graphics.renderOutline(markerX - 1, markerY - 1, 3, 3, 0xFFFFFFFF);
		int hueY = getY() + Math.round(hue * (planeHeight - 1));
		graphics.renderOutline(getX() + planeWidth + 4, hueY - 2, 16, 5, 0xFF1C2730);
		graphics.renderOutline(getX() + planeWidth + 5, hueY - 1, 14, 3, 0xFFFFFFFF);
		int previewX = getX() + planeWidth + 26;
		graphics.fill(previewX, getY(), getX() + width, getY() + planeHeight / 2, 0xFF000000 | original);
		graphics.fill(previewX, getY() + planeHeight / 2, getX() + width, getY() + planeHeight, 0xFF000000 | color.getAsInt());
		var font = Minecraft.getInstance().font;
		graphics.drawString(font, DesignerFormScreen.tr("color_original"), previewX + 3, getY() + 4, contrast(original), false);
		graphics.drawString(font, DesignerFormScreen.tr("color_current"), previewX + 3, getY() + planeHeight / 2 + 4, contrast(color.getAsInt()), false);
	}

	private int contrast(int color) {
		int brightness = (color >> 16 & 255) * 299 + (color >> 8 & 255) * 587 + (color & 255) * 114;
		return brightness > 150000 ? 0xFF101820 : 0xFFFFFFFF;
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (mouseX >= getX() + width - 48) {
			if (mouseY < getY() + height / 2) {
				selected.accept(original);
			}
			hueDragging = false;
			return;
		}
		hueDragging = mouseX >= getX() + width - 68;
		pick(mouseX, mouseY);
	}

	@Override
	protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
		if (mouseX < getX() + width - 48) {
			pick(mouseX, mouseY);
		}
	}

	private void pick(double x, double y) {
		float vertical = (float) Math.max(0, Math.min(1, (y - getY()) / (height - 3)));
		if (hueDragging) {
			hue = vertical;
		} else {
			saturation = (float) Math.max(0, Math.min(1, (x - getX()) / (width - 75)));
			value = 1 - vertical;
		}
		lastColor = DesignerColorValue.rgb(hue, saturation, value);
		selected.accept(lastColor);
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
			saturation = Math.max(0, Math.min(1, saturation + (key == GLFW.GLFW_KEY_RIGHT ? 0.01F : -0.01F)));
		} else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			value = Math.max(0, Math.min(1, value + (key == GLFW.GLFW_KEY_UP ? 0.01F : -0.01F)));
		} else {
			return super.keyPressed(key, scan, modifiers);
		}
		lastColor = DesignerColorValue.rgb(hue, saturation, value);
		selected.accept(lastColor);
		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
