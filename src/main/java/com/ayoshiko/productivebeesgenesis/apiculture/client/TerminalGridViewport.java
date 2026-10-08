package com.ayoshiko.productivebeesgenesis.apiculture.client;

import net.minecraft.client.gui.GuiGraphics;

/** 只滚动当前有界页面；到页边缘再请求下一页，不保留全网客户端目录。 */
final class TerminalGridViewport {
	int x, y, columns, rows, offset, count;
	private boolean dragging;
	void layout(int x, int y, int width, int rows, int count) {
		this.x = x; this.y = y; this.columns = Math.max(1, (width - 12) / 18);
		this.rows = Math.max(1, rows); this.count = count; offset = Math.clamp(offset, 0, max());
	}
	int max() { return Math.max(0, (count + columns - 1) / columns - rows); }
	int first() { return offset * columns; }
	int end() { return Math.min(count, first() + columns * rows); }
	int cellX(int index) { return x + index % columns * 18; }
	int cellY(int index) { return y + (index / columns - offset) * 18; }
	int rowAt(double mouseX, double mouseY) {
		if (mouseX < x || mouseX >= x + columns * 18 || mouseY < y || mouseY >= y + rows * 18) return -1;
		return first() + (int) (mouseY - y) / 18 * columns + (int) (mouseX - x) / 18;
	}
	boolean contains(double mouseX, double mouseY) { return mouseX >= x && mouseX < x + columns * 18 + 12 && mouseY >= y && mouseY < y + rows * 18; }
	void render(GuiGraphics g) {
		TerminalSlotGrid.grid(g, x, y, columns, rows);
		int sx = x + columns * 18 + 3, h = rows * 18;
		g.fill(sx, y - 1, sx + 8, y + h - 1, 0xfff1f1f5);
		g.fill(sx + 1, y, sx + 7, y + h - 2, 0xff999db5);
		int thumb = max() == 0 ? y : y + offset * Math.max(0, h - 15) / max();
		TerminalSkin.panel(g, sx, thumb, 8, 14);
	}
	boolean press(double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY) || mouseX < x + columns * 18) return false;
		dragging = true; move(mouseY); return true;
	}
	boolean drag(double mouseY, int button) { if (!dragging || button != 0) return false; move(mouseY); return true; }
	boolean release(int button) { if (!dragging || button != 0) return false; dragging = false; return true; }
	private void move(double mouseY) { offset = Math.clamp((int) Math.round((mouseY - y - 7) * max() / Math.max(1, rows * 18 - 15)), 0, max()); }
}
