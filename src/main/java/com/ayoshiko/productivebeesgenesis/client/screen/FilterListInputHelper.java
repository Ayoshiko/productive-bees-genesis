package com.ayoshiko.productivebeesgenesis.client.screen;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.mojang.datafixers.util.Pair;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * FilterListScreen 的输入框与添加验证辅助类
 * <p>
 * 将新增蜜蜂类型的输入框、确认/取消按钮、输入校验与提示渲染等逻辑从屏幕类中剥离，
 * 降低 FilterListScreen 的复杂度（SRP）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责输入框交互与输入校验，不涉及列表数据语义或配置持久化</li>
 *   <li>组合模式 — 持有 {@link FilterListScreen} 引用，通过包级访问共享必要状态</li>
 * </ul>
 * <br/>
 * 线程安全：客户端 GUI 单线程访问，无需同步。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListInputHelper {

	private final FilterListScreen screen;

	/** 输入框是否可见 */
	private boolean inputVisible = false;
	private EditBox inputField;
	private Button confirmAddButton;
	private Button cancelButton;

	FilterListInputHelper(FilterListScreen screen) {
		this.screen = screen;
	}

	/**
	 * 创建输入框、确认与取消按钮（默认隐藏）。
	 * <p>
	 * 布局坐标由 {@link FilterListScreen#initControlBar()} 预先计算并传入，
	 * 本方法仅负责组件创建与初始状态设置。
	 *
	 * @param bottomY   按钮 Y 坐标
	 * @param inputX    输入框 X 坐标
	 * @param inputW    输入框宽度
	 * @param confirmX  确认按钮 X 坐标
	 * @param confirmW  确认按钮宽度
	 * @param cancelX   取消按钮 X 坐标
	 * @param cancelW   取消按钮宽度
	 */
	void initInputField(int bottomY, int inputX, int inputW, int confirmX, int confirmW, int cancelX, int cancelW) {
		inputField = new EditBox(screen.getMinecraft().font, inputX, bottomY, inputW, 20,
				Component.translatable("productivebeesgenesis.config.input_bee_type"));
		inputField.setMaxLength(128);
		inputField.setHint(Component.translatable("productivebeesgenesis.config.input_bee_type"));
		inputField.setVisible(false);
		screen.addRenderableWidgetBridge(inputField);

		confirmAddButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.confirm"),
				button -> confirmAddEntry()
		).bounds(confirmX, bottomY, confirmW, 20).build();
		confirmAddButton.visible = false;
		screen.addRenderableWidgetBridge(confirmAddButton);

		cancelButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.cancel"),
				button -> toggleInputVisibility(false)
		).bounds(cancelX, bottomY, cancelW, 20).build();
		cancelButton.visible = false;
		screen.addRenderableWidgetBridge(cancelButton);
	}

	/**
	 * 切换输入框及相关按钮的可见性。
	 * <p>
	 * 显示时聚焦输入框；隐藏时清空输入内容。
	 *
	 * @param visible 是否可见
	 */
	void toggleInputVisibility(boolean visible) {
		inputVisible = visible;
		inputField.setVisible(visible);
		confirmAddButton.visible = visible;
		cancelButton.visible = visible;
		if (visible) {
			screen.setFocused(inputField);
			inputField.setFocused(true);
		} else {
			inputField.setValue("");
		}
	}

	/** @return 输入框是否可见（供渲染提示判断使用） */
	boolean isInputVisible() {
		return inputVisible;
	}

	/**
	 * 确认添加当前输入的蜜蜂类型。
	 * <p>
	 * 校验通过后追加到列表、滚动到底部并重建控件。
	 */
	void confirmAddEntry() {
		String text = inputField.getValue();
		Pair<Boolean, Component> validation = validateInput(text);
		if (!validation.getFirst()) return;

		screen.beeTypes.add(text.trim());
		screen.scrollToBottom();
		toggleInputVisibility(false);
		screen.rebuildWidgetsBridge();
	}

	/**
	 * 校验输入文本是否为合法且不重复的蜜蜂类型。
	 * <p>
	 * 校验顺序：非空 → ResourceLocation 格式 → 去重 → 存在性。
	 *
	 * @param text 原始输入文本
	 * @return Pair（是否合法，错误提示组件；合法时提示为空组件）
	 */
	Pair<Boolean, Component> validateInput(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.empty"));
		}
		ResourceLocation beeType = BeeInfoHelper.parseBeeType(trimmed);
		if (beeType == null) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.invalid_format"));
		}
		if (screen.beeTypes.contains(trimmed)) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.duplicate"));
		}
		if (!BeeInfoHelper.isBeeTypeExists(beeType)) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.not_found"));
		}
		return Pair.of(true, Component.empty());
	}

	/**
	 * 渲染输入校验提示（输入框可见且输入非法时显示红色提示）。
	 *
	 * @param graphics 绘图上下文
	 * @param mouseX   鼠标 X 坐标（保留以备扩展，当前未使用）
	 * @param mouseY   鼠标 Y 坐标（保留以备扩展，当前未使用）
	 */
	void renderInputHint(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!inputVisible) return;
		String text = inputField.getValue();
		if (text.isEmpty()) return;

		Pair<Boolean, Component> validation = validateInput(text);
		if (!validation.getFirst()) {
			int bottomY = screen.height - FilterListScreen.LIST_BOTTOM_MARGIN + 10;
			graphics.drawString(screen.getMinecraft().font, validation.getSecond(),
					inputField.getX(), bottomY + 22, GuiColors.STATUS_ERROR_HINT);
		}
	}
}
