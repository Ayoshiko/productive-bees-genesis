package com.ayoshiko.productivebeesgenesis.client.screen;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

/**
 * BeeSelectionScreen 的滚动条交互与渲染辅助类
 * <p>
 * 继承 {@link AbstractVerticalScrollBar}，仅提供数据源（屏幕尺寸、列表状态）与
 * 滚动变化钩子，滚动条几何计算、渲染与鼠标交互由基类统一处理（SRP/OCP）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责向基类提供 BeeSelectionScreen 的布局与状态数据</li>
 *   <li>组合模式 — 持有 {@link BeeSelectionScreen}、{@link BeeSelectionState} 与
 *       {@link BeeSelectionSorter} 引用，通过包级访问共享必要状态</li>
 * </ul>
 * <br/>
 * 线程安全：客户端 GUI 单线程访问，无需同步。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class BeeSelectionScrollBar extends AbstractVerticalScrollBar {

	private final BeeSelectionScreen screen;
	private final BeeSelectionState state;
	private final BeeSelectionSorter sorter;

	BeeSelectionScrollBar(BeeSelectionScreen screen, BeeSelectionState state, BeeSelectionSorter sorter) {
		this.screen = screen;
		this.state = state;
		this.sorter = sorter;
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
		return BeeSelectionScreen.SCROLL_BAR_WIDTH;
	}

	@Override
	protected int getScrollBarMargin() {
		return BeeSelectionScreen.SIDE_PADDING;
	}

	@Override
	protected int getListTopY() {
		return BeeSelectionScreen.LIST_TOP_Y;
	}

	@Override
	protected int getListHeight() {
		return BeeSelectionScreen.LIST_BOTTOM_MARGIN;
	}

	@Override
	protected int getTotalCount() {
		return sorter.getDisplayItems().size();
	}

	@Override
	protected int getVisibleCount() {
		return screen.getVisibleEntryCount();
	}

	@Override
	protected int getMinThumbHeight() {
		return 20;
	}

	@Override
	protected int getScrollOffset() {
		return state.getScrollOffset();
	}

	@Override
	protected void setScrollOffset(int offset) {
		state.setScrollOffset(offset);
	}

	// ========== 钩子方法：滚动偏移变化时更新回到顶部按钮状态 ==========

	@Override
	protected void onScrollChanged() {
		screen.updateScrollToTopButton();
	}
}
