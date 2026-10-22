package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.lwjgl.glfw.GLFW;

/** 光标只属于当前设计器，关闭时释放原生句柄。 */
final class DesignerCursor {
	private static long cursor;
	private static int shape;

	private DesignerCursor() {
	}

	static void update(long window, int edge) {
		int next = switch (edge) {
			case DesignerWindowLayout.WEST, DesignerWindowLayout.EAST -> GLFW.GLFW_RESIZE_EW_CURSOR;
			case DesignerWindowLayout.NORTH, DesignerWindowLayout.SOUTH -> GLFW.GLFW_RESIZE_NS_CURSOR;
			case DesignerWindowLayout.WEST | DesignerWindowLayout.NORTH, DesignerWindowLayout.EAST | DesignerWindowLayout.SOUTH -> GLFW.GLFW_RESIZE_NWSE_CURSOR;
			case DesignerWindowLayout.EAST | DesignerWindowLayout.NORTH, DesignerWindowLayout.WEST | DesignerWindowLayout.SOUTH -> GLFW.GLFW_RESIZE_NESW_CURSOR;
			default -> 0;
		};
		if (shape == next) {
			return;
		}
		clear(window);
		shape = next;
		if (next != 0) {
			cursor = GLFW.glfwCreateStandardCursor(next);
			GLFW.glfwSetCursor(window, cursor);
		}
	}

	static void clear(long window) {
		GLFW.glfwSetCursor(window, 0);
		if (cursor != 0) {
			GLFW.glfwDestroyCursor(cursor);
		}
		cursor = 0;
		shape = 0;
	}
}
