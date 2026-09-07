package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionParser;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionText;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.tooltip.TooltipUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 「放物品 → 在标签列表里单击 → 加入/移出表达式」的选取器面板。
 * <p>
 * 布局：顶行为样品槽 + 连接符切换 + 目标侧切换 + 搜索框，下方为定高标签滚动列表。
 * 交互取自精妙存储高级虚空升级的标签过滤 UX（仅参考行为，未使用其代码 —— 该仓库为 ARR 协议）：
 * 槽里放物品即列出它的全部标签，单击一行切换该标签是否写入表达式，已写入的行带勾选标记。
 * <p>
 * <b>为什么废掉原来的加号/减号 + tooltip 列表</b>：候选原先整表塞进按钮 tooltip，靠滚轮在
 * tooltip 里换行。大型整合包（如 BeebeeBlock）里一个物品挂几十个标签，tooltip 高度直接超过界面
 * 高度，而原版 tooltip 定位器只把它往上顶、不裁剪，结果顶部若干条被推出屏幕外，玩家看不到也
 * 滚不到。改为 {@link TagListWidget} 后可见行数固定，候选再多只是滚动条变长；单击即切换，
 * 加/删共用一行，按钮数量也从四个降到两个。
 * <p>
 * <b>搜索框</b>：几十个标签靠滚轮翻找仍然低效，输入子串即时过滤列表（只影响显示，不改表达式）。
 * <p>
 * <b>连接符按钮</b>对应精妙存储的「匹配任意标签 / 匹配所有标签」：{@code |} 追加为「命中任一」，
 * {@code &} 追加为「必须全部命中」。它只影响后续追加，不改写已有表达式。
 * <p>
 * <b>目标侧按钮</b>：本项目有白名单/黑名单两个表达式，勾选态必须先知道目标侧才能判定，
 * 因此显式选侧而不是用左右键区分。
 * <p>
 * 职责（SRP）：只做交互编排；候选计算在 {@link TagPickerState}，列表渲染在 {@link TagListWidget}，
 * 文本增删在 {@link TagExpressionText}，表达式读写经 {@link TagExpressionEditor} 抽象（DIP）。
 */
final class TagPickerWidget extends GuiElement {

	/** 顶行高度（样品槽 18px 决定）。 */
	private static final int TOP_ROW_HEIGHT = 18;
	/** 顶行与列表之间的间隙。 */
	private static final int ROW_GAP = 2;
	/** 列表可见行数：窗口总高与「一屏能看多少」的折中，候选再多也只是滚动条变长。 */
	private static final int VISIBLE_ROWS = 6;
	/** 面板总高，供宿主窗口推算窗口高度。 */
	static final int HEIGHT = TOP_ROW_HEIGHT + ROW_GAP + TagListWidget.heightFor(VISIBLE_ROWS);

	private static final int BTN_SIZE = 16;
	private static final int BTN_GAP = 2;
	private static final int MATCH_X = TagSampleSlotWidget.SIZE + BTN_GAP;
	private static final int SIDE_X = MATCH_X + BTN_SIZE + BTN_GAP;
	private static final int SEARCH_X = SIDE_X + BTN_SIZE + 4;
	private static final int SEARCH_HEIGHT = 16;
	private static final int SEARCH_MAX_LENGTH = 48;
	private static final String LANG_PREFIX = "productivebeesgenesis.gui.ae_input_tag_filter.picker.";
	private static final String LIST_PREFIX = "productivebeesgenesis.gui.ae_input_tag_filter.list.";

	private final TagPickerState state = new TagPickerState();
	private final TagExpressionEditor editor;
	private final PickerButton matchButton;
	private final PickerButton sideButton;

	/** 目标侧：true = 黑名单表达式。 */
	private boolean blacklistTarget;
	/** 追加时使用的连接符：{@code '|'} 命中任一，{@code '&'} 必须全部。 */
	private char operator = '|';

	/** 上次用于重算候选的表达式文本；未变则整轮跳过（tick 每帧调用，见 {@link #refreshCandidates}）。 */
	private String lastExpression;
	/** 上次重算候选时的目标侧，切侧后勾选态必须重算。 */
	private boolean lastTargetWasBlacklist;

	/**
	 * @param window 承载文本框焦点的宿主窗口（{@link GuiTextField} 需要它派发焦点）
	 * @param editor 表达式读写抽象
	 */
	TagPickerWidget(IGuiWrapper gui, ContainerEventHandler window, int x, int y, int width,
			TagExpressionEditor editor) {
		super(gui, x, y, width, HEIGHT);
		this.editor = editor;
		addChild(new TagSampleSlotWidget(gui, x, y, this::onSampleChanged));
		matchButton = addChild(new PickerButton(gui, x + MATCH_X, y + 1,
				Component.literal("|"), this::toggleOperator));
		sideButton = addChild(new PickerButton(gui, x + SIDE_X, y + 1,
				Component.literal("W"), this::toggleTarget));
		GuiTextField searchField = addChild(new GuiTextField(gui, window,
				x + SEARCH_X, y + 1, width - SEARCH_X, SEARCH_HEIGHT));
		searchField.setMaxLength(SEARCH_MAX_LENGTH);
		searchField.setScale(0.8F);
		searchField.setInputValidator(TagPickerWidget::isSearchCharacter);
		searchField.setResponder(state::setQuery);
		searchField.setTooltip(TooltipUtils.create(List.of(
				Component.translatable(LANG_PREFIX + "search"),
				Component.translatable(LANG_PREFIX + "controls")
						.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))));
		addChild(new TagListWidget(gui, x, y + TOP_ROW_HEIGHT + ROW_GAP, width,
				TagListWidget.heightFor(VISIBLE_ROWS),
				state::getVisibleRows, this::toggleLiteral, this::emptyText));
		refreshLabels();
		refreshCandidates();
	}

	private void onSampleChanged(ItemStack stack) {
		state.setStack(stack);
		forceRefresh();
	}

	/** 每客户端 tick 检查表达式是否被手动编辑；变了才重算候选（tick 是每帧路径，必须便宜）。 */
	@Override
	public void tick() {
		super.tick();
		refreshCandidates();
	}

	private void refreshCandidates() {
		String expression = currentExpression();
		if (blacklistTarget == lastTargetWasBlacklist && expression.equals(lastExpression)) return;
		lastExpression = expression;
		lastTargetWasBlacklist = blacklistTarget;
		state.refresh(expression);
	}

	/** 强制重算（样品物品变了，表达式文本可能没变）。 */
	private void forceRefresh() {
		lastExpression = null;
		refreshCandidates();
	}

	private String currentExpression() {
		return editor == null ? "" : editor.getTagExpression(blacklistTarget);
	}

	/**
	 * 单击某行：已在表达式里则移出，否则按当前连接符追加。
	 * <p>
	 * 追加超长或删除后自愈失败时 {@link TagExpressionText} 会原样返回文本，此处据此跳过写入，
	 * 避免无意义地触发校验与刷新。
	 */
	private void toggleLiteral(String literal) {
		if (literal == null || editor == null) return;
		String current = currentExpression();
		String next = TagExpressionText.containsLiteral(current, literal)
				? TagExpressionText.removeLiteral(current, literal)
				: TagExpressionText.appendLiteral(current, literal, operator,
						TagExpressionParser.MAX_EXPRESSION_LENGTH);
		if (next.equals(current)) return;
		editor.setTagExpression(blacklistTarget, next);
		refreshCandidates();
	}

	private boolean toggleOperator() {
		operator = operator == '|' ? '&' : '|';
		refreshLabels();
		return true;
	}

	private boolean toggleTarget() {
		blacklistTarget = !blacklistTarget;
		refreshLabels();
		refreshCandidates();
		return true;
	}

	private void refreshLabels() {
		matchButton.setMessage(Component.literal(String.valueOf(operator)));
		sideButton.setMessage(Component.literal(blacklistTarget ? "B" : "W"));
		matchButton.setTooltip(TooltipUtils.create(Component.translatable(
				operator == '|' ? LANG_PREFIX + "match_any" : LANG_PREFIX + "match_all")));
		sideButton.setTooltip(TooltipUtils.create(Component.translatable(
				blacklistTarget ? LANG_PREFIX + "side_black" : LANG_PREFIX + "side_white")));
	}

	/** 列表为空时的说明：区分「没放物品」「物品没标签」「搜索无结果」。 */
	private Component emptyText() {
		if (state.hasQuery() && !state.getRows().isEmpty()) {
			return Component.translatable(LIST_PREFIX + "no_match");
		}
		if (state.getStack().isEmpty()) {
			return Component.translatable(LIST_PREFIX + "no_item");
		}
		return Component.translatable(LIST_PREFIX + "no_tags");
	}

	/** 搜索框只接受标签/物品 id 的组成字符，避免输入运算符造成误解。 */
	private static boolean isSearchCharacter(char character) {
		if (Character.isLetterOrDigit(character)) return true;
		return switch (character) {
			case ':', '/', '_', '-', '.' -> true;
			default -> false;
		};
	}

	/**
	 * 把焦点状态同步给当前聚焦的子控件 —— MEK 的焦点转移只发生在同级之间。
	 * <p>
	 * 窗口把焦点交给别的输入框时，只会调用本面板的 {@code setFocused(false)}，不会递归到孙控件。
	 * 若不在这里同步撕掉搜索框的焦点，搜索框会和窗口级表达式框同时处于聚焦态：两个光标同时闪，
	 * 键盘输入被排在前面的表达式框吞掉。收到 {@code true} 时同样要还给子控件 ——
	 * 父级重新聚焦本面板走的是「先 false 再 true」，只清不还会把刚点中的搜索框误清掉。
	 */
	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		GuiElement child = getFocused();
		if (child != null) child.setFocused(focused);
	}

	/** 面板自身不画文字：顶行控件与列表各自渲染。 */
	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		// 交由子控件渲染
	}

	/** 选取器按钮：单一左键动作，文字深色无阴影（与窗口其他按钮一致）。 */
	private static final class PickerButton extends MekanismButton {

		PickerButton(IGuiWrapper gui, int x, int y, Component message, BooleanSupplier action) {
			super(gui, x, y, BTN_SIZE, BTN_SIZE, message, (element, mouseX, mouseY) -> action.getAsBoolean());
			setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		}

		@Override
		protected int getButtonTextColor(int mouseX, int mouseY) {
			return 0x232323;
		}

		@Override
		protected boolean displayButtonTextShadow() {
			return false;
		}
	}
}
