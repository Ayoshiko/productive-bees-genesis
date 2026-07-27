package com.ayoshiko.productivebeesgenesis.client.screen;

/**
 * GUI 渲染颜色常量集中管理
 * <p>
 * 所有 GUI 屏幕与渲染器应引用这些常量，避免硬编码颜色值。
 * 本类仅存放 ARGB 颜色常量，不包含任何逻辑（SRP）。
 * <br/>
 * 注意：部分常量值相同但语义不同（如 {@link #STATUS_INVALID} 与 {@link #STATUS_ERROR_HINT}），
 * 拆分为独立常量以便后续按语义单独调整。
 */
public final class GuiColors {

	private GuiColors() {}

	// ========== 背景色 ==========

	/** 全屏不透明深色背景 */
	public static final int BG_SCREEN_DARK = 0xFF101010;

	/** 列表区域深灰背景 */
	public static final int BG_LIST_PANEL = 0xFF1A1A1A;

	// ========== 边框色 ==========

	/** 列表区域灰色边框（上下左右） */
	public static final int BORDER_GRAY = 0xFF707070;

	/** 条目分隔线深灰色 */
	public static final int BORDER_DARK = 0xFF404040;

	/** 分组标题底部分隔线 */
	public static final int BORDER_GROUP_HEADER = 0xFF606080;

	// ========== 文本色 ==========

	/** 标题文本色（白色不透明，alpha=255） */
	public static final int TEXT_TITLE = 0xFFFFFFFF;

	/** 白色文本（复选框、表头、分组标题、模式高亮边框等） */
	public static final int TEXT_WHITE = 0xFFFFFFFF;

	/** 类型 ID 浅灰文本 */
	public static final int TEXT_LIGHT_GRAY = 0xFFE0E0E0;

	/** 产物信息灰色文本 */
	public static final int TEXT_PRODUCT_GRAY = 0xFFC0C0C0;

	/** 次要表头/空结果提示暗灰文本 */
	public static final int TEXT_DIM_GRAY = 0xFFB0B0B0;

	/** 序号中灰文本 */
	public static final int TEXT_INDEX_GRAY = 0xFF909090;

	/** 空列表提示/拖拽手柄深灰文本 */
	public static final int TEXT_DARK_GRAY = 0xFF808080;

	/** 蜜蜂选择屏幕产物信息浅灰文本 */
	public static final int TEXT_PRODUCT_LIGHT = 0xFFD0D0D0;

	/** 显示名称黄色文本 */
	public static final int TEXT_NAME_YELLOW = 0xFFFFFF80;

	/** 已添加蜜蜂名称绿色文本 */
	public static final int TEXT_NAME_ADDED_GREEN = 0xFF80FF80;

	/** 已添加标记亮绿色文本 */
	public static final int TEXT_ADDED_MARK = 0xFF00FF00;

	// ========== 状态色（导入/验证提示） ==========

	/** 导入成功绿色提示 */
	public static final int STATUS_SUCCESS = 0xFF60FF60;

	/** 导入重复项橙黄色提示 */
	public static final int STATUS_DUPLICATE = 0xFFFFC040;

	/** 导入无效项红色提示 */
	public static final int STATUS_INVALID = 0xFFFF6060;

	/** 导入混合结果（既有重复又有无效）橙色提示 */
	public static final int STATUS_MIXED = 0xFFFFA500;

	/** 输入验证错误红色提示（值同 STATUS_INVALID，语义独立以便单独调整） */
	public static final int STATUS_ERROR_HINT = 0xFFFF6060;

	// ========== 半透明叠加色 ==========

	/** 行悬停高亮（半透明白） */
	public static final int OVERLAY_HOVER_ROW = 0x30FFFFFF;

	/** 拖拽插入线光晕（半透明白） */
	public static final int OVERLAY_DRAG_LINE_GLOW = 0x80FFFFFF;

	/** 拖拽幽灵背景（半透明黑） */
	public static final int OVERLAY_DRAG_GHOST_BG = 0x40000000;

	/** 拖拽幽灵文本（半透明白） */
	public static final int OVERLAY_DRAG_GHOST_TEXT = 0x80FFFFFF;

	/** 普通条目背景（半透明白） */
	public static final int OVERLAY_ENTRY_BG = 0x20FFFFFF;

	/** 悬停条目背景（半透明白） */
	public static final int OVERLAY_ENTRY_HOVER_BG = 0x60FFFFFF;

	/** 已添加条目背景（半透明绿） */
	public static final int OVERLAY_ENTRY_ADDED_BG = 0x2080FF80;

	/** 悬停已添加条目背景（半透明绿） */
	public static final int OVERLAY_ENTRY_ADDED_HOVER_BG = 0x4080FF80;

	// ========== 分组标题背景色 ==========

	/** 分组标题背景 */
	public static final int GROUP_HEADER_BG = 0xFF404060;

	/** 悬停分组标题背景 */
	public static final int GROUP_HEADER_BG_HOVER = 0xFF505070;

	// ========== 滚动条色 ==========

	/** 滚动条轨道深灰 */
	public static final int SCROLLBAR_TRACK = 0xFF404040;

	/** 滚动条滑块灰色 */
	public static final int SCROLLBAR_THUMB = 0xFFA0A0A0;

	/** 滚动条边框暗灰（1:1复刻MEK原版scroll_list.png边框） */
	public static final int SCROLLBAR_BORDER = 0xFF202020;

	// ========== 指示色 ==========

	/** 已添加指示条绿色竖条 */
	public static final int ADDED_INDICATOR_BAR = 0xFF40C040;
}
