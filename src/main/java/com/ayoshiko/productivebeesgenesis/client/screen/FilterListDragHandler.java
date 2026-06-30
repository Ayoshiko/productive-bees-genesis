package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

/**
 * FilterListScreen 的拖拽与滚动条交互处理器
 * <p>
 * 将条目拖拽排序、滚动条拖拽/点击跳转、滚动条渲染及可见条目删除按钮重建等
 * 交互逻辑从屏幕类中剥离，降低 FilterListScreen 的复杂度，便于维护与扩展。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责拖拽与滚动条交互，不涉及配置读写或列表数据语义</li>
 *   <li>组合模式 — 持有 {@link FilterListScreen} 引用，通过包级访问共享必要状态</li>
 * </ul>
 * <br/>
 * 线程安全：客户端 GUI 单线程访问，无需同步。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListDragHandler {

	private final FilterListScreen screen;

	/** 拖拽排序状态：源条目索引 */
	private int dragSourceIndex = -1;
	/** 拖拽排序状态：目标插入位置索引 */
	private int dragInsertIndex = -1;
	/** 是否正在拖拽条目进行排序 */
	private boolean isDragging = false;
	/** 是否正在拖动滚动条滑块 */
	private boolean isDraggingScrollBar = false;

	FilterListDragHandler(FilterListScreen screen) {
		this.screen = screen;
	}

	// ========== 滚动条几何与命中测试 ==========

	/** 获取滚动条轨道左侧 X 坐标 */
	int getScrollBarX() {
		return screen.width - FilterListScreen.SCREEN_MARGIN - FilterListScreen.SCROLL_BAR_WIDTH;
	}

	/** 判断鼠标是否位于滚动条轨道区域内 */
	boolean isMouseOverScrollBar(double mouseX, double mouseY) {
		return mouseX >= getScrollBarX() && mouseX < getScrollBarX() + FilterListScreen.SCROLL_BAR_WIDTH
				&& mouseY >= FilterListScreen.LIST_TOP_Y
				&& mouseY < screen.height - FilterListScreen.LIST_BOTTOM_MARGIN;
	}

	/** 判断鼠标是否位于滚动条滑块上 */
	boolean isMouseOverScrollBarThumb(double mouseX, double mouseY) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return false;
		}
		return mouseY >= thumb.y && mouseY < thumb.y + thumb.height;
	}

	/**
	 * 计算当前滚动条滑块位置与高度；列表无需滚动时返回 {@code null}。
	 */
	ScrollBarThumb calculateScrollBarThumb() {
		int total = screen.beeTypes.size();
		int visible = screen.getVisibleEntryCount();
		if (total <= visible) {
			return null;
		}
		int listBottom = screen.height - FilterListScreen.LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - FilterListScreen.LIST_TOP_Y;
		int thumbHeight = Math.max(16, trackHeight * visible / total);
		int maxScroll = total - visible;
		int thumbY = FilterListScreen.LIST_TOP_Y
				+ (trackHeight - thumbHeight) * screen.scrollOffset / Math.max(1, maxScroll);
		return new ScrollBarThumb(thumbY, thumbHeight);
	}

	/**
	 * 根据鼠标 Y 坐标更新滚动偏移，用于拖动滚动条滑块或点击轨道跳转。
	 */
	void updateScrollOffsetFromMouseY(double mouseY) {
		int total = screen.beeTypes.size();
		int visible = screen.getVisibleEntryCount();
		int maxScroll = total - visible;
		if (maxScroll <= 0) {
			return;
		}
		int listBottom = screen.height - FilterListScreen.LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - FilterListScreen.LIST_TOP_Y;
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return;
		}
		int available = trackHeight - thumb.height;
		if (available <= 0) {
			return;
		}
		double relative = mouseY - FilterListScreen.LIST_TOP_Y - thumb.height / 2.0;
		int offset = (int) Math.round(relative * maxScroll / available);
		screen.scrollOffset = Math.max(0, Math.min(maxScroll, offset));
	}

	/**
	 * 渲染滚动条轨道与滑块。
	 */
	void renderScrollBar(GuiGraphics graphics) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return;
		}
		int listBottom = screen.height - FilterListScreen.LIST_BOTTOM_MARGIN;
		int scrollX = getScrollBarX();
		graphics.fill(scrollX, FilterListScreen.LIST_TOP_Y,
				scrollX + FilterListScreen.SCROLL_BAR_WIDTH, listBottom, 0xFF404040);
		graphics.fill(scrollX, thumb.y,
				scrollX + FilterListScreen.SCROLL_BAR_WIDTH, thumb.y + thumb.height, 0xFFA0A0A0);
	}

	// ========== 滚动条交互 ==========

	/**
	 * 处理滚动条区域的鼠标按下：拖拽滑块或点击轨道快速跳转。
	 * <p>
	 * Task 11 修复关联：点击轨道跳转后需重建删除按钮以匹配新的可见条目。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleScrollbarClick(double mouseX, double mouseY, int button) {
		if (button != 0) {
			return false;
		}
		if (screen.beeTypes.size() <= screen.getVisibleEntryCount()) {
			return false;
		}
		if (!isMouseOverScrollBar(mouseX, mouseY)) {
			return false;
		}
		if (isMouseOverScrollBarThumb(mouseX, mouseY)) {
			isDraggingScrollBar = true;
		} else {
			updateScrollOffsetFromMouseY(mouseY);
			// 点击轨道跳转后重建删除按钮以匹配新的可见条目
			rebuildEntryButtonsOnly();
		}
		return true;
	}

	// ========== 条目删除按钮重建 ==========

	/**
	 * 仅重建条目删除按钮，避免滚动时全量 {@code rebuildWidgets}。
	 * <p>
	 * 性能优化：滚动是高频操作，全量 rebuildWidgets 会销毁并重建搜索框、模式按钮、
	 * 输入框等与滚动无关的组件。此方法仅移除旧的删除按钮并创建新的，
	 * 将滚动开销从 O(全部组件) 降低到 O(可见条目数)。
	 * <p>
	 * Task 11 修复核心：滚动时必须同步重建删除按钮，否则按钮回调捕获的索引
	 * 与实际可见条目错位，导致删除错误条目。
	 */
	void rebuildEntryButtonsOnly() {
		for (Button btn : screen.entryButtons) {
			screen.removeWidgetBridge(btn);
		}
		screen.entryButtons.clear();
		screen.createEntryButtons();
	}

	// ========== 条目拖拽排序 ==========

	/** 开始拖拽指定索引的条目 */
	void startDrag(int index) {
		this.dragSourceIndex = index;
		this.dragInsertIndex = index;
		this.isDragging = true;
	}

	/**
	 * 完成拖拽：将源条目移动到目标位置，然后重建控件。
	 */
	void finishDrag() {
		List<String> beeTypes = screen.beeTypes;
		if (dragSourceIndex >= 0 && dragSourceIndex < beeTypes.size()
				&& dragInsertIndex != dragSourceIndex) {
			int target = Math.max(0, Math.min(beeTypes.size(), dragInsertIndex));
			String moved = beeTypes.remove(dragSourceIndex);
			if (target > dragSourceIndex) {
				target--;
			}
			beeTypes.add(target, moved);
		}
		isDragging = false;
		dragSourceIndex = -1;
		dragInsertIndex = -1;
		// rebuildWidgets 为 Screen 的 protected 方法，通过包级桥接方法转发
		screen.rebuildWidgetsBridge();
	}

	// ========== 鼠标事件委托 ==========

	/**
	 * 处理鼠标拖拽事件：滚动条滑块拖拽或条目拖拽排序。
	 * <p>
	 * Task 11 修复核心：拖拽滑块时 scrollOffset 持续变化，可见条目集合随之改变，
	 * 必须同步重建删除按钮（回调捕获了条目索引），否则按钮位置与条目错位。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseDragged(double mouseX, double mouseY, int button) {
		if (isDraggingScrollBar && button == 0) {
			updateScrollOffsetFromMouseY(mouseY);
			rebuildEntryButtonsOnly();
			return true;
		}
		if (isDragging && button == 0) {
			dragInsertIndex = screen.renderer.getInsertionIndex(mouseY, screen.beeTypes, screen.scrollOffset);
			return true;
		}
		return false;
	}

	/**
	 * 处理鼠标释放事件：结束滚动条拖拽或条目拖拽。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseReleased(double mouseX, double mouseY, int button) {
		if (isDraggingScrollBar && button == 0) {
			isDraggingScrollBar = false;
			// 滚动条拖拽期间 scrollOffset 连续变化，释放时统一重建删除按钮以匹配当前可见条目
			rebuildEntryButtonsOnly();
			return true;
		}
		if (isDragging && button == 0) {
			finishDrag();
			return true;
		}
		return false;
	}

	/** 获取拖拽源索引（供渲染拖放指示线使用） */
	int getDragSourceIndex() {
		return dragSourceIndex;
	}

	/** 获取拖拽插入位置索引（供渲染拖放指示线使用） */
	int getDragInsertIndex() {
		return dragInsertIndex;
	}

	/** 滚动条滑块位置/高度记录 */
	record ScrollBarThumb(int y, int height) {
	}
}
