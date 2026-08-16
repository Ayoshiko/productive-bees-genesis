package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import javax.annotation.ParametersAreNonnullByDefault;

/**
	 * 蜜蜂选择屏幕的搜索框与排序按钮管理器
	 * <p>
	 * 将搜索框的布局计算、创建配置、搜索文本状态管理及实时过滤回调从屏幕类中剥离，
	 * 同时管理与之位置关联的排序按钮，使 BeeSelectionScreen 专注于事件调度与整体布局。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP — 仅负责搜索框与排序按钮的创建、状态及交互逻辑</li>
	 *   <li>组合模式 — 持有 {@link BeeSelectionScreen}、{@link BeeSelectionState} 与
	 *       {@link BeeSelectionSorter} 引用，通过包级访问共享必要状态</li>
	 * </ul>
	 * <br/>
	 * 线程安全：客户端 GUI 单线程访问。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class BeeSelectionSearchBar {

	private final BeeSelectionScreen screen;
	private final BeeSelectionState state;
	private final BeeSelectionSorter sorter;

	/** 搜索框组件 */
	private EditBox searchField;
	/** 排序按钮 */
	private Button sortButton;
	/** 首次设置搜索框值时避免触发滚动重置 */
	private boolean ignoreNextSearchReset = false;

	/** 计算后的搜索框左侧 X 坐标（供排序按钮定位使用） */
	private int searchX;
	/** 计算后的搜索框右侧 X 坐标（供右侧按钮定位使用） */
	private int searchRight;

	BeeSelectionSearchBar(BeeSelectionScreen screen, BeeSelectionState state, BeeSelectionSorter sorter) {
		this.screen = screen;
		this.state = state;
		this.sorter = sorter;
	}

	/**
	 * 计算搜索框布局并创建搜索框与排序按钮
	 * <p>
	 * 顶部布局：排序按钮在搜索框左侧，全选/反选/切换在右侧。
	 * 当右侧空间不足时，自动缩小搜索框宽度以避免按钮重叠。
	 *
	 * @param screenWidth 屏幕宽度
	 */
	void init(int screenWidth) {
		// 计算搜索框位置与宽度，右侧空间不足时自动缩小
		int desiredSearchWidth = BeeSelectionScreen.SEARCH_WIDTH;
		searchX = screenWidth / 2 - desiredSearchWidth / 2;
		searchRight = searchX + desiredSearchWidth;
		int rightArea = screenWidth - searchRight - BeeSelectionScreen.SIDE_PADDING;
		int requiredRight = BeeSelectionScreen.SELECT_BUTTON_WIDTH * 2
				+ BeeSelectionScreen.TOGGLE_BUTTON_WIDTH + BeeSelectionScreen.TOP_BUTTON_GAP * 2;
		int actualSearchWidth = desiredSearchWidth;
		if (rightArea < requiredRight) {
			actualSearchWidth = Math.max(140, desiredSearchWidth - (requiredRight - rightArea));
			searchX = screenWidth / 2 - actualSearchWidth / 2;
			searchRight = searchX + actualSearchWidth;
		}

		// 创建搜索框 — 顶部居中
		searchField = new EditBox(screen.getMinecraft().font, searchX, BeeSelectionScreen.TOP_ROW_Y,
				actualSearchWidth, BeeSelectionScreen.TOP_BUTTON_HEIGHT,
				Component.translatable("productivebeesgenesis.config.search"));
		searchField.setMaxLength(64);
		searchField.setHint(Component.translatable("productivebeesgenesis.config.search_hint"));
		searchField.setResponder(this::onSearchChanged);
		// 恢复之前的搜索文本；init 期间禁止滚动重置
		ignoreNextSearchReset = true;
		searchField.setValue(state.getSearchText());
		ignoreNextSearchReset = false;

		// 创建排序按钮 — 搜索框左侧
		sortButton = Button.builder(
				Component.translatable(sorter.getSortKey()),
				button -> cycleSortMode()
		).bounds(searchX - BeeSelectionScreen.SORT_BUTTON_WIDTH - BeeSelectionScreen.TOP_BUTTON_GAP,
				BeeSelectionScreen.TOP_ROW_Y,
				BeeSelectionScreen.SORT_BUTTON_WIDTH,
				BeeSelectionScreen.TOP_BUTTON_HEIGHT).build();
	}

	/**
	 * 搜索框内容变化回调
	 * <p>
	 * 大小写不敏感匹配类型ID、显示名称或最终产物模组ID。
	 *
	 * @param text 输入文本
	 */
	private void onSearchChanged(String text) {
		state.setSearchText(text);
		if (!ignoreNextSearchReset) {
			state.resetScroll();
		}
		sorter.recomputeFilteredEntries();
	}

	/**
	 * 循环切换排序规则，更新按钮文本并刷新列表
	 */
	void cycleSortMode() {
		state.cycleSortMode();
		if (sortButton != null) {
			sortButton.setMessage(Component.translatable(sorter.getSortKey()));
		}
		state.resetScroll();
		sorter.recomputeFilteredEntries();
	}

	/** 获取搜索框组件（供屏幕添加到 renderable 列表与设置焦点） */
	EditBox getSearchField() {
		return searchField;
	}

	/** 获取排序按钮组件（供屏幕添加到 renderable 列表） */
	Button getSortButton() {
		return sortButton;
	}

	/** 获取搜索框右侧 X 坐标（供右侧按钮定位使用） */
	int getSearchRight() {
		return searchRight;
	}
}
