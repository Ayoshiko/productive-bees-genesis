package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2TagFilter;
import com.ayoshiko.productivebeesgenesis.network.SetAeInputTagFilterPayload;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionParser;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * AE2 输入标签过滤表达式编辑窗口（白名单 / 黑名单表达式 + 运算符图例 + 标签选取器 + 保存）。
 * <p>
 * 布局参考 ExtendedAE 的「ME 标签输出总线」：运算符图例直接写在输入框下方的窗口正文里，
 * 而不是靠悬停 tooltip —— 图例是常读信息，悬浮层会遮住输入框，正文常驻更符合直觉。
 * <p>
 * 标签选取器参考精妙存储高级虚空升级的标签过滤交互：放物品、单击标签行加入/移出表达式。
 * 仅参考行为，未使用其代码（该仓库为 ARR 协议）。
 * <p>
 * <b>窗口高度必须留在一屏之内</b>：{@link #WINDOW_HEIGHT} 由各段内容推出而不是写死，且被刻意
 * 压在 210px 以内 —— 原版界面缩放最小可用高度是 240px，本窗口开在父窗口下方 4px 处，
 * 再高就会把保存按钮顶到屏幕外，重演「标签列表超出屏幕」那类问题。
 * <p>
 * 文字统一用 {@link #titleTextColor()}：窗口底色偏白，MEK 的 {@code screenTextColor()}
 * 是为深色内屏设计的亮绿色，压在白底上可读性差。
 * <p>
 * <b>为什么 interactionStrategy 用 CONTAINER 而不是 NONE</b>：NONE 会让
 * {@link GuiWindow#mouseClicked} 无条件返回 true，吞掉窗口外的所有点击 ——
 * 玩家因此无法从背包里拿起物品，样品槽就只能靠 JEI 拖拽填充。CONTAINER 放行
 * 玩家物品栏区域的点击（MEK 默认策略），拿起物品后即可点入样品槽。
 */
final class GuiAeInputTagFilterConfig extends GuiWindow implements TagExpressionEditor {

	private static final int WINDOW_WIDTH = 230;
	private static final int FIELD_X = 10;
	private static final int FIELD_WIDTH = WINDOW_WIDTH - 2 * FIELD_X;
	private static final int FIELD_HEIGHT = 18;
	private static final int LABEL_OFFSET = 10;
	private static final int WHITELIST_Y = 26;
	private static final int BLACKLIST_Y = 56;
	/** 图例两行（对齐 ExtendedAE 的正文式排布）。 */
	private static final int LEGEND_Y = 76;
	private static final int LEGEND_LINE_HEIGHT = 9;
	private static final int PICKER_Y = 94;
	private static final int SAVE_WIDTH = 38;
	private static final int SAVE_HEIGHT = 18;
	private static final int BOTTOM_PADDING = 6;
	/** 窗口高度由内容推出：选取器面板底 + 间隙 + 保存按钮 + 下边距。 */
	private static final int WINDOW_HEIGHT = PICKER_Y + TagPickerWidget.HEIGHT + 4
			+ SAVE_HEIGHT + BOTTOM_PADDING;
	private static final int NORMAL_TEXT_COLOR = 0xFFFFFF;
	private static final int ERROR_TEXT_COLOR = 0xFF5555;

	private final BlockPos pos;
	private final Ae2TagFilter tagFilter;
	private final GuiTextField whitelistField;
	private final GuiTextField blacklistField;

	GuiAeInputTagFilterConfig(IGuiWrapper gui, int x, int y, BlockPos pos, Ae2TagFilter tagFilter) {
		super(gui, x, y, WINDOW_WIDTH, WINDOW_HEIGHT, new SelectedWindowData(WindowType.UNSPECIFIED));
		this.pos = pos;
		this.tagFilter = tagFilter;
		// CONTAINER：放行玩家物品栏点击，样品槽才能接收背包里的物品（见类注释）
		this.interactionStrategy = InteractionStrategy.CONTAINER;

		whitelistField = addChild(new GuiTextField(gui(), this,
				relativeX + FIELD_X, relativeY + WHITELIST_Y, FIELD_WIDTH, FIELD_HEIGHT));
		blacklistField = addChild(new GuiTextField(gui(), this,
				relativeX + FIELD_X, relativeY + BLACKLIST_Y, FIELD_WIDTH, FIELD_HEIGHT));
		configureField(whitelistField, tagFilter == null ? "" : tagFilter.getWhitelistSource());
		configureField(blacklistField, tagFilter == null ? "" : tagFilter.getBlacklistSource());

		addChild(new TagPickerWidget(gui(), this, relativeX + FIELD_X, relativeY + PICKER_Y,
				FIELD_WIDTH, this));
		addChild(new SaveButton(gui(), relativeX + WINDOW_WIDTH - FIELD_X - SAVE_WIDTH,
				relativeY + WINDOW_HEIGHT - BOTTOM_PADDING - SAVE_HEIGHT,
				Component.translatable("productivebeesgenesis.gui.ae_input_tag_filter.save"), this::save));
		validate(whitelistField);
		validate(blacklistField);
	}

	private void configureField(GuiTextField field, String initial) {
		field.setMaxLength(TagExpressionParser.MAX_EXPRESSION_LENGTH);
		field.setInputValidator(GuiAeInputTagFilterConfig::isExpressionCharacter);
		field.setText(initial == null ? "" : initial);
		field.setResponder(ignored -> validate(field));
		field.setEnterHandler(this::save);
	}

	@Override
	public String getTagExpression(boolean blacklist) {
		String text = (blacklist ? blacklistField : whitelistField).getText();
		return text == null ? "" : text;
	}

	@Override
	public void setTagExpression(boolean blacklist, String expression) {
		GuiTextField target = blacklist ? blacklistField : whitelistField;
		if (expression == null || expression.equals(target.getText())) return;
		// setText 会触发 responder，语法校验与配色随之刷新
		target.setText(expression);
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawTitleText(guiGraphics,
				Component.translatable("productivebeesgenesis.gui.ae_input_tag_filter.title"), 5);
		drawLabel(guiGraphics, "productivebeesgenesis.gui.ae_input_tag_filter.whitelist",
				WHITELIST_Y - LABEL_OFFSET, 0.75F);
		drawLabel(guiGraphics, "productivebeesgenesis.gui.ae_input_tag_filter.blacklist",
				BLACKLIST_Y - LABEL_OFFSET, 0.75F);
		// 图例常驻正文（ExtendedAE 范式），不再用 tooltip 遮挡输入框
		drawLabel(guiGraphics, "productivebeesgenesis.gui.ae_input_tag_filter.legend.line1", LEGEND_Y, 0.7F);
		drawLabel(guiGraphics, "productivebeesgenesis.gui.ae_input_tag_filter.legend.line2",
				LEGEND_Y + LEGEND_LINE_HEIGHT, 0.7F);
	}

	private void drawLabel(GuiGraphics guiGraphics, String key, int y, float scale) {
		drawScaledScrollingString(guiGraphics, Component.translatable(key), FIELD_X, y,
				TextAlignment.LEFT, titleTextColor(), FIELD_WIDTH, 0, false, scale);
	}

	/** 客户端即时语法校验：错误时红字 + 具体原因 tooltip；正常时清除 tooltip（图例已在正文）。 */
	private void validate(GuiTextField field) {
		TagExpressionParser.Result result = TagExpressionParser.parse(field.getText());
		if (!result.isError()) {
			field.setTextColor(NORMAL_TEXT_COLOR);
			field.setTooltip((Tooltip) null);
			return;
		}
		field.setTextColor(ERROR_TEXT_COLOR);
		field.setTooltip(Tooltip.create(Component.translatable(
				"productivebeesgenesis.gui.ae_input_tag_filter.error." + result.errorKey())));
	}

	private void save() {
		PacketDistributor.sendToServer(new SetAeInputTagFilterPayload(
				pos, whitelistField.getText(), blacklistField.getText()));
		// 本地先行应用，避免等待服务端回包造成的一拍闪烁；服务端回包会覆盖为归一化文本
		if (tagFilter != null) tagFilter.apply(whitelistField.getText(), blacklistField.getText());
		close();
	}

	/** 允许标签 id 字符与表达式运算符；拒绝其他字符从源头减少语法错误。 */
	private static boolean isExpressionCharacter(char character) {
		if (Character.isLetterOrDigit(character)) return true;
		return switch (character) {
			case ':', '/', '_', '-', '.', '*', '&', '|', '^', '!', '(', ')', ' ' -> true;
			default -> false;
		};
	}

	private static final class SaveButton extends MekanismButton {
		SaveButton(IGuiWrapper gui, int x, int y, Component message, Runnable callback) {
			super(gui, x, y, SAVE_WIDTH, SAVE_HEIGHT, message, (element, mouseX, mouseY) -> {
				callback.run();
				return true;
			});
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
