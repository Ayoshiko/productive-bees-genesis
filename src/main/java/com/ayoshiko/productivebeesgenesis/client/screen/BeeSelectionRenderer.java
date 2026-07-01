package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * BeeSelectionScreen 的列表渲染与命中测试辅助类
 * <p>
 * 负责分组标题、蜜蜂条目、复选框、图标等绘制，以及鼠标点击位置到列表项的映射，
 * 使 BeeSelectionScreen 专注于状态管理与事件调度。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class BeeSelectionRenderer {

	private static final String CHECKBOX_EMPTY = "\u2610";
	private static final String CHECKBOX_CHECKED = "\u2611";
	private static final String ADDED_MARK = "\u2713";
	private static final String COLLAPSED_ARROW = "\u25B6";
	private static final String EXPANDED_ARROW = "\u25BC";

	private final BeeSelectionScreen screen;

	BeeSelectionRenderer(BeeSelectionScreen screen) {
		this.screen = screen;
	}

	/**
	 * 渲染可见的分组标题与蜜蜂条目。
	 */
	void renderDisplayList(GuiGraphics graphics, List<DisplayItem> displayItems,
						   int scrollOffset, int mouseX, int mouseY, BeeSelectionState state) {
		int visibleCount = screen.getVisibleEntryCount();
		int endIndex = Math.min(scrollOffset + visibleCount, displayItems.size());

		for (int i = scrollOffset; i < endIndex; i++) {
			int y = BeeSelectionScreen.LIST_TOP_Y + (i - scrollOffset) * BeeSelectionScreen.ENTRY_SPACING;
			DisplayItem item = displayItems.get(i);
			boolean hovered = isRowHovered(mouseX, mouseY, y);
			if (item instanceof HeaderItem header) {
				renderHeader(graphics, header, y, hovered);
			} else if (item instanceof EntryItem entryItem) {
				renderEntry(graphics, entryItem.entry, y, hovered, state);
			}
		}

		if (displayItems.isEmpty()) {
			renderEmptyResult(graphics);
		}
	}

	/**
	 * 渲染空结果提示。
	 */
	void renderEmptyResult(GuiGraphics graphics) {
		graphics.drawCenteredString(screen.getMinecraft().font,
				Component.translatable("productivebeesgenesis.config.no_search_result"),
				screen.width / 2, BeeSelectionScreen.LIST_TOP_Y + 20, GuiColors.TEXT_DIM_GRAY);
	}

	/**
	 * 根据鼠标 Y 坐标返回命中的分组标题索引；未命中返回 {@code null}。
	 */
	Integer getHeaderIndexAt(double mouseY, List<DisplayItem> displayItems, int scrollOffset) {
		int index = getRowIndexAt(mouseY, displayItems, scrollOffset);
		if (index < 0) {
			return null;
		}
		return displayItems.get(index) instanceof HeaderItem ? index : null;
	}

	/**
	 * 根据鼠标 Y 坐标返回命中的蜜蜂条目索引；未命中返回 {@code null}。
	 */
	Integer getEntryIndexAt(double mouseY, List<DisplayItem> displayItems, int scrollOffset) {
		int index = getRowIndexAt(mouseY, displayItems, scrollOffset);
		if (index < 0) {
			return null;
		}
		return displayItems.get(index) instanceof EntryItem ? index : null;
	}

	// ========== 内部绘制方法 ==========

	private void renderHeader(GuiGraphics graphics, HeaderItem header, int y, boolean hovered) {
		int x = BeeSelectionScreen.SIDE_PADDING + 4;
		int width = screen.width - 2 * BeeSelectionScreen.SIDE_PADDING - BeeSelectionScreen.SCROLL_BAR_WIDTH - 8;

		// 分组标题背景
		int bgColor = hovered ? GuiColors.GROUP_HEADER_BG_HOVER : GuiColors.GROUP_HEADER_BG;
		graphics.fill(x, y, x + width, y + BeeSelectionScreen.ENTRY_HEIGHT, bgColor);
		graphics.fill(x, y + BeeSelectionScreen.ENTRY_HEIGHT - 1, x + width, y + BeeSelectionScreen.ENTRY_HEIGHT, GuiColors.BORDER_GROUP_HEADER);

		String arrow = header.collapsed ? COLLAPSED_ARROW : EXPANDED_ARROW;
		String text = arrow + " " + header.namespace + " (" + header.count + ")";
		graphics.drawString(screen.getMinecraft().font, Component.literal(text), x + 4, y + 8, GuiColors.TEXT_WHITE);
	}

	private void renderEntry(GuiGraphics graphics, BeeSelectionScreen.BeeEntry entry, int y,
							 boolean hovered, BeeSelectionState state) {
		boolean added = screen.isAlreadyAdded(entry);
		boolean selected = !added && state.isSelected(entry.typeId);

		int x = BeeSelectionScreen.SIDE_PADDING + 4;
		int width = screen.width - 2 * BeeSelectionScreen.SIDE_PADDING - BeeSelectionScreen.SCROLL_BAR_WIDTH - 8;

		// 背景：已添加条目使用淡绿色底，未添加使用默认半透明；悬停时叠加高亮
		int bgColor;
		if (hovered) {
			bgColor = added ? GuiColors.OVERLAY_ENTRY_ADDED_HOVER_BG : GuiColors.OVERLAY_ENTRY_HOVER_BG;
		} else {
			bgColor = added ? GuiColors.OVERLAY_ENTRY_ADDED_BG : GuiColors.OVERLAY_ENTRY_BG;
		}
		graphics.fill(x, y, x + width, y + BeeSelectionScreen.ENTRY_HEIGHT, bgColor);
		graphics.fill(x, y, x + width, y + 1, GuiColors.BORDER_DARK);
		graphics.fill(x, y + BeeSelectionScreen.ENTRY_HEIGHT - 1, x + width, y + BeeSelectionScreen.ENTRY_HEIGHT, GuiColors.BORDER_DARK);

		if (added) {
			graphics.fill(x, y, x + 2, y + BeeSelectionScreen.ENTRY_HEIGHT, GuiColors.ADDED_INDICATOR_BAR);
		}

		if (!added) {
			String checkbox = selected ? CHECKBOX_CHECKED : CHECKBOX_EMPTY;
			graphics.drawString(screen.getMinecraft().font, Component.literal(checkbox), x + 2, y + 9, GuiColors.TEXT_WHITE);
		}

		if (!entry.icon.isEmpty()) {
			graphics.renderItem(entry.icon, x + 2 + BeeSelectionScreen.CHECKBOX_COLUMN_WIDTH, y + 7);
		}

		boolean activeSearch = !state.getSearchText().isEmpty();
		int nameColor = activeSearch ? GuiColors.TEXT_WHITE : (added ? GuiColors.TEXT_NAME_ADDED_GREEN : GuiColors.TEXT_NAME_YELLOW);
		int typeColor = activeSearch ? GuiColors.TEXT_NAME_YELLOW : GuiColors.TEXT_PRODUCT_GRAY;
		int textX = x + BeeSelectionScreen.CHECKBOX_COLUMN_WIDTH + BeeSelectionScreen.ICON_COLUMN_WIDTH + 6;
		graphics.drawString(screen.getMinecraft().font, entry.displayName, textX, y + 4, nameColor);

		int checkRightMargin = 18;
		int typeMaxWidth = Math.max(20, width - (BeeSelectionScreen.CHECKBOX_COLUMN_WIDTH + BeeSelectionScreen.ICON_COLUMN_WIDTH + 6) - checkRightMargin);
		String typeText = screen.getMinecraft().font.plainSubstrByWidth(entry.typeId, typeMaxWidth);
		graphics.drawString(screen.getMinecraft().font, typeText, textX, y + 16, typeColor);

		int productX = textX + BeeSelectionScreen.PRODUCT_OFFSET_X - 2;
		int listRight = x + width - checkRightMargin;
		int productMaxWidth = listRight - productX;
		if (productMaxWidth > 0) {
			String productText = screen.getMinecraft().font.plainSubstrByWidth(entry.productInfo.getString(), productMaxWidth);
			graphics.drawString(screen.getMinecraft().font, Component.literal(productText), productX, y + 10, GuiColors.TEXT_PRODUCT_LIGHT);
		}

		if (added) {
			int checkX = x + width - 12;
			graphics.drawString(screen.getMinecraft().font, Component.literal(ADDED_MARK), checkX, y + 9, GuiColors.TEXT_ADDED_MARK);
		}
	}

	private boolean isRowHovered(int mouseX, int mouseY, int y) {
		return mouseX >= BeeSelectionScreen.SIDE_PADDING
				&& mouseX < screen.width - BeeSelectionScreen.SIDE_PADDING
				&& mouseY >= y && mouseY < y + BeeSelectionScreen.ENTRY_HEIGHT;
	}

	private int getRowIndexAt(double mouseY, List<DisplayItem> displayItems, int scrollOffset) {
		if (mouseY < BeeSelectionScreen.LIST_TOP_Y) {
			return -1;
		}
		int relative = (int) ((mouseY - BeeSelectionScreen.LIST_TOP_Y) / BeeSelectionScreen.ENTRY_SPACING);
		int index = scrollOffset + relative;
		return index >= 0 && index < displayItems.size() ? index : -1;
	}

	// ========== 显示项类型 ==========

	/**
	 * 列表显示项基类，仅作类型标记。
	 */
	static abstract class DisplayItem {
	}

	/**
	 * 分组标题项。
	 */
	static final class HeaderItem extends DisplayItem {
		final String namespace;
		final int count;
		boolean collapsed;

		HeaderItem(String namespace, int count, boolean collapsed) {
			this.namespace = namespace;
			this.count = count;
			this.collapsed = collapsed;
		}
	}

	/**
	 * 蜜蜂条目项。
	 */
	static final class EntryItem extends DisplayItem {
		final BeeSelectionScreen.BeeEntry entry;

		EntryItem(BeeSelectionScreen.BeeEntry entry) {
			this.entry = entry;
		}
	}
}
