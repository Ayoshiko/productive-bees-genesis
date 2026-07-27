package com.ayoshiko.productivebeesgenesis.client.screen;

/**
 * 多流体槽窗口布局工具类(SRP:仅负责动态布局计算,不涉及渲染)
 * <br/>
 * 所有公式均为纯函数,基于 tankCount 动态计算窗口尺寸和槽位坐标。
 * <p>
 * <b>布局原理(单行横排):</b>
 * <ul>
 *   <li>GaugeType.SMALL 实际像素:18×30,槽位步进 20px(18+2 gap)</li>
 *   <li>所有槽位在同一行横排显示(1 行 N 列),无翻页</li>
 *   <li>窗口宽度随 tankCount 线性增长,窗口高度固定</li>
 * </ul>
 *
 * @since Task 8
 */
public final class GuiMultiFluidTanksLayoutHelper {

	/** Gauge 像素宽(GaugeType.SMALL) */
	public static final int GAUGE_W = 18;
	/** Gauge 像素高(GaugeType.SMALL) */
	public static final int GAUGE_H = 30;
	/** Gauge 间距 */
	public static final int GAUGE_GAP = 2;
	/** 槽位步进 = GAUGE_W + GAUGE_GAP = 20px */
	public static final int SLOT_PITCH = 20;
	/** 左内边距 */
	public static final int LEFT_PADDING = 8;
	/** 右内边距 */
	public static final int RIGHT_PADDING = 8;
	/** 标题栏高度(含 PinButton + 关闭按钮区域) */
	public static final int TITLE_HEIGHT = 18;
	/** 网格起始 Y 坐标(标题栏下方) */
	public static final int GRID_Y = 22;
	/** 底部提示区域高度 */
	public static final int HINT_HEIGHT = 14;

	private GuiMultiFluidTanksLayoutHelper() {
	}

	/**
	 * 计算列数(单行横排)
	 * <br/>
	 * 原理:单行布局,列数 = tankCount,所有槽位排成一行。
	 *
	 * @param tankCount 流体槽数量
	 * @return 列数(等于 tankCount)
	 */
	public static int calculateCols(int tankCount) {
		return tankCount;
	}

	/**
	 * 计算可见行数(单行横排固定为 1)
	 *
	 * @param tankCount 流体槽数量(未使用,保留参数兼容)
	 * @return 固定返回 1
	 */
	public static int calculateVisibleRows(int tankCount) {
		return 1;
	}

	/**
	 * 计算窗口宽度
	 * <br/>
	 * 原理:LEFT_PADDING + tankCount × SLOT_PITCH - GAUGE_GAP + RIGHT_PADDING。
	 *
	 * @param tankCount 流体槽数量
	 * @return 窗口宽度(像素)
	 */
	public static int calculateWindowWidth(int tankCount) {
		return LEFT_PADDING + tankCount * SLOT_PITCH - GAUGE_GAP + RIGHT_PADDING;
	}

	/**
	 * 计算窗口高度(单行固定高度,不依赖 tankCount)
	 * <br/>
	 * 原理:TITLE_HEIGHT + GAUGE_H + HINT_HEIGHT + 4。
	 *
	 * @param tankCount 流体槽数量(未使用,保留参数兼容)
	 * @return 窗口高度(像素)
	 */
	public static int calculateWindowHeight(int tankCount) {
		return TITLE_HEIGHT + GAUGE_H + HINT_HEIGHT + 4;
	}

	/**
	 * 计算指定槽位的 X 坐标(单行横排)
	 * <br/>
	 * 原理:LEFT_PADDING + i × SLOT_PITCH,所有槽位在同一行从左到右排列。
	 *
	 * @param i         槽位索引(0-based)
	 * @param cols      列数(未使用,保留参数兼容)
	 * @param tankCount 流体槽总数(未使用,保留参数兼容)
	 * @return X 坐标(相对于窗口左上角)
	 */
	public static int calculateGaugeX(int i, int cols, int tankCount) {
		return LEFT_PADDING + i * SLOT_PITCH;
	}

	/**
	 * 计算指定槽位的 Y 坐标(单行横排固定为 GRID_Y)
	 *
	 * @param i    槽位索引(0-based,未使用,保留参数兼容)
	 * @param cols 列数(未使用,保留参数兼容)
	 * @return Y 坐标(相对于窗口左上角)
	 */
	public static int calculateGaugeY(int i, int cols) {
		return GRID_Y;
	}
}
