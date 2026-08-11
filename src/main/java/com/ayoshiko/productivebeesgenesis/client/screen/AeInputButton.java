package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
	 * AE2 输入拉取按钮 — 注入到 MEK 侧面配置窗口
	 * <br/>
	 * 14×14 像素按钮，复用于 3 个输入控制按钮（拉取开关/NBT忽略/过滤模式）。
	 * 点击时通过回调发送对应网络包到服务端。
	 * <p>
	 * 继承 {@link MekanismButton} 复用渲染管线，使用 DEFAULT 灰色背景
	 * （{@link GuiElement.ButtonBackground#DEFAULT}），与 GuiAeInputConfig 中的
	 * CtrlButton 视觉一致，避免与 MEK SideConfig 窗口原版按钮风格冲突。
	 * <p>
	 * <b>target 字段</b>：由 {@link AeInputOverlay#ensureButtons} 每帧更新，
	 * 指向当前侧面配置窗口的上下文。点击回调通过 target 获取方块坐标发送网络包。
	 * <p>
	 * <b>双重点击处理</b>：{@link AeInputOverlay#mouseClicked} 事件处理器在 Pre 阶段
	 * 拦截点击，按钮自身的 onPress 回调作为后备路径，确保即使事件被其他模组取消也能响应。
	 * 两者不会同时执行（事件取消后按钮不接收点击）。
	 */
public class AeInputButton extends MekanismButton {

	/** 按钮尺寸（宽=高） */
	private static final int BUTTON_SIZE = 14;

	/** 当前覆盖层目标 — 由 AeInputOverlay 每帧更新 */
	AeInputOverlay.OverlayTarget target;

	/** 点击回调 — 接收当前 target 执行对应网络包发送 */
	private final Consumer<AeInputOverlay.OverlayTarget> onClick;

	/**
	 * 构造 AE2 输入按钮
	 *
	 * @param gui     Mekanism GUI 实例
	 * @param x       按钮 X 坐标（相对 GUI 左上角）
	 * @param y       按钮 Y 坐标（相对 GUI 左上角）
	 * @param label   按钮显示文字
	 * @param onClick 点击回调（发送对应网络包）
	 * @param target  初始覆盖层目标
	 */
	public AeInputButton(GuiMekanism<?> gui, int x, int y, Component label,
			Consumer<AeInputOverlay.OverlayTarget> onClick,
			AeInputOverlay.OverlayTarget target) {
		super(gui, x, y, BUTTON_SIZE, BUTTON_SIZE, label, (element, mouseX, mouseY) -> {
			if (element instanceof AeInputButton button && button.target != null) {
				button.onClick.accept(button.target);
				return true;
			}
			return false;
		});
		// 使用 DEFAULT 灰色背景，与 GuiAeInputConfig.CtrlButton 一致
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
		this.onClick = onClick;
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
