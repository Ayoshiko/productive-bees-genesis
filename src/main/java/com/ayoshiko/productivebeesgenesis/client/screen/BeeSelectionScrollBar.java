package com.ayoshiko.productivebeesgenesis.client.screen;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;

/**
 * BeeSelectionScreen 的滚动条交互与渲染辅助类
 * <p>
 * 将滚动条滑块位置计算、轨道/滑块渲染、拖拽与点击跳转等逻辑从屏幕类中剥离，
 * 降低 BeeSelectionScreen 的复杂度（SRP）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责滚动条几何、渲染与交互，不涉及列表数据或选择状态</li>
 *   <li>组合模式 — 持有 {@link BeeSelectionScreen}、{@link BeeSelectionState} 与
 *       {@link BeeSelectionSorter} 引用，通过包级访问共享必要状态</li>
 * </ul>
 * <br/>
 * 线程安全：客户端 GUI 单线程访问，无需同步。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class BeeSelectionScrollBar {

	private final BeeSelectionScreen screen;
	private final BeeSelectionState state;
	private final BeeSelectionSorter sorter;
	/** 是否正在拖动滚动条滑块 */
	private boolean isDragging = false;

	BeeSelectionScrollBar(BeeSelectionScreen screen, BeeSelectionState state, BeeSelectionSorter sorter) {
		this.screen = screen;
		this.state = state;
		this.sorter = sorter;
	}

	/** 滚动条滑块位置/高度记录 */
	record ScrollBarThumb(int y, int height) {
	}

	/** 获取滚动条轨道左侧 X 坐标 */
	private int getScrollBarX() {
		return screen.width - BeeSelectionScreen.SIDE_PADDING - BeeSelectionScreen.SCROLL_BAR_WIDTH;
	}

	/** 判断鼠标是否位于滚动条轨道区域内 */
	private boolean isMouseOverScrollBar(double mouseX, double mouseY) {
		return mouseX >= getScrollBarX() && mouseX < getScrollBarX() + BeeSelectionScreen.SCROLL_BAR_WIDTH
				&& mouseY >= BeeSelectionScreen.LIST_TOP_Y
				&& mouseY < screen.height - BeeSelectionScreen.LIST_BOTTOM_MARGIN;
	}

	/** 判断鼠标是否位于滚动条滑块上 */
	private boolean isMouseOverScrollBarThumb(double mouseX, double mouseY) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return false;
		}
		return mouseY >= thumb.y && mouseY < thumb.y + thumb.height;
	}

	/**
	 * 计算当前滚动条滑块位置与高度；列表无需滚动时返回 {@code null}。
	 */
	private ScrollBarThumb calculateScrollBarThumb() {
		int total = sorter.getDisplayItems().size();
		int visible = screen.getVisibleEntryCount();
		if (total <= visible) {
			return null;
		}
		int listBottom = screen.height - BeeSelectionScreen.LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - BeeSelectionScreen.LIST_TOP_Y;
		int thumbHeight = Math.max(20, trackHeight * visible / total);
		int maxScroll = total - visible;
		int thumbY = BeeSelectionScreen.LIST_TOP_Y
				+ (trackHeight - thumbHeight) * state.getScrollOffset() / Math.max(1, maxScroll);
		return new ScrollBarThumb(thumbY, thumbHeight);
	}

	/**
	 * 根据鼠标 Y 坐标更新滚动偏移，用于拖动滚动条滑块或点击轨道跳转。
	 */
	private void updateScrollOffsetFromMouseY(double mouseY) {
		int total = sorter.getDisplayItems().size();
		int visible = screen.getVisibleEntryCount();
		int maxScroll = total - visible;
		if (maxScroll <= 0) {
			return;
		}
		int listBottom = screen.height - BeeSelectionScreen.LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - BeeSelectionScreen.LIST_TOP_Y;
		int thumbHeight = Math.max(20, trackHeight * visible / total);
		int available = trackHeight - thumbHeight;
		if (available <= 0) {
			return;
		}
		double relative = mouseY - BeeSelectionScreen.LIST_TOP_Y - thumbHeight / 2.0;
		int offset = (int) Math.round(relative * maxScroll / available);
		state.setScrollOffset(offset);
		state.clampScrollOffset(maxScroll);
	}

	/**
	 * 渲染滚动条轨道与滑块。
	 */
	void render(GuiGraphics graphics) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return;
		}
		int listBottom = screen.height - BeeSelectionScreen.LIST_BOTTOM_MARGIN;
		int scrollX = getScrollBarX();
		graphics.fill(scrollX, BeeSelectionScreen.LIST_TOP_Y,
				scrollX + BeeSelectionScreen.SCROLL_BAR_WIDTH, listBottom, GuiColors.SCROLLBAR_TRACK);
		graphics.fill(scrollX, thumb.y,
				scrollX + BeeSelectionScreen.SCROLL_BAR_WIDTH, thumb.y + thumb.height, GuiColors.SCROLLBAR_THUMB);
	}

	/**
	 * 处理鼠标按下事件：拖拽滑块或点击轨道快速跳转。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) {
			return false;
		}
		int total = sorter.getDisplayItems().size();
		if (total <= screen.getVisibleEntryCount()) {
			return false;
		}
		if (!isMouseOverScrollBar(mouseX, mouseY)) {
			return false;
		}
		if (isMouseOverScrollBarThumb(mouseX, mouseY)) {
			isDragging = true;
		} else {
			updateScrollOffsetFromMouseY(mouseY);
		}
		return true;
	}

	/**
	 * 处理鼠标拖拽事件：拖动滑块时更新滚动偏移。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseDragged(double mouseX, double mouseY, int button) {
		if (isDragging && button == 0) {
			updateScrollOffsetFromMouseY(mouseY);
			screen.updateScrollToTopButton();
			return true;
		}
		return false;
	}

	/**
	 * 处理鼠标释放事件：结束滑块拖拽。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseReleased(double mouseX, double mouseY, int button) {
		if (isDragging && button == 0) {
			isDragging = false;
			return true;
		}
		return false;
	}
}
