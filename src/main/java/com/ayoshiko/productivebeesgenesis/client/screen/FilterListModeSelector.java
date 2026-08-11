package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import javax.annotation.ParametersAreNonnullByDefault;

/**
	 * FilterListScreen 的过滤模式选择器
	 * <p>
	 * 将过滤模式图标按钮的创建、图标映射、模式标签渲染及激活按钮高亮等
	 * 显示逻辑从屏幕类中剥离，降低 FilterListScreen 的复杂度（SRP）。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP — 仅负责模式按钮的创建与渲染，不涉及配置读写或列表数据</li>
	 *   <li>组合模式 — 持有 {@link FilterListScreen} 引用，通过包级访问共享必要状态</li>
	 * </ul>
	 * <br/>
	 * 线程安全：客户端 GUI 单线程访问，无需同步。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListModeSelector {

	private final FilterListScreen screen;
	private final Button[] modeButtons = new Button[ModConfig.FilterMode.values().length];

	FilterListModeSelector(FilterListScreen screen) {
		this.screen = screen;
	}

	/**
	 * 创建过滤模式图标按钮组（DISABLED/WHITELIST/BLACKLIST）。
	 *
	 * @param y 按钮 Y 坐标
	 */
	void createModeButtons(int y) {
		int modeButtonSize = 20;
		int modeButtonGap = 2;
		int totalModeWidth = modeButtonSize * ModConfig.FilterMode.values().length
				+ modeButtonGap * (ModConfig.FilterMode.values().length - 1);
		int firstModeX = screen.width - FilterListScreen.SCREEN_MARGIN - totalModeWidth;

		// 模式标签绘制在 renderModeLabel() 中完成，这里仅创建并排列图标按钮
		for (ModConfig.FilterMode mode : ModConfig.FilterMode.values()) {
			int bx = firstModeX + mode.ordinal() * (modeButtonSize + modeButtonGap);
			Button btn = Button.builder(
					Component.literal(getModeIcon(mode)),
					button -> screen.filterMode = mode
			).bounds(bx, y, modeButtonSize, modeButtonSize)
					.tooltip(Tooltip.create(Component.translatable(
							"productivebeesgenesis.config.filter_mode." + mode.name().toLowerCase() + ".tooltip")))
					.build();
			modeButtons[mode.ordinal()] = btn;
			screen.addRenderableWidgetBridge(btn);
		}
	}

	/**
	 * 渲染过滤模式标签（模式按钮左侧）。
	 */
	void renderModeLabel(GuiGraphics graphics) {
		Component modeLabel = Component.translatable("productivebeesgenesis.config.filter_mode");
		int modeLabelWidth = screen.getMinecraft().font.width(modeLabel);
		int firstModeX = modeButtons[0] != null ? modeButtons[0].getX() : screen.width - FilterListScreen.SCREEN_MARGIN - 64;
		graphics.drawString(screen.getMinecraft().font, modeLabel, firstModeX - modeLabelWidth - 8, 29, GuiColors.TEXT_DIM_GRAY);
	}

	/**
	 * 高亮当前激活的过滤模式按钮（绘制白色边框）。
	 */
	void renderModeButtonHighlight(GuiGraphics graphics) {
		for (ModConfig.FilterMode mode : ModConfig.FilterMode.values()) {
			Button btn = modeButtons[mode.ordinal()];
			if (btn == null || mode != screen.filterMode) {
				continue;
			}
			// 当前激活模式绘制高亮边框
			graphics.fill(btn.getX() - 1, btn.getY() - 1, btn.getX() + btn.getWidth() + 1, btn.getY(), GuiColors.TEXT_WHITE);
			graphics.fill(btn.getX() - 1, btn.getY() + btn.getHeight(), btn.getX() + btn.getWidth() + 1,
					btn.getY() + btn.getHeight() + 1, GuiColors.TEXT_WHITE);
			graphics.fill(btn.getX() - 1, btn.getY(), btn.getX(), btn.getY() + btn.getHeight(), GuiColors.TEXT_WHITE);
			graphics.fill(btn.getX() + btn.getWidth(), btn.getY(), btn.getX() + btn.getWidth() + 1,
					btn.getY() + btn.getHeight(), GuiColors.TEXT_WHITE);
		}
	}

	/** 获取过滤模式对应的图标字符 */
	private String getModeIcon(ModConfig.FilterMode mode) {
		return switch (mode) {
			case DISABLED -> "\u26D4";
			case WHITELIST -> "\u2713";
			case BLACKLIST -> "\u2717";
		};
	}
}
