package com.ayoshiko.productivebeesgenesis.client.screen;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 垂直滚动条基类，封装滚动条几何计算、命中测试、渲染与鼠标交互。
 * <p>
 * 将 {@link FilterListDragHandler} 与 {@link BeeSelectionScrollBar} 中约 90 行
 * 重复度 80%-100% 的滚动条逻辑抽取为公共基类，遵循下述设计原则：
 * <ul>
 *   <li>SRP — 仅负责滚动条几何、渲染与交互，不涉及列表数据语义或条目拖拽</li>
 *   <li>OCP/DIP — 通过抽象方法获取屏幕数据，子类只需提供数据源即可扩展</li>
 *   <li>钩子方法 — {@link #onScrollChanged}/{@link #onThumbClicked}/{@link #onThumbReleased}
 *       供子类在滚动偏移变化、滑块点击/释放时执行附加逻辑（如重建按钮、更新状态）</li>
 * </ul>
 * <br/>
 * 线程安全：客户端 GUI 单线程访问，无需同步。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
abstract class AbstractVerticalScrollBar {

	/** 是否正在拖动滚动条滑块 */
	protected boolean isDragging = false;

	// ========== 抽象方法：子类提供数据源 ==========

	/** 屏幕宽度（用于计算滚动条 X 坐标，对应 {@code screen.width}） */
	protected abstract int getScreenX();

	/** 屏幕高度（用于计算列表底部 Y 坐标，对应 {@code screen.height}） */
	protected abstract int getScreenY();

	/** 屏幕高度（保留扩展点，当前与 {@link #getScreenY()} 一致） */
	protected abstract int getScreenHeight();

	/** 滚动条宽度 */
	protected abstract int getScrollBarWidth();

	/** 滚动条右侧边距（FilterListScreen 为 SCREEN_MARGIN，BeeSelectionScreen 为 SIDE_PADDING） */
	protected abstract int getScrollBarMargin();

	/** 列表区域顶部 Y 坐标 */
	protected abstract int getListTopY();

	/** 列表底部边距（screen.height 与列表底部 Y 的差值） */
	protected abstract int getListHeight();

	/** 列表总条目数 */
	protected abstract int getTotalCount();

	/** 可见条目数 */
	protected abstract int getVisibleCount();

	/** 滑块最小高度（FilterList 为 16，BeeSelection 为 20） */
	protected abstract int getMinThumbHeight();

	/** 当前滚动偏移 */
	protected abstract int getScrollOffset();

	/** 设置滚动偏移（子类负责写入对应状态字段） */
	protected abstract void setScrollOffset(int offset);

	// ========== 钩子方法（默认空实现，子类可重写） ==========

	/** 滚动偏移变化时调用（点击轨道跳转或拖拽滑块后） */
	protected void onScrollChanged() {}

	/** 滑块被点击时调用（开始拖拽滑块） */
	protected void onThumbClicked() {}

	/** 滑块拖拽释放时调用 */
	protected void onThumbReleased() {}

	// ========== 公共逻辑 ==========

	/** 滚动条滑块位置/高度记录 */
	record ScrollBarThumb(int y, int height) {
	}

	/** 获取滚动条轨道左侧 X 坐标 */
	int getScrollBarX() {
		return getScreenX() - getScrollBarMargin() - getScrollBarWidth();
	}

	/** 列表底部 Y 坐标 */
	private int getListBottomY() {
		return getScreenY() - getListHeight();
	}

	/** 滚动条轨道高度 */
	private int getTrackHeight() {
		return getListBottomY() - getListTopY();
	}

	/** 判断鼠标是否位于滚动条轨道区域内 */
	boolean isMouseOverScrollBar(double mouseX, double mouseY) {
		int scrollX = getScrollBarX();
		return mouseX >= scrollX && mouseX < scrollX + getScrollBarWidth()
				&& mouseY >= getListTopY()
				&& mouseY < getListBottomY();
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
		int total = getTotalCount();
		int visible = getVisibleCount();
		if (total <= visible) {
			return null;
		}
		int trackHeight = getTrackHeight();
		int thumbHeight = Math.max(getMinThumbHeight(), trackHeight * visible / total);
		int maxScroll = total - visible;
		int thumbY = getListTopY()
				+ (trackHeight - thumbHeight) * getScrollOffset() / Math.max(1, maxScroll);
		return new ScrollBarThumb(thumbY, thumbHeight);
	}

	/**
	 * 根据鼠标 Y 坐标更新滚动偏移，用于拖动滚动条滑块或点击轨道跳转。
	 */
	void updateScrollOffsetFromMouseY(double mouseY) {
		int total = getTotalCount();
		int visible = getVisibleCount();
		int maxScroll = total - visible;
		if (maxScroll <= 0) {
			return;
		}
		int trackHeight = getTrackHeight();
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return;
		}
		int available = trackHeight - thumb.height;
		if (available <= 0) {
			return;
		}
		double relative = mouseY - getListTopY() - thumb.height / 2.0;
		int offset = (int) Math.round(relative * maxScroll / available);
		setScrollOffset(Math.max(0, Math.min(maxScroll, offset)));
	}

	/**
	 * 渲染滚动条轨道与滑块。
	 */
	void renderScrollBar(GuiGraphics graphics) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return;
		}
		int listBottom = getListBottomY();
		int scrollX = getScrollBarX();
		graphics.fill(scrollX, getListTopY(),
				scrollX + getScrollBarWidth(), listBottom, GuiColors.SCROLLBAR_TRACK);
		graphics.fill(scrollX, thumb.y,
				scrollX + getScrollBarWidth(), thumb.y + thumb.height, GuiColors.SCROLLBAR_THUMB);
	}

	// ========== 鼠标事件委托 ==========

	/**
	 * 处理鼠标按下：拖拽滑块或点击轨道快速跳转。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) {
			return false;
		}
		if (getTotalCount() <= getVisibleCount()) {
			return false;
		}
		if (!isMouseOverScrollBar(mouseX, mouseY)) {
			return false;
		}
		if (isMouseOverScrollBarThumb(mouseX, mouseY)) {
			isDragging = true;
			onThumbClicked();
		} else {
			updateScrollOffsetFromMouseY(mouseY);
			onScrollChanged();
		}
		return true;
	}

	/**
	 * 处理鼠标拖拽：拖动滑块时更新滚动偏移。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseDragged(double mouseX, double mouseY, int button) {
		if (isDragging && button == 0) {
			updateScrollOffsetFromMouseY(mouseY);
			onScrollChanged();
			return true;
		}
		return false;
	}

	/**
	 * 处理鼠标释放：结束滑块拖拽。
	 *
	 * @return true 表示事件已处理
	 */
	boolean handleMouseReleased(double mouseX, double mouseY, int button) {
		if (isDragging && button == 0) {
			isDragging = false;
			onThumbReleased();
			return true;
		}
		return false;
	}
}
