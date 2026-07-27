package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * MEK Apiary Tab 位置动态计算辅助类
 * <br/>
 * 从 {@link ApiaryGuiLayoutHelper} 抽取，专职负责 MEK 标准 Tab（警告/能量/红石）的 Y 坐标动态计算。
 * <p>
 * 设计原理：
 * <ul>
 *   <li>MEK 原版 Tab 的 Y 坐标在 GuiXxxTab 构造函数中硬编码（警告=109、能量=137、红石=137），
 *       仅适配标准 176×166 GUI</li>
 *   <li>本模组蜂箱 GUI 高度随蜜蜂行数动态变化，需根据玩家物品栏 Y 坐标动态调整 Tab 位置</li>
 *   <li>计算规则：警告 Tab Y = max(物品栏Y, 喂食Tab底部+间距)，能量/红石 Tab 依次递增</li>
 * </ul>
 * <p>
 * 调用方需先通过 {@link ApiaryGuiLayoutHelper#getInventoryYForBeeRows(int)} 计算物品栏 Y，
 * 再传入本类方法获取 Tab 坐标，实现职责分离（蜜蜂布局 ⟂ Tab 布局）。
 *
 * @see ApiaryGuiLayoutHelper
 */
public final class ApiaryTabLayoutHelper {

	private ApiaryTabLayoutHelper() {}

	// ===== Tab 常量 =====

	/** 自定义 Tab（喂食/PB升级）Y 坐标 */
	public static final int CUSTOM_TAB_Y = 98;

	/** 自定义 Tab 高度 */
	public static final int CUSTOM_TAB_H = 18;

	/** Tab 垂直间距（6px 确保警告 Tab 不与喂食器 Tab 视觉重叠） */
	public static final int TAB_V_GAP = 6;

	/** MEK 标准 Tab 尺寸（警告/能量 Tab：26x26） */
	public static final int MEK_TAB_SIZE = 26;

	/** MEK 警告 Tab 默认 Y（GuiWarningTab 构造函数硬编码） */
	private static final int DEFAULT_WARNING_TAB_Y = 109;

	/** MEK 能量 Tab 默认 Y（GuiEnergyTab 构造函数硬编码） */
	private static final int DEFAULT_ENERGY_TAB_Y = 137;

	/** MEK 红石 Tab 默认 Y（GuiRedstoneControlTab 构造函数硬编码） */
	private static final int DEFAULT_REDSTONE_TAB_Y = 137;

	/**
	 * 获取 MEK 警告 Tab 的 Y 坐标（左侧）
	 * <br/>
	 * 动态计算规则：max(物品栏Y, 喂食Tab底部+间距)。
	 * <ul>
	 *   <li>小 GUI（1行蜜蜂）：物品栏Y=113 &lt; 喂食Tab底部+间距=118，取 118（紧贴喂食Tab下方）</li>
	 *   <li>大 GUI（4行蜜蜂）：物品栏Y=200 &gt; 118，取 200（与物品栏对齐）</li>
	 * </ul>
	 * 这样既避免与喂食 Tab 重叠，又参考了物品栏位置进行动态布局。
	 *
	 * @param inventoryY 玩家物品栏 Y 坐标（由 {@link ApiaryGuiLayoutHelper#getInventoryYForBeeRows(int)} 计算）
	 * @return 警告 Tab Y 坐标
	 */
	public static int getWarningTabY(int inventoryY) {
		int feederBottom = CUSTOM_TAB_Y + CUSTOM_TAB_H;
		return Math.max(inventoryY, feederBottom + TAB_V_GAP);
	}

	/**
	 * 获取 MEK 能量 Tab 的 Y 坐标（左侧）
	 * <br/>
	 * 位于警告 Tab 下方，保持 28px 间距（26px高度+2px间隔）。
	 *
	 * @param inventoryY 玩家物品栏 Y 坐标
	 * @return 能量 Tab Y 坐标
	 */
	public static int getEnergyTabY(int inventoryY) {
		return getWarningTabY(inventoryY) + MEK_TAB_SIZE + TAB_V_GAP;
	}

	/**
	 * 获取 MEK 红石 Tab 的 Y 坐标（右侧）
	 * <br/>
	 * 与能量 Tab 保持相同 Y（均在警告 Tab 下方），位于 PB 升级 Tab 下方。
	 *
	 * @param inventoryY 玩家物品栏 Y 坐标
	 * @return 红石 Tab Y 坐标
	 */
	public static int getRedstoneTabY(int inventoryY) {
		return getEnergyTabY(inventoryY);
	}

	/**
	 * 计算警告 Tab 相对默认位置的 Y 偏移量
	 * <br/>
	 * 用于在渲染时平移 MEK 原版 GuiWarningTab，使其从默认 Y=109 移动到动态计算位置。
	 *
	 * @param inventoryY 玩家物品栏 Y 坐标
	 * @return Y 偏移量（正值表示下移）
	 */
	public static int getWarningTabDeltaY(int inventoryY) {
		return getWarningTabY(inventoryY) - DEFAULT_WARNING_TAB_Y;
	}

	/**
	 * 计算能量 Tab 相对默认位置的 Y 偏移量
	 * <br/>
	 * 用于在渲染时平移 MEK 原版 GuiEnergyTab，使其从默认 Y=137 移动到动态计算位置。
	 *
	 * @param inventoryY 玩家物品栏 Y 坐标
	 * @return Y 偏移量（正值表示下移）
	 */
	public static int getEnergyTabDeltaY(int inventoryY) {
		return getEnergyTabY(inventoryY) - DEFAULT_ENERGY_TAB_Y;
	}

	/**
	 * 计算红石 Tab 相对默认位置的 Y 偏移量
	 * <br/>
	 * 用于在渲染时平移 MEK 原版 GuiRedstoneControlTab，使其从默认 Y=137 移动到动态计算位置。
	 *
	 * @param inventoryY 玩家物品栏 Y 坐标
	 * @return Y 偏移量（正值表示下移）
	 */
	public static int getRedstoneTabDeltaY(int inventoryY) {
		return getRedstoneTabY(inventoryY) - DEFAULT_REDSTONE_TAB_Y;
	}
}
