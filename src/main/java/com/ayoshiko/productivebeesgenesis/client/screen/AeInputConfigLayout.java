package com.ayoshiko.productivebeesgenesis.client.screen;

/**
	 * AE2 输入配置窗口布局常量与纯计算辅助 — 从 {@link GuiAeInputConfig} 拆分。
	 * <br/>
	 * 持有窗口布局常量与页码计算方法，供 {@link GuiAeInputConfig}及同包按钮类共享。
	 */
final class AeInputConfigLayout {

	private AeInputConfigLayout() {
	}

	static final int WINDOW_WIDTH = 260;
	static final int WINDOW_HEIGHT = 200;
	static final int GRID_COLS = 9;
	static final int GRID_ROWS = 2;
	static final int SLOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
	static final int CELL_PITCH_X = 18;
	static final int CELL_PITCH_Y = 60;
	static final int GEAR_SIZE = 16;
	static final int GRID_WIDTH = GRID_COLS * CELL_PITCH_X;
	static final int GRID_HEIGHT = GRID_ROWS * CELL_PITCH_Y;
	static final int GRID_X = 8;
	static final int GRID_Y = 44;
	static final int INFO_X = GRID_X + GRID_WIDTH + 4;
	static final int INFO_WIDTH = WINDOW_WIDTH - INFO_X - 8;
	static final int INFO_HEIGHT = GRID_HEIGHT;
	static final int CTRL_Y = 22;
	static final int CTRL_BTN_HEIGHT = 14;
	static final int TOGGLE_BTN_WIDTH = 26;
	static final int RESERVE_BTN_WIDTH = 42;
	static final int PAGE_BTN_WIDTH = 18;
	static final int PIN_X_OFFSET = 16;
	static final int PIN_Y_OFFSET = 6;

	/**
	 * Total pages = max(config minimum pages, capacity pages).
	 * Fixed-position mode pages are based on the array capacity so entries can
	 * be placed in any slot; at least minPages pages are kept.
	 */
	static int computeTotalPages(int slotCount, int minPages) {
		int capPages = (int) Math.ceil((double) slotCount / SLOTS_PER_PAGE);
		return Math.max(1, Math.max(minPages, capPages));
	}

	static int controlY(int relativeY) {
		return relativeY + CTRL_Y;
	}
}
