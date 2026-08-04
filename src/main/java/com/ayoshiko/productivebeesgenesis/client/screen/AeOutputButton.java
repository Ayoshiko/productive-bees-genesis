package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * AE2 输出切换按钮 — 注入到 MEK 侧面配置窗口
 * <br/>
 * 14×14 像素按钮，显示 "A" 文字，点击时发送 {@link CycleAeOutputPayload} 到服务端，
 * 切换 per-tile AE2 物品/流体输出开关。
 * <p>
 * 继承 {@link MekanismButton} 以复用 Mekanism 的按钮渲染管线（纹理、悬停效果等），
 * 使用 DEFAULT 灰色背景（{@link GuiElement.ButtonBackground#DEFAULT}），
 * 与 GuiAeInputConfig 中的 CtrlButton 视觉一致，避免与 MEK SideConfig
 * 窗口原版按钮风格冲突。
 * <p>
 * <b>target 字段</b>：由 {@link AeOutputOverlay#ensureButton} 每帧更新，
 * 指向当前侧面配置窗口的上下文（GUI、方块实体、传输类型）。
 * 点击回调通过 target 获取方块坐标和传输类型，发送对应的网络包。
 * <p>
 * <b>双重点击处理</b>：{@link AeOutputOverlay#mouseClicked} 事件处理器在事件阶段
 * 拦截点击（Pre 阶段优先于屏幕 mouseClicked），按钮自身的 onPress 回调作为后备路径，
 * 确保即使事件被其他模组取消也能响应。两者不会同时执行（事件取消后按钮不接收点击）。
 */
public class AeOutputButton extends MekanismButton {

	/** 按钮尺寸（宽=高） */
	private static final int BUTTON_SIZE = 14;

	/** 当前覆盖层目标 — 由 AeOutputOverlay 每帧更新 */
	AeOutputOverlay.OverlayTarget target;

	/**
	 * 构造 AE2 输出按钮
	 *
	 * @param gui    Mekanism GUI 实例
	 * @param x      按钮 X 坐标（相对 GUI 左上角）
	 * @param y      按钮 Y 坐标（相对 GUI 左上角）
	 * @param target 初始覆盖层目标
	 */
	public AeOutputButton(GuiMekanism<?> gui, int x, int y, AeOutputOverlay.OverlayTarget target) {
		super(gui, x, y, BUTTON_SIZE, BUTTON_SIZE, Component.literal("A"), (element, mouseX, mouseY) -> {
			if (element instanceof AeOutputButton button && button.target != null
					&& AeOutputOverlay.canToggle(button.target.type())) {
				sendToggle(button.target);
				return true;
			}
			return false;
		});
		// 使用 DEFAULT 灰色背景，与 GuiAeInputConfig.CtrlButton 一致
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return active && target != null
				&& AeOutputOverlay.isPerTileEnabled(target.tile(), target.type())
				? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}

	/**
	 * 发送切换 per-tile AE2 输出的网络包
	 * <br/>
	 * 将 Mekanism 的 TransmissionType 转换为我们的 OutputType 枚举。
	 * 仅 canToggle 类型（ITEM/FLUID）会调用此方法。
	 */
	private static void sendToggle(AeOutputOverlay.OverlayTarget target) {
		CycleAeOutputPayload.OutputType outputType = target.type() == TransmissionType.FLUID
				? CycleAeOutputPayload.OutputType.FLUID
				: CycleAeOutputPayload.OutputType.ITEM;
		PacketDistributor.sendToServer(new CycleAeOutputPayload(target.tile().getBlockPos(), outputType));
	}
}
