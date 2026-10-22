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
	 *   <li>{@link #WAITING_DAY_CYCLE} — 行为基因与当前昼夜不符</li>
	 *   <li>{@link #WAITING_RAIN} — 天气耐受基因无法适应雨天</li>
	 *   <li>{@link #WAITING_THUNDER} — 天气耐受基因无法适应雷暴</li>
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
	WAITING_OUTPUT(0xFFC107),

	/** 行为基因不适应当前昼夜 — 紫色 */
	WAITING_DAY_CYCLE(0x9C6ADE),

	/** 天气耐受基因不适应雨天 — 蓝色 */
	WAITING_RAIN(0x42A5F5),

	/** 天气耐受基因不适应雷暴 — 深蓝色 */
	WAITING_THUNDER(0x5C6BC0),

	/** 已付蜂蜜仍有后缀未进入储罐或网络。 */
	WAITING_FLUID(0xE6A23C),

	/** 已生成批次仍保有未交出的物品或流体。 */
	WAITING_FROZEN_OUTPUT(0xE6A23C),

	/** 原蜜蜂的已付周期尚未结清，不能混入改变基因后的新周期。 */
	WAITING_PREVIOUS_BEE(0xC792EA),

	/** 仅命中扩展资源配方的蜂种等待其账户、容量或配方恢复。 */
	WAITING_RESOURCE(0xD49A45),

	/** 已付生产结果未知或保存数据无效，必须保留并核验。 */
	PRODUCTION_SUSPENDED(0xEF5350),

	/** 外部接收结果未知，保管请求等待核验。 */
	OUTPUT_SUSPENDED(0xEF5350),

	/** 输出缓冲原文尚未成功解码。 */
	OUTPUT_DATA_INVALID(0xEF5350),

	/** 有界物品缓冲已达到组数上限。 */
	WAITING_OUTPUT_BUFFER(0xE6A23C),

	/** 蜂种已停用；实体、物品和已付周期保留以便恢复。 */
	RETIRED_BEE(0x90A4AE);

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

	public String translationKey() {
		return "gui.productivebeesgenesis.bee_state." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
