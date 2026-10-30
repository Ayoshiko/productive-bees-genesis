package com.ayoshiko.productivebeesgenesis.client.screen;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.components.Button;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.List;

/**
	 * FilterListScreen 的条目拖拽排序处理器，兼承 {@link AbstractVerticalScrollBar} 复用滚动条逻辑。
	 * <p>
	 * 滚动条几何计算、渲染与鼠标交互由基类统一处理；本类仅保留条目拖拽排序逻辑
	 * （startDrag/finishDrag/rebuildEntryButtonsOnly）与滚动变化时的按钮重建钩子。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP — 拖拽排序与滚动条交互职责清晰分离：滚动条由基类负责，条目拖拽由本类负责</li>
	 *   <li>OCP — 通过重写 {@link #handleMouseDragged}/{@link #handleMouseReleased} 扩展条目拖拽，
	 *       不修改基类滚动条逻辑</li>
	 *   <li>组合模式 — 持有 {@link FilterListScreen} 引用，通过包级访问共享必要状态</li>
	 * </ul>
	 * <br/>
	 * 线程安全：客户端 GUI 单线程访问，无需同步。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListDragHandler extends AbstractVerticalScrollBar {

	private final FilterListScreen screen;

	/** 拖拽排序状态：源条目索引 */
	private int dragSourceIndex = -1;
	/** 拖拽排序状态：目标插入位置索引 */
	private int dragInsertIndex = -1;
	/** 是否正在拖拽条目进行排序（与基类 isDragging 滚动条拖拽标志相互独立） */
	private boolean isDraggingEntry = false;

	FilterListDragHandler(FilterListScreen screen) {
		this.screen = screen;
	}

	// ========== 抽象方法实现：向基类提供屏幕数据 ==========

	@Override
	protected int getScreenX() {
		return screen.width;
	}

	@Override
	protected int getScreenY() {
		return screen.height;
	}

	@Override
	protected int getScrollBarWidth() {
		return FilterListScreen.SCROLL_BAR_WIDTH;
	}

	@Override
	protected int getScrollBarMargin() {
		return FilterListScreen.SCREEN_MARGIN;
	}

	@Override
	protected int getListTopY() {
		return FilterListScreen.LIST_TOP_Y;
	}

	@Override
	protected int getListHeight() {
		return FilterListScreen.LIST_BOTTOM_MARGIN;
	}

	@Override
	protected int getTotalCount() {
		return screen.beeTypes.size();
	}

	@Override
	protected int getVisibleCount() {
		return screen.getVisibleEntryCount();
	}

	@Override
	protected int getMinThumbHeight() {
		return 16;
	}

	@Override
	protected int getScrollOffset() {
		return screen.scrollOffset;
	}

	@Override
	protected void setScrollOffset(int offset) {
		screen.scrollOffset = offset;
	}

	// ========== 钩子方法：滚动偏移变化/滑块释放时重建删除按钮 ==========
	//
	// Task 11 修复核心：滚动时 scrollOffset 变化，可见条目集合随之改变，
	// 必须同步重建删除按钮（回调捕获了条目索引），否则按钮位置与条目错位。

	@Override
	protected void onScrollChanged() {
		rebuildEntryButtonsOnly();
	}

	@Override
	protected void onThumbReleased() {
		rebuildEntryButtonsOnly();
	}

	// ========== 条目删除按钮重建 ==========

	/**
	 * 仅重建条目删除按钮，避免滚动时全量 {@code rebuildWidgets}。
	 * <p>
	 * 性能优化：滚动是高频操作，全量 rebuildWidgets 会销毁并重建搜索框、模式按钮、
	 * 输入框等与滚动无关的组件。此方法仅移除旧的删除按钮并创建新的，
	 * 将滚动开销从 O(全部组件) 降低到 O(可见条目数)。
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
		this.isDraggingEntry = true;
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
		isDraggingEntry = false;
		dragSourceIndex = -1;
		dragInsertIndex = -1;
		// rebuildWidgets 为 Screen 的 protected 方法，通过包级桥接方法转发
		screen.rebuildWidgetsBridge();
	}

	// ========== 鼠标事件委托（重写：先处理滚动条，再处理条目拖拽） ==========

	/**
	 * 处理鼠标拖拽事件：先委托基类处理滚动条滑块拖拽，否则处理条目拖拽排序。
	 *
	 * @return true 表示事件已处理
	 */
	@Override
	boolean handleMouseDragged(double mouseX, double mouseY, int button) {
		if (super.handleMouseDragged(mouseX, mouseY, button)) {
			return true;
		}
		if (isDraggingEntry && button == 0) {
			dragInsertIndex = screen.renderer.getInsertionIndex(mouseY, screen.beeTypes, screen.scrollOffset);
			return true;
		}
		return false;
	}

	/**
	 * 处理鼠标释放事件：先委托基类结束滚动条拖拽，否则结束条目拖拽。
	 *
	 * @return true 表示事件已处理
	 */
	@Override
	boolean handleMouseReleased(double mouseX, double mouseY, int button) {
		if (super.handleMouseReleased(mouseX, mouseY, button)) {
			return true;
		}
		if (isDraggingEntry && button == 0) {
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
}
