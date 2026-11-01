package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

/** 只保存界面几何偏好；可用区域始终排除独立背包和 JEI。 */
final class DesignerWindowLayout {
	static final int WEST = 1;
	static final int EAST = 2;
	static final int NORTH = 4;
	static final int SOUTH = 8;
	static final int MOVE = 16;
	private int availableWidth;
	private int availableHeight;
	private int minimumX;
	private int preferredWidth = -1;
	private int preferredHeight = -1;
	private int requestedX;
	private int requestedY;
	private boolean positioned;
	private boolean pinned;
	private Bounds bounds;
	private Bounds pressed;
	private int gesture;
	private double pressX;
	private double pressY;

	Bounds fit(int screenWidth, int screenHeight, int ingredientSpace) {
		minimumX = 8;
		availableWidth = Math.max(1, screenWidth - 16);
		availableHeight = Math.max(1, screenHeight - 108);
		int width = clamp(preferredWidth < 0 ? Math.min(820, Math.max(280, screenWidth - ingredientSpace * 2 - 16)) : preferredWidth, Math.min(280, availableWidth), availableWidth);
		int height = clamp(preferredHeight < 0 ? 540 : preferredHeight, Math.min(132, availableHeight), availableHeight);
		int x = positioned ? requestedX : minimumX + (availableWidth - width) / 2;
		int y = positioned ? requestedY : 4 + (availableHeight - height) / 2;
		bounds = new Bounds(clamp(x, minimumX, minimumX + availableWidth - width), clamp(y, 4, 4 + availableHeight - height), width, height);
		return bounds;
	}

	int edgeAt(double x, double y) {
		if (pinned || bounds == null || x < bounds.left() - 4 || x >= bounds.right() + 4 || y < bounds.top() - 4 || y >= bounds.bottom() + 4) {
			return 0;
		}
		int edge = 0;
		if (x < bounds.left() + 5) {
			edge |= WEST;
		} else if (x >= bounds.right() - 5) {
			edge |= EAST;
		}
		if (y < bounds.top() + 5) {
			edge |= NORTH;
		} else if (y >= bounds.bottom() - 5) {
			edge |= SOUTH;
		}
		return edge;
	}

	boolean press(int kind, double x, double y) {
		if (pinned || kind == 0) {
			return false;
		}
		pressed = bounds;
		pressX = x;
		pressY = y;
		gesture = kind;
		return true;
	}

	boolean drag(double x, double y) {
		if (gesture == 0) {
			return false;
		}
		int dx = (int) Math.round(x - pressX);
		int dy = (int) Math.round(y - pressY);
		int left = pressed.left();
		int top = pressed.top();
		int right = pressed.right();
		int bottom = pressed.bottom();
		int minimumWidth = Math.min(280, availableWidth);
		int minimumHeight = Math.min(132, availableHeight);
		if (gesture == MOVE) {
			left = clamp(left + dx, minimumX, minimumX + availableWidth - pressed.width());
			top = clamp(top + dy, 4, 4 + availableHeight - pressed.height());
			right = left + pressed.width();
			bottom = top + pressed.height();
		} else {
			if ((gesture & WEST) != 0) {
				left = clamp(left + dx, minimumX, right - minimumWidth);
			}
			if ((gesture & EAST) != 0) {
				right = clamp(right + dx, left + minimumWidth, minimumX + availableWidth);
			}
			if ((gesture & NORTH) != 0) {
				top = clamp(top + dy, 4, bottom - minimumHeight);
			}
			if ((gesture & SOUTH) != 0) {
				bottom = clamp(bottom + dy, top + minimumHeight, 4 + availableHeight);
			}
			preferredWidth = right - left;
			preferredHeight = bottom - top;
		}
		positioned = true;
		requestedX = left;
		requestedY = top;
		Bounds next = new Bounds(left, top, right - left, bottom - top);
		boolean changed = !next.equals(bounds);
		bounds = next;
		return changed;
	}

	void release() {
		gesture = 0;
		pressed = null;
	}

	boolean dragging() {
		return gesture != 0;
	}

	boolean pinned() {
		return pinned;
	}

	void togglePin() {
		pinned = !pinned;
		release();
	}

	void reset() {
		preferredWidth = -1;
		preferredHeight = -1;
		positioned = false;
		pinned = false;
		release();
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	record Bounds(int left, int top, int width, int height) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}
	}
}
