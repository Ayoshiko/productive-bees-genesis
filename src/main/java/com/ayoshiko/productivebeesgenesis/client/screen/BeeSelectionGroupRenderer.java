package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 蜜蜂选择屏幕的分组折叠与显示切换管理器
 * <p>
 * 将分组折叠状态切换、"仅显示未添加"过滤切换及分组标题点击检测等逻辑从屏幕类中剥离，
 * 使 BeeSelectionScreen 专注于事件调度与整体布局。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责分组折叠协调与显示切换逻辑</li>
 *   <li>组合模式 — 持有 {@link BeeSelectionState}、{@link BeeSelectionSorter}
 *       与 {@link BeeSelectionRenderer} 引用</li>
 * </ul>
 * <br/>
 * 线程安全：客户端 GUI 单线程访问。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class BeeSelectionGroupRenderer {

	private final BeeSelectionState state;
	private final BeeSelectionSorter sorter;
	private final BeeSelectionRenderer renderer;

	/** 显示全部 / 仅未添加 切换按钮 */
	private Button toggleButton;

	BeeSelectionGroupRenderer(BeeSelectionState state, BeeSelectionSorter sorter, BeeSelectionRenderer renderer) {
		this.state = state;
		this.sorter = sorter;
		this.renderer = renderer;
	}

	/**
	 * 切换指定分组的折叠状态
	 * <p>
	 * 委托状态层切换折叠标记，再通知排序器使 displayItems 缓存失效并重建，
	 * 最后修正滚动偏移以避免越界。
	 *
	 * @param namespace 分组对应的 namespace（模组ID）
	 */
	void toggleGroupCollapsed(String namespace) {
		state.toggleGroupCollapsed(namespace);
		sorter.onCollapsedChanged();
	}

	/**
	 * 切换"仅显示未添加"状态，并刷新列表和按钮文本
	 */
	void toggleShowOnlyUnadded() {
		state.toggleShowOnlyUnadded();
		if (toggleButton != null) {
			toggleButton.setMessage(getToggleMessage());
		}
		state.resetScroll();
		sorter.recomputeFilteredEntries();
	}

	/** 获取切换按钮的当前文本 */
	private Component getToggleMessage() {
		return Component.translatable(state.isShowOnlyUnadded()
				? "productivebeesgenesis.config.show_unadded"
				: "productivebeesgenesis.config.show_all");
	}

	/**
	 * 创建"仅显示未添加"切换按钮
	 *
	 * @param x        按钮左侧 X 坐标
	 * @param y        按钮顶部 Y 坐标
	 * @param maxWidth 按钮最大可用宽度
	 * @return 创建好的按钮实例（由调用方添加到 renderable 列表）
	 */
	Button createToggleButton(int x, int y, int maxWidth) {
		int toggleWidth = Math.min(BeeSelectionScreen.TOGGLE_BUTTON_WIDTH, maxWidth);
		toggleButton = Button.builder(
				getToggleMessage(),
				button -> toggleShowOnlyUnadded()
		).bounds(x, y, Math.max(50, toggleWidth), BeeSelectionScreen.TOP_BUTTON_HEIGHT).build();
		return toggleButton;
	}

	/**
	 * 处理分组标题点击事件
	 * <p>
	 * 检测鼠标 Y 坐标是否命中分组标题行，若命中则切换该分组的折叠状态。
	 *
	 * @param mouseY       鼠标 Y 坐标
	 * @param displayItems 当前显示列表
	 * @param scrollOffset 当前滚动偏移
	 * @return 是否已处理（命中分组标题）
	 */
	boolean handleHeaderClick(double mouseY, List<BeeSelectionRenderer.DisplayItem> displayItems, int scrollOffset) {
		Integer headerIndex = renderer.getHeaderIndexAt(mouseY, displayItems, scrollOffset);
		if (headerIndex != null) {
			BeeSelectionRenderer.DisplayItem item = displayItems.get(headerIndex);
			if (item instanceof BeeSelectionRenderer.HeaderItem header) {
				toggleGroupCollapsed(header.namespace);
			}
			return true;
		}
		return false;
	}
}
