package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
	 * 喂食器窗口布局辅助 — 从 {@link GuiFeederWindow} 拆分的静态辅助类。
	 * <br/>
	 * 持有窗口布局常量与纯计算/分配辅助方法（宽高计算、花朵统计、信息面板高度、花朵名称截断）。
	 */
final class FeederWindowLayoutSupport {

	private FeederWindowLayoutSupport() {
	}

	/** 槽位步进（18+2=20px） */
	static final int SLOT_PITCH = 20;

	/** 左侧内边距 */
	static final int LEFT_PADDING = 6;

	/** 标题栏高度 */
	static final int TITLE_HEIGHT = 18;

	/** 底部提示区域高度 */
	static final int HINT_HEIGHT = 14;

	/** 网格与信息面板间距 */
	static final int PANEL_GAP = 4;

	/** 右侧信息面板固定宽度 */
	static final int INFO_PANEL_WIDTH = 59;

	/**
	 * 花朵名称最大渲染宽度（缩放前）
	 * <br/>
	 * 模块 5 修复（v2.4 最终版）：玩家实测 85px 在高级/精英等级蜂箱中仍超出边框。
	 * 虽然理论计算 55/0.55≈100px，但实际渲染因 startX 偏移、缩放像素对齐、字体度量差异
	 * 及不同等级蜂箱的窗口尺寸差异，需要更大的安全余量。改为 75px 确保所有等级
	 * （基础/高级/精英/终极）的 9 种不同花朵名称都不超出信息面板边框。
	 * <p>
	 * 理论计算：信息面板宽度 {@link #INFO_PANEL_WIDTH}=59px，扣除左右内边距各 2px 后实际可用 55px，
	 * 花朵名称使用 0.55F 缩放，等效渲染前最大宽度 = 55 / 0.55 ≈ 100px。
	 * 但 75px 渲染后 = 75 × 0.55 = 41.25px，远小于 55px，留出 13.75px 安全余量。
	 */
	static final int MAX_FLOWER_NAME_WIDTH = 75;

	/** 省略号字符串（与 {@code BeeNameRenderer} 保持一致） */
	static final String ELLIPSIS = "...";

	/** 固定按钮 X 偏移 */
	static final int PIN_X_OFFSET = 16;

	/** 固定按钮 Y 偏移 */
	static final int PIN_Y_OFFSET = 6;

	/** 单页最大行数（翻页时每页显示 6 行，5×6=30 槽） */
	static final int MAX_ROWS_PER_PAGE = 6;

	/** 翻页按钮宽度 */
	static final int PAGE_BTN_WIDTH = 18;

	/** 翻页按钮高度 */
	static final int PAGE_BTN_HEIGHT = 12;

	/** 翻页按钮 Y 偏移（位于标题栏内） */
	static final int PAGE_BTN_Y_OFFSET = 4;

	/** 翻页按钮间距 */
	static final int PAGE_BTN_GAP = 2;

	/** 翻页按钮右侧内边距 */
	static final int PAGE_BTN_RIGHT_MARGIN = 8;

	/**
	 * 标题栏右侧按钮位置计算（自右向左第 index 个按钮的 X 偏移，index 从 0 开始）
	 * <br/>
	 * 翻页按钮与转化开关共用同一排、同一尺寸与间距，统一由本方法排布，
	 * 避免各处重复硬编码偏移导致按钮重叠。
	 *
	 * @param windowWidth 窗口总宽度
	 * @param index       自右向左的序号（0 = 最右侧）
	 * @return 相对窗口左边的 X 偏移
	 */
	static int titleButtonX(int windowWidth, int index) {
		return windowWidth - (index + 1) * (PAGE_BTN_WIDTH + PAGE_BTN_GAP) - PAGE_BTN_RIGHT_MARGIN;
	}

	/**
	 * 标题文字右侧需要让出的宽度 — 供 {@code GuiFeederWindow.getTitlePadEnd} 使用
	 * <br/>
	 * 标题居中绘制区间为 [padStart, xSize - padEnd]，不让出按钮占位时长标题会压到按钮上。
	 *
	 * @param buttonCount 标题栏右侧按钮数量
	 * @return 右侧保留宽度
	 */
	static int titleButtonsReservedWidth(int buttonCount) {
		if (buttonCount <= 0) return 0;
		return buttonCount * (PAGE_BTN_WIDTH + PAGE_BTN_GAP) + PAGE_BTN_RIGHT_MARGIN;
	}

	/**
	 * 计算窗口宽度 — 供 {@link GuiFeederTab} 居中定位使用
	 * <br/>
	 * 模块 4 修复（v2.4 最终版）：暴露为 public，避免 GuiFeederTab 使用错误的窗口宽度
	 * 导致窗口定位偏右超出 GUI 右边框。原 GuiFeederTab 计算 windowWidth = cols*20+20，
	 * 而实际窗口宽度 = cols*20+75（含信息面板），差异 55px 导致居中定位错误。
	 */
	public static int calculateWidth(int cols) {
		return LEFT_PADDING + (cols * SLOT_PITCH - 2) + PANEL_GAP + INFO_PANEL_WIDTH + LEFT_PADDING;
	}

	/**
	 * 计算窗口高度 — 基于实际可见行数与信息面板内容所需高度
	 * <br/>
	 * 模块 4 修复（v2.4 最终版）：窗口高度 = 标题 + max(网格高度, 信息面板内容高度) + 底部提示。
	 * 修复前窗口高度仅由网格高度决定，基础蜂箱（3 行喂食槽，面板高 58px）放入 9 种不同花朵时，
	 * 第 5/6 个花朵名称及"+N 更多"超出信息面板底部边框。现在面板高度按内容动态计算。
	 *
	 * @param rows        喂食槽行数（数据层）
	 * @param flowerCount 已放置的不同花朵类型数
	 * @return 窗口总高度
	 */
	public static int calculateHeight(int rows, int flowerCount) {
		int visibleRows = Math.min(rows, MAX_ROWS_PER_PAGE);
		int gridHeight = visibleRows * SLOT_PITCH - 2;
		int panelHeight = calculateInfoPanelHeight(rows, flowerCount);
		return TITLE_HEIGHT + Math.max(gridHeight, panelHeight) + HINT_HEIGHT;
	}

	/**
	 * 统计喂食槽中已放置的不同花朵类型数量
	 * <br/>
	 * 按物品 + 组件（ItemStack.isSameItemSameComponents）去重，与 {@link GuiFeederWindow#renderInfoPanel} 统计逻辑一致。
	 *
	 * @param tile 蜂箱方块实体
	 * @return 不同类型花朵数
	 */
	static int countFlowerTypes(TileEntityMekApiary tile) {
		List<ItemStack> types = new ArrayList<>();
		for (var slot : tile.getFeederSlots()) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) continue;
			boolean found = false;
			for (ItemStack existing : types) {
				if (ItemStack.isSameItemSameComponents(existing, stack)) {
					found = true;
					break;
				}
			}
			if (!found) {
				types.add(stack);
			}
		}
		return types.size();
	}

	/**
	 * 计算右侧信息面板所需高度（内容自适应）
	 * <br/>
	 * 与 {@link GuiFeederWindow#renderInfoPanel} 的 textY 递增逻辑保持一致：
	 * <ul>
	 *   <li>槽位使用率 + 状态 + 花朵标题 + 花朵列表首行偏移 = 36px（花朵1 的 textY）</li>
	 *   <li>花朵列表：每行 7px，最多 6 行</li>
	 *   <li>花朵超过 6 种时 "+N 更多" 额外 +7px</li>
	 *   <li>翻页时页码额外 +18px（页码标签 + 页码值）</li>
	 *   <li>底部留出文字高度 + 内边距余量（6px）</li>
	 * </ul>
	 *
	 * @param rows        喂食槽行数（用于判断是否翻页）
	 * @param flowerCount 已放置的不同花朵类型数
	 * @return 信息面板所需高度
	 */
	static int calculateInfoPanelHeight(int rows, int flowerCount) {
		int shown = Math.min(flowerCount, 6);
		// 36 = 槽位(10) + 状态(8) + 花朵标题(10) + 花朵列表偏移(8)，即首朵花的 textY
		int contentHeight = 36 + shown * 7 + (flowerCount > 6 ? 7 : 0);
		// 翻页（rows > MAX_ROWS_PER_PAGE）时页码额外占用 18px
		if (rows > MAX_ROWS_PER_PAGE) {
			contentHeight += 18;
		}
		// 底部文字高度 + 内边距余量
		return contentHeight + 6;
	}

	/**
	 * 截断过长的花朵名称 — 模块 5 修复
	 * <br/>
	 * 信息面板宽度有限（{@link #INFO_PANEL_WIDTH}=59px），花朵名称使用 0.55F 缩放渲染，
	 * 当 9 种不同花朵填满喂食槽时，长名称（如"蓝色风信子"）会超出边框。
	 * 本方法使用 {@link net.minecraft.client.gui.Font#plainSubstrByWidth} 截断到
	 * {@link #MAX_FLOWER_NAME_WIDTH} 内并追加 "..." 省略号。
	 * <p>
	 * 实现参考 {@code BeeNameRenderer.truncateIfNeeded}，但常量针对喂食器面板调整。
	 *
	 * @param font Minecraft 字体
	 * @param name 原始花朵名称组件
	 * @return 截断后的名称组件（超长时带省略号）
	 */
	static Component truncateFlowerName(net.minecraft.client.gui.Font font, Component name) {
		if (font.width(name) <= MAX_FLOWER_NAME_WIDTH) {
			return name;
		}
		String plainText = name.getString();
		int ellipsisWidth = font.width(ELLIPSIS);
		int maxTextWidth = MAX_FLOWER_NAME_WIDTH - ellipsisWidth;
		if (maxTextWidth <= 0) {
			return Component.literal(ELLIPSIS);
		}
		String truncated = font.plainSubstrByWidth(plainText, maxTextWidth);
		return Component.literal(truncated + ELLIPSIS);
	}
}
