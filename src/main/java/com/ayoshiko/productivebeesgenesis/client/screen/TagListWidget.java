package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.scroll.GuiScrollList;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 标签候选滚动列表 —— 定高列表 + 滚动条，单击一行即把该字面量加入/移出当前目标侧表达式。
 * <p>
 * <b>为什么必须是滚动列表，而不是把候选塞进 tooltip</b>：原实现把全部候选逐条塞进加号/减号按钮的
 * tooltip，而原版 {@code DefaultTooltipPositioner} 只会把过高的 tooltip 往上顶
 * （{@code y = guiHeight - height}），从不裁剪高度。大型整合包里一个矿物方块能挂几十个标签，
 * tooltip 高度直接超过界面高度，顶部若干条被顶到屏幕外 —— 玩家既看不到也没有任何手段滚到。
 * 定高列表把「可见行数」与「候选总数」解耦：高度恒为 N 行，候选再多只是滚动条变长。
 * <p>
 * 复用 MEK 的 {@link GuiScrollList}（与离心机界面同一套内屏底图与滚动条贴图），
 * 省掉自绘轨道/滑块与命中测试，也保证配色风格一致。
 * <p>
 * 职责（SRP）：只做渲染与命中测试；候选数据由 {@code rows} 供给、写表达式由 {@code onToggle}
 * 回调完成（DIP：本类不认识 {@code TagExpressionText}，也不知道当前目标是白名单还是黑名单）。
 */
final class TagListWidget extends GuiScrollList {

	/** 单行高度（与 MEK 文本滚动列表一致，10px 恰好容纳 0.7 缩放的标签 id）。 */
	static final int ROW_HEIGHT = 10;
	/** 已加入项前缀，视觉上等价于精妙存储标签过滤里的勾选框。 */
	private static final String CHECKED_PREFIX = "\u2714 ";
	private static final String UNCHECKED_PREFIX = "  ";
	private static final float TEXT_SCALE = 0.7F;

	private final Supplier<List<TagPickerState.Row>> rows;
	private final Consumer<String> onToggle;
	private final Supplier<Component> emptyText;

	/** 列表控件高度：可见行数 × 行高 + 上下各 1px 边框。 */
	static int heightFor(int visibleRows) {
		return visibleRows * ROW_HEIGHT + 2;
	}

	/**
	 * @param rows      候选行供给者（每帧读取，调用方负责保证是缓存好的列表而非现算）
	 * @param onToggle  单击某行时的回调，入参为该行字面量
	 * @param emptyText 无候选行时显示的说明文本（区分「没放物品」「没标签」「搜索无结果」）
	 */
	TagListWidget(IGuiWrapper gui, int x, int y, int width, int height,
			Supplier<List<TagPickerState.Row>> rows, Consumer<String> onToggle, Supplier<Component> emptyText) {
		super(gui, x, y, width, height, ROW_HEIGHT, GuiInnerScreen.SCREEN, GuiInnerScreen.SCREEN_SIZE);
		this.rows = rows;
		this.onToggle = onToggle;
		this.emptyText = emptyText;
	}

	@Override
	protected int getMaxElements() {
		return rows.get().size();
	}

	/** 本列表不保留选中态：勾选态来自表达式本身，单击即写表达式。 */
	@Override
	public boolean hasSelection() {
		return false;
	}

	@Override
	public void clearSelection() {
		// 无选中态可清
	}

	/**
	 * {@link GuiScrollList#onClick} 的命中行回调 —— 借用父类已经做好的命中测试
	 * （含排除滚动条列、按滚动偏移换算下标），语义是「点了第 index 行」而非「选中第 index 行」。
	 */
	@Override
	protected void setSelected(int index) {
		List<TagPickerState.Row> current = rows.get();
		if (index < 0 || index >= current.size()) return;
		onToggle.accept(current.get(index).literal());
	}

	/** 底图阶段：画已加入项底色与悬停高亮（坐标同 MEK，相对 GUI 左上角）。 */
	@Override
	protected void renderElements(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		List<TagPickerState.Row> current = rows.get();
		if (current.isEmpty()) return;
		int scrollIndex = getCurrentSelection();
		int focused = getFocusedElements();
		int hovered = hoveredRow(mouseX, mouseY);
		for (int i = 0; i < focused; i++) {
			int index = scrollIndex + i;
			if (index >= current.size()) break;
			int color = backgroundFor(current.get(index).inExpression(), hovered == i);
			if (color == 0) continue;
			int top = relativeY + 1 + i * ROW_HEIGHT;
			guiGraphics.fill(relativeX + 1, top, relativeX + barXShift - 1, top + ROW_HEIGHT, color);
		}
	}

	private static int backgroundFor(boolean inExpression, boolean hovered) {
		if (inExpression) {
			return hovered ? GuiColors.OVERLAY_ENTRY_ADDED_HOVER_BG : GuiColors.OVERLAY_ENTRY_ADDED_BG;
		}
		return hovered ? GuiColors.OVERLAY_HOVER_ROW : 0;
	}

	/** 前景阶段：画行文字（坐标相对本控件左上角，与 MEK 文本滚动列表一致）。 */
	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		List<TagPickerState.Row> current = rows.get();
		if (current.isEmpty()) {
			drawScaledScrollingString(guiGraphics, emptyText.get(), 0, 2, TextAlignment.CENTER,
					GuiColors.TEXT_DARK_GRAY, barXShift, 2, false, TEXT_SCALE);
			return;
		}
		int scrollIndex = getCurrentSelection();
		int focused = getFocusedElements();
		for (int i = 0; i < focused; i++) {
			int index = scrollIndex + i;
			if (index >= current.size()) break;
			TagPickerState.Row row = current.get(index);
			Component text = Component.literal(
					(row.inExpression() ? CHECKED_PREFIX : UNCHECKED_PREFIX) + row.literal());
			drawScaledScrollingString(guiGraphics, text, 0, 2 + ROW_HEIGHT * i, TextAlignment.LEFT,
					row.inExpression() ? GuiColors.TEXT_NAME_ADDED_GREEN : GuiColors.TEXT_LIGHT_GRAY,
					barXShift, 2, false, TEXT_SCALE);
		}
	}

	/** 鼠标所在的可见行序号（不是候选下标）；不在行区域返回 -1。 */
	private int hoveredRow(double mouseX, double mouseY) {
		if (mouseX < getX() + 1 || mouseX >= getX() + barXShift - 1) return -1;
		if (mouseY < getY() + 1 || mouseY >= getBottom() - 1) return -1;
		int row = (int) ((mouseY - getY() - 1) / ROW_HEIGHT);
		return row >= 0 && row < getFocusedElements() ? row : -1;
	}
}
