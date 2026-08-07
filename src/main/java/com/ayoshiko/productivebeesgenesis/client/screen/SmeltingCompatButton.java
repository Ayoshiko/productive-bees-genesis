package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.function.Consumer;

import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;

/**
 * 离心机电力熔炼炉配方兼容开关按钮 — 注入到 MEK 侧面配置窗口
 * <br/>
 * 14×14 像素按钮，样式与 {@link AeInputButton} 完全一致
 * （{@link GuiElement.ButtonBackground#DEFAULT} 灰色背景、单字符文字、无阴影）。
 * 点击时通过回调发送 {@link com.ayoshiko.productivebeesgenesis.network.ToggleSmeltingCompatPayload}
 * 到服务端切换 per-tile 熔炉配方兼容开关。
 */
public class SmeltingCompatButton extends MekanismButton {

	/** 按钮尺寸（宽=高） */
	private static final int BUTTON_SIZE = 14;

	/** 当前覆盖层目标 — 由 SmeltingCompatOverlay 每帧更新 */
	AeInputOverlay.OverlayTarget target;

	/** 点击回调 — 发送切换网络包 */
	private final Consumer<AeInputOverlay.OverlayTarget> onClick;

	public SmeltingCompatButton(GuiMekanism<?> gui, int x, int y, Component label,
			Consumer<AeInputOverlay.OverlayTarget> onClick,
			AeInputOverlay.OverlayTarget target) {
		super(gui, x, y, BUTTON_SIZE, BUTTON_SIZE, label, (element, mouseX, mouseY) -> {
			if (element instanceof SmeltingCompatButton button && button.target != null) {
				button.onClick.accept(button.target);
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.target = target;
		this.onClick = onClick;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		// 开关状态通过文字颜色显示：开启绿色（与 ApiaryDirectEjectButton 一致），关闭默认深灰
		if (target != null && target.tile() instanceof IAe2OutputHostBase host) {
			if (host.productivebeesgenesis$getAe2StateHolder() != null
					&& host.productivebeesgenesis$getAe2StateHolder().isSmeltingCompatEnabled()) {
				return 0x009E45;
			}
		}
		return 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
