package com.ayoshiko.productivebeesgenesis.client.screen;

/** 多流体槽的纯布局计算；额外槽按原顺序换行，不改变宿主槽索引。 */
public final class GuiMultiFluidTanksLayoutHelper {

	public static final int GAUGE_W = 18;
	public static final int GAUGE_H = 30;
	public static final int GAUGE_GAP = 2;
	public static final int SLOT_PITCH = GAUGE_W + GAUGE_GAP;
	public static final int ROW_PITCH = GAUGE_H + GAUGE_GAP;
	public static final int LEFT_PADDING = 8;
	public static final int RIGHT_PADDING = 8;
	public static final int GRID_Y = 22;
	public static final int HINT_HEIGHT = 14;

	/** 即使暂时没有额外槽，也为关闭和固定按钮保留标题栏空间。 */
	private static final int MIN_WINDOW_WIDTH = 80;

	private GuiMultiFluidTanksLayoutHelper() {
	}

	public static int calculateAvailableWidth(int viewportWidth) {
		return Math.max(MIN_WINDOW_WIDTH, viewportWidth - LEFT_PADDING - RIGHT_PADDING);
	}

	public static int calculateCols(int tankCount, int availableWidth) {
		int fittingCols = Math.max(1, (availableWidth - LEFT_PADDING - RIGHT_PADDING + GAUGE_GAP) / SLOT_PITCH);
		return Math.min(Math.max(1, tankCount), fittingCols);
	}

	public static int calculateVisibleRows(int tankCount, int cols) {
		if (tankCount <= 0) {
			return 0;
		}
		return 1 + (tankCount - 1) / cols;
	}

	public static int calculateWindowWidth(int tankCount, int availableWidth) {
		int cols = calculateCols(tankCount, availableWidth);
		return Math.max(MIN_WINDOW_WIDTH, LEFT_PADDING + cols * SLOT_PITCH - GAUGE_GAP + RIGHT_PADDING);
	}

	public static int calculateWindowHeight(int tankCount, int cols) {
		int rows = calculateVisibleRows(tankCount, cols);
		int gridHeight = rows == 0 ? 0 : rows * ROW_PITCH - GAUGE_GAP;
		return GRID_Y + gridHeight + HINT_HEIGHT;
	}

	public static int calculateGaugeX(int index, int cols) {
		return LEFT_PADDING + (index % cols) * SLOT_PITCH;
	}

	public static int calculateGaugeY(int index, int cols) {
		return GRID_Y + (index / cols) * ROW_PITCH;
	}
}
