package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionParser;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionText;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.tooltip.TooltipUtils;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 「放物品 → 滚轮选标签 → 单击加入/移除」的选取器行。
 * <p>
 * 布局：样品槽 + 加号 + 减号 + 连接符切换 + 目标侧切换 + 当前标签文本。
 * 交互取自精妙存储高级虚空升级的加/删标签 UX（仅参考行为，未使用其代码 —— 该仓库为 ARR 协议）：
 * 槽里放物品即列出它的全部标签，滚轮在候选间环形切换，点按钮写入/移出表达式。
 * <p>
 * <b>为什么要「目标侧」按钮</b>：本项目有白名单/黑名单两个表达式。早先用「左键白 / 右键黑」区分，
 * 但补上减号后同一个按钮要承载四种含义，而且候选列表必须先知道目标侧才能剔除已写入项。
 * 显式选侧后每个按钮只有一种含义，候选列表也唯一确定。
 * <p>
 * <b>连接符按钮</b>对应精妙存储的「匹配任意标签 / 匹配所有标签」：{@code |} 追加为「命中任一」，
 * {@code &} 追加为「必须全部命中」。它只影响后续追加，不改写已有表达式。
 * <p>
 * 职责（SRP）：只做交互与渲染；候选计算在 {@link TagPickerState}，
 * 文本增删在 {@link TagExpressionText}，表达式读写经 {@link TagExpressionEditor} 抽象（DIP）。
 */
final class TagPickerWidget extends GuiElement {

	static final int HEIGHT = 18;
	private static final int BTN_SIZE = 16;
	private static final int BTN_GAP = 2;
	private static final int PLUS_X = TagSampleSlotWidget.SIZE + BTN_GAP;
	private static final int MINUS_X = PLUS_X + BTN_SIZE + BTN_GAP;
	private static final int MATCH_X = MINUS_X + BTN_SIZE + BTN_GAP;
	private static final int SIDE_X = MATCH_X + BTN_SIZE + BTN_GAP;
	private static final int TEXT_X = SIDE_X + BTN_SIZE + 4;
	private static final String LANG_PREFIX = "productivebeesgenesis.gui.ae_input_tag_filter.picker.";

	private final TagPickerState state = new TagPickerState();
	private final TagExpressionEditor editor;
	private final int textColor;
	private final PickerButton plusButton;
	private final PickerButton minusButton;
	private final PickerButton matchButton;
	private final PickerButton sideButton;

	/** 目标侧：true = 黑名单表达式。 */
	private boolean blacklistTarget;
	/** 追加时使用的连接符：{@code '|'} 命中任一，{@code '&'} 必须全部。 */
	private char operator = '|';

	private List<Component> addTooltipLines = List.of();
	private List<Component> removeTooltipLines = List.of();
	/** 上次用于重算候选的表达式文本；未变则整轮跳过（tick 每帧调用，见 {@link #refreshCandidates}）。 */
	private String lastExpression;
	/** 上次重算候选时的目标侧，切侧后必须强制重算。 */
	private boolean lastTargetWasBlacklist;

	/**
	 * @param textColor 当前标签文字颜色（由宿主窗口传入，保证与窗口配色一致）
	 * @param editor    表达式读写抽象
	 */
	TagPickerWidget(IGuiWrapper gui, int x, int y, int width, int textColor, TagExpressionEditor editor) {
		super(gui, x, y, width, HEIGHT);
		this.editor = editor;
		this.textColor = textColor;
		addChild(new TagSampleSlotWidget(gui, x, y, this::onSampleChanged));
		plusButton = addChild(new PickerButton(gui, x + PLUS_X, y + 1, Component.literal("+"), this::addSelected));
		minusButton = addChild(new PickerButton(gui, x + MINUS_X, y + 1, Component.literal("-"), this::removeSelected));
		matchButton = addChild(new PickerButton(gui, x + MATCH_X, y + 1, Component.literal("|"), this::toggleOperator));
		sideButton = addChild(new PickerButton(gui, x + SIDE_X, y + 1, Component.literal("W"), this::toggleTarget));
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
		updateTooltips();
	}

	/** 强制重算（样品物品或候选集本身变了，表达式文本可能没变）。 */
	private void forceRefresh() {
		lastExpression = null;
		refreshCandidates();
	}

	private String currentExpression() {
		return editor == null ? "" : editor.getTagExpression(blacklistTarget);
	}

	private boolean addSelected() {
		String literal = state.addList().current();
		if (literal == null || editor == null) return false;
		editor.setTagExpression(blacklistTarget, TagExpressionText.appendLiteral(
				currentExpression(), literal, operator, TagExpressionParser.MAX_EXPRESSION_LENGTH));
		refreshCandidates();
		return true;
	}

	private boolean removeSelected() {
		String literal = state.removeList().current();
		if (literal == null || editor == null) return false;
		editor.setTagExpression(blacklistTarget, TagExpressionText.removeLiteral(currentExpression(), literal));
		refreshCandidates();
		return true;
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

	/** 滚轮：加号上切换可加候选，减号上切换可删候选；其余位置交还父级。 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double xDelta, double yDelta) {
		if (plusButton.isMouseOver(mouseX, mouseY) && state.addList().cycle(yDelta)) {
			updateTooltips();
			return true;
		}
		if (minusButton.isMouseOver(mouseX, mouseY) && state.removeList().cycle(yDelta)) {
			updateTooltips();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, xDelta, yDelta);
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		String current = state.addList().current();
		Component text = current == null
				? Component.translatable(LANG_PREFIX + "empty")
				: Component.literal(current);
		drawScaledScrollingString(guiGraphics, text, TEXT_X, 5, TextAlignment.LEFT,
				textColor, width - TEXT_X, 0, false, 0.7F);
	}

	/**
	 * 重建加号/减号 tooltip（逐条列出候选，当前项高亮）。
	 * <p>
	 * 仅在内容变化时重新创建 {@link Tooltip}：本方法由 tick 与交互驱动，
	 * 每次都新建 Component 列表会持续产生垃圾。
	 */
	private void updateTooltips() {
		List<Component> add = buildLines(true);
		if (!add.equals(addTooltipLines)) {
			addTooltipLines = add;
			plusButton.setTooltip(TooltipUtils.create(add));
		}
		List<Component> remove = buildLines(false);
		if (!remove.equals(removeTooltipLines)) {
			removeTooltipLines = remove;
			minusButton.setTooltip(TooltipUtils.create(remove));
		}
	}

	private List<Component> buildLines(boolean adding) {
		TagCursorList list = adding ? state.addList() : state.removeList();
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable(LANG_PREFIX + (adding ? "title" : "remove_title")));
		if (adding && state.getStack().isEmpty()) {
			lines.add(Component.translatable(LANG_PREFIX + "no_item")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			return List.copyOf(lines);
		}
		if (list.isEmpty()) {
			lines.add(Component.translatable(LANG_PREFIX + (adding ? "no_tags" : "nothing_to_remove"))
					.withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
			return List.copyOf(lines);
		}
		List<String> entries = list.getEntries();
		int cursor = list.getCursor();
		for (int i = 0; i < entries.size(); i++) {
			boolean selected = i == cursor;
			lines.add(Component.literal((selected ? "-> " : "> ") + entries.get(i))
					.withStyle(selected ? (adding ? ChatFormatting.GREEN : ChatFormatting.RED)
							: ChatFormatting.DARK_GRAY));
		}
		lines.add(Component.translatable(LANG_PREFIX + (adding ? "controls" : "remove_controls"))
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		return List.copyOf(lines);
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
