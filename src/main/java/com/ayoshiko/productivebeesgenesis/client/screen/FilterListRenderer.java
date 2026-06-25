package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.List;
import java.util.Set;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * FilterListScreen 的列表渲染与命中测试辅助类
 * <p>
 * 将列表表头、条目、复选框、拖拽手柄、拖放指示线等绘制逻辑从屏幕类中剥离，
 * 避免 FilterListScreen 因功能叠加而过度膨胀，便于后续维护与扩展。
 * <p>
 * 本类不持有业务状态，所有数据均由 {@link FilterListScreen} 提供。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListRenderer {

	private static final String DRAG_HANDLE = "\u2261";
	private static final String CHECKBOX_EMPTY = "\u2610";
	private static final String CHECKBOX_CHECKED = "\u2611";

	private final FilterListScreen screen;

	FilterListRenderer(FilterListScreen screen) {
		this.screen = screen;
	}

	/**
	 * 渲染列表表头，包括新增的全选复选框列。
	 */
	void renderHeader(GuiGraphics graphics, List<String> beeTypes, Set<String> selectedTypes, int scrollOffset) {
		int indexColumnX = getIndexColumnX();
		int idColumnX = getIdColumnX();
		int nameColumnX = getNameColumnX();
		int actionColumnX = getActionColumnX();

		// 表头 # 与序号右对齐，避免视觉偏移（与最大序号"99"宽度对齐更稳妥）
		String headerNumber = "#";
		int headerWidth = screen.getMinecraft().font.width(headerNumber);
		int headerX = indexColumnX + FilterListScreen.INDEX_COLUMN_WIDTH - headerWidth - 2;
		graphics.drawString(screen.getMinecraft().font, Component.literal(headerNumber),
				headerX, FilterListScreen.LIST_TOP_Y - 14, 0xFFFFFFFF);
		graphics.drawString(screen.getMinecraft().font, Component.translatable("productivebeesgenesis.config.bee_type_id"),
				idColumnX, FilterListScreen.LIST_TOP_Y - 14, 0xFFFFFFFF);
		graphics.drawString(screen.getMinecraft().font, Component.translatable("productivebeesgenesis.config.bee_name"),
				nameColumnX, FilterListScreen.LIST_TOP_Y - 14, 0xFFB0B0B0);
		graphics.drawString(screen.getMinecraft().font, Component.translatable("productivebeesgenesis.config.actions"),
				actionColumnX, FilterListScreen.LIST_TOP_Y - 14, 0xFFB0B0B0);

		// 表头复选框：仅当所有可见条目都被选中时显示 ☑
		boolean allVisibleSelected = areAllVisibleSelected(beeTypes, selectedTypes, scrollOffset);
		String checkbox = allVisibleSelected ? CHECKBOX_CHECKED : CHECKBOX_EMPTY;
		graphics.drawString(screen.getMinecraft().font, Component.literal(checkbox),
				getCheckboxX(), FilterListScreen.LIST_TOP_Y - 14, 0xFFFFFFFF);
	}

	/**
	 * 渲染可见条目，包括拖拽手柄、复选框、序号、图标、类型ID与名称。
	 */
	void renderEntries(GuiGraphics graphics, List<String> beeTypes, Set<String> selectedTypes,
					   int scrollOffset, int mouseX, int mouseY) {
		int visibleCount = screen.getVisibleEntryCount();
		int endIndex = Math.min(scrollOffset + visibleCount, beeTypes.size());

		for (int i = scrollOffset; i < endIndex; i++) {
			int entryY = FilterListScreen.LIST_TOP_Y + (i - scrollOffset) * FilterListScreen.ENTRY_SPACING;
			String beeTypeId = beeTypes.get(i);
			boolean hovered = isRowHovered(mouseX, mouseY, entryY);
			renderBeeEntry(graphics, beeTypeId, i, entryY, hovered, selectedTypes.contains(beeTypeId));
		}

		if (beeTypes.isEmpty()) {
			graphics.drawCenteredString(screen.getMinecraft().font,
					Component.translatable("productivebeesgenesis.config.empty_list"),
					screen.width / 2, FilterListScreen.LIST_TOP_Y + 20, 0xFF808080);
		}
	}

	/**
	 * 在裁剪区域外渲染拖放指示线与被拖拽条目的半透明幽灵，避免被 scissor 裁剪。
	 */
	void renderDragOverlay(GuiGraphics graphics, List<String> beeTypes, int scrollOffset,
						   int dragSourceIndex, int dragInsertIndex, int mouseX, int mouseY) {
		if (dragSourceIndex < 0 || dragSourceIndex >= beeTypes.size()) {
			return;
		}

		int listBottom = screen.height - FilterListScreen.LIST_BOTTOM_MARGIN;
		int listLeft = FilterListScreen.SCREEN_MARGIN;
		int listRight = screen.width - FilterListScreen.SCREEN_MARGIN;

		// 插入指示线
		int insertY = FilterListScreen.LIST_TOP_Y + (dragInsertIndex - scrollOffset) * FilterListScreen.ENTRY_SPACING;
		insertY = Math.max(FilterListScreen.LIST_TOP_Y, Math.min(listBottom, insertY));
		graphics.fill(listLeft + 1, insertY - 1, listRight - 1, insertY, 0xFFFFFFFF);
		graphics.fill(listLeft + 1, insertY, listRight - 1, insertY + 1, 0x80FFFFFF);

		// 被拖拽条目的幽灵（半透明背景 + 类型ID）
		int ghostY = mouseY - FilterListScreen.ENTRY_HEIGHT / 2;
		graphics.fill(listLeft + 1, ghostY, listRight - 1, ghostY + FilterListScreen.ENTRY_HEIGHT, 0x40000000);
		graphics.drawString(screen.getMinecraft().font, Component.literal(beeTypes.get(dragSourceIdIndexSafe(beeTypes, dragSourceIndex))),
				listLeft + 10, ghostY + 7, 0x80FFFFFF);
	}

	/**
	 * 判断鼠标是否点击了表头复选框区域。
	 */
	boolean isHeaderCheckboxHit(double mouseX, double mouseY) {
		return mouseX >= getCheckboxX() && mouseX < getCheckboxX() + FilterListScreen.CHECKBOX_COLUMN_WIDTH
				&& mouseY >= FilterListScreen.LIST_TOP_Y - 16 && mouseY < FilterListScreen.LIST_TOP_Y - 2;
	}

	/**
	 * 判断鼠标是否点击了某行（任意位置，但排除拖拽手柄与删除按钮区域），返回列表真实索引；
	 * 未命中或命中操作区域返回 {@code null}。
	 */
	Integer getRowCheckboxIndex(double mouseX, double mouseY, List<String> beeTypes, int scrollOffset) {
		int row = getRowIndexAt(mouseY, beeTypes, scrollOffset);
		if (row < 0) {
			return null;
		}
		// 点击拖拽手柄时不切换选择
		if (mouseX >= getDragHandleX() && mouseX < getDragHandleX() + FilterListScreen.DRAG_HANDLE_WIDTH) {
			return null;
		}
		// 点击右侧操作区（删除按钮及滚动条）时不切换选择
		int actionLeft = getActionColumnX() - 4;
		if (mouseX >= actionLeft) {
			return null;
		}
		return row;
	}

	/**
	 * 判断鼠标是否按下了某行的拖拽手柄，返回列表真实索引；未命中返回 {@code null}。
	 */
	Integer getDragHandleIndex(double mouseX, double mouseY, List<String> beeTypes, int scrollOffset) {
		int row = getRowIndexAt(mouseY, beeTypes, scrollOffset);
		if (row < 0) {
			return null;
		}
		return mouseX >= getDragHandleX() && mouseX < getDragHandleX() + FilterListScreen.DRAG_HANDLE_WIDTH ? row : null;
	}

	/**
	 * 根据鼠标 Y 坐标计算插入位置（以条目为单位的索引）。
	 */
	int getInsertionIndex(double mouseY, List<String> beeTypes, int scrollOffset) {
		int relative = (int) Math.round((mouseY - FilterListScreen.LIST_TOP_Y) / (double) FilterListScreen.ENTRY_SPACING);
		return Math.max(0, Math.min(beeTypes.size(), scrollOffset + relative));
	}

	// ========== 内部绘制方法 ==========

	private void renderBeeEntry(GuiGraphics graphics, String beeTypeId, int index, int y,
								boolean hovered, boolean selected) {
		int indexColumnX = getIndexColumnX();
		int iconColumnX = getIconColumnX();
		int idColumnX = getIdColumnX();
		int nameColumnX = getNameColumnX();
		int nameColumnMaxWidth = getNameColumnMaxWidth();
		int idColumnMaxWidth = Math.max(20, nameColumnX - idColumnX - 4);

		// 行背景：悬停时高亮
		if (hovered) {
			graphics.fill(FilterListScreen.SCREEN_MARGIN + 1, y,
					screen.width - FilterListScreen.SCREEN_MARGIN - 1, y + FilterListScreen.ENTRY_HEIGHT, 0x30FFFFFF);
		}

		// 拖拽手柄
		int handleTextWidth = screen.getMinecraft().font.width(DRAG_HANDLE);
		int handleX = getDragHandleX() + (FilterListScreen.DRAG_HANDLE_WIDTH - handleTextWidth) / 2;
		graphics.drawString(screen.getMinecraft().font, Component.literal(DRAG_HANDLE), handleX, y + 5, 0xFF808080);

		// 复选框
		String checkbox = selected ? CHECKBOX_CHECKED : CHECKBOX_EMPTY;
		graphics.drawString(screen.getMinecraft().font, Component.literal(checkbox),
				getCheckboxX(), y + 5, 0xFFFFFFFF);

		// 序号（从1开始，右对齐）
		String indexText = String.valueOf(index + 1);
		int indexX = indexColumnX + FilterListScreen.INDEX_COLUMN_WIDTH - screen.getMinecraft().font.width(indexText) - 2;
		graphics.drawString(screen.getMinecraft().font, indexText, indexX, y + 5, 0xFF909090);

		// 代表物品图标
		ItemStack icon = screen.getBeeIcon(beeTypeId);
		if (!icon.isEmpty()) {
			int iconX = iconColumnX + (FilterListScreen.ICON_COLUMN_WIDTH - 16) / 2;
			graphics.renderItem(icon, iconX, y + 4);
		}

		// 类型ID
		String trimmedId = screen.getMinecraft().font.plainSubstrByWidth(beeTypeId, Math.max(20, idColumnMaxWidth));
		graphics.drawString(screen.getMinecraft().font, trimmedId, idColumnX, y + 2, 0xFFE0E0E0);

		// 显示名称和产物信息 — 使用screen缓存避免每帧重复解析
		Component displayName = screen.getBeeDisplayName(beeTypeId);
		String trimmedName = screen.getMinecraft().font.plainSubstrByWidth(displayName.getString(), Math.max(20, nameColumnMaxWidth));
		graphics.drawString(screen.getMinecraft().font, Component.literal(trimmedName), nameColumnX, y + 2, 0xFFFFFF80);

		Component productInfo = screen.getBeeProductInfo(beeTypeId);
		String trimmed = screen.getMinecraft().font.plainSubstrByWidth(productInfo.getString(), Math.max(20, nameColumnMaxWidth));
		graphics.drawString(screen.getMinecraft().font, Component.literal(trimmed), nameColumnX, y + 14, 0xFFC0C0C0);
	}

	private boolean isRowHovered(int mouseX, int mouseY, int entryY) {
		return mouseX >= FilterListScreen.SCREEN_MARGIN
				&& mouseX < screen.width - FilterListScreen.SCREEN_MARGIN
				&& mouseY >= entryY && mouseY < entryY + FilterListScreen.ENTRY_HEIGHT;
	}

	private int getRowIndexAt(double mouseY, List<String> beeTypes, int scrollOffset) {
		if (mouseY < FilterListScreen.LIST_TOP_Y) {
			return -1;
		}
		int relative = (int) ((mouseY - FilterListScreen.LIST_TOP_Y) / FilterListScreen.ENTRY_SPACING);
		int index = scrollOffset + relative;
		return index >= 0 && index < beeTypes.size() ? index : -1;
	}

	private boolean areAllVisibleSelected(List<String> beeTypes, Set<String> selectedTypes, int scrollOffset) {
		int visibleCount = screen.getVisibleEntryCount();
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		for (int i = scrollOffset; i < end; i++) {
			if (!selectedTypes.contains(beeTypes.get(i))) {
				return false;
			}
		}
		return end > scrollOffset;
	}

	private int dragSourceIdIndexSafe(List<String> beeTypes, int dragSourceIndex) {
		return Math.max(0, Math.min(beeTypes.size() - 1, dragSourceIndex));
	}

	// ========== 列位置计算 ==========

	private int getListLeftX() {
		return FilterListScreen.SCREEN_MARGIN;
	}

	private int getDragHandleX() {
		return getListLeftX() + 2;
	}

	private int getCheckboxX() {
		return getDragHandleX() + FilterListScreen.DRAG_HANDLE_WIDTH + 2;
	}

	private int getIndexColumnX() {
		return getCheckboxX() + FilterListScreen.CHECKBOX_COLUMN_WIDTH + 2;
	}

	private int getIconColumnX() {
		return getIndexColumnX() + FilterListScreen.INDEX_COLUMN_WIDTH;
	}

	private int getIdColumnX() {
		return getIconColumnX() + FilterListScreen.ICON_COLUMN_WIDTH;
	}

	private int getIdColumnWidth() {
		int available = screen.width - 2 * FilterListScreen.SCREEN_MARGIN
				- FilterListScreen.ACTION_AREA_WIDTH
				- FilterListScreen.DRAG_HANDLE_WIDTH - FilterListScreen.CHECKBOX_COLUMN_WIDTH
				- FilterListScreen.INDEX_COLUMN_WIDTH - FilterListScreen.ICON_COLUMN_WIDTH;
		return Math.min(180, Math.max(100, available / 3));
	}

	private int getNameColumnX() {
		return getIdColumnX() + getIdColumnWidth();
	}

	private int getNameColumnMaxWidth() {
		return Math.max(20, getActionColumnX() - getNameColumnX() - 8);
	}

	private int getActionColumnX() {
		return screen.width - FilterListScreen.SCREEN_MARGIN - FilterListScreen.DELETE_BUTTON_WIDTH;
	}
}
