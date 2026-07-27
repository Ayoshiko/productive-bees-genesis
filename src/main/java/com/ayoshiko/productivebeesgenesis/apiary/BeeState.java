package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * 蜜蜂在机械蜂箱内的状态枚举
 * <br/>
 * 用于 BeeSlot.state 字段，驱动 tick 处理流程并供 GUI 状态灯渲染。
 * <p>
 * 状态语义：
 * <ul>
 *   <li>{@link #IDLE} — 空槽或刚装入未启动</li>
 *   <li>{@link #WORKING} — 正常生产中，能量充足、花朵有效、输出有空间</li>
 *   <li>{@link #WAITING_FLOWER} — 喂食器无有效花朵，等待玩家补充</li>
 *   <li>{@link #WAITING_ENERGY} — 能量不足，等待能量恢复</li>
 *   <li>{@link #WAITING_OUTPUT} — 输出槽已满，等待产物被取走</li>
 * </ul>
 * <p>
 * 设计原则：单一职责，仅描述状态与对应的 GUI 渲染颜色。
 */
public enum BeeState {

	/** 空槽或刚装入未启动 — 灰色 */
	IDLE(0x555555),

	/** 正常生产中 — 绿色 */
	WORKING(0x4CAF50),

	/** 等待花朵 — 橙色 */
	WAITING_FLOWER(0xFF9800),

	/** 等待能量 — 红色 */
	WAITING_ENERGY(0xF44336),

	/** 等待输出空间 — 黄色 */
	WAITING_OUTPUT(0xFFC107);

	/** 状态颜色 RGB int 值（供 GUI 状态灯渲染） */
	private final int color;

	BeeState(int color) {
		this.color = color;
	}

	/**
	 * 获取状态对应的 RGB 颜色值
	 *
	 * @return RGB int（0xRRGGBB 格式）
	 */
	public int getColor() {
		return color;
	}
}
