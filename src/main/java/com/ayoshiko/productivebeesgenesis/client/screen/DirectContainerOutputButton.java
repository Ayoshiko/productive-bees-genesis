package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 产物直通 per-tile 开关按钮（蜂箱与离心机共用）。
 * <br/>
 * 14×14 像素，样式与 {@link SmeltingCompatButton} / {@code ApiaryDirectEjectButton} 完全一致
 * （{@link GuiElement.ButtonBackground#DEFAULT} 灰底、单字符、无阴影），开启时文字转绿。
 * <p>
 * 只持有方块实体引用、每帧由覆盖层重新赋值：既不认识蜂箱/离心机的具体类型
 * （状态查询委托 {@link DirectContainerOutputOverlay}），也不会每帧分配新的回调闭包。
 */
class DirectContainerOutputButton extends MekanismButton {

	private static final int BUTTON_SIZE = 14;

	/** 当前 GUI 对应的方块实体（覆盖层每帧刷新，避免持有过期引用） */
	BlockEntity tile;

	DirectContainerOutputButton(GuiMekanism<?> gui, int x, int y, BlockEntity tile) {
		super(gui, x, y, BUTTON_SIZE, BUTTON_SIZE, Component.literal("O"), (element, mouseX, mouseY) -> {
			if (element instanceof DirectContainerOutputButton button && button.tile != null) {
				DirectContainerOutputOverlay.sendToggle(button.tile);
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.tile = tile;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return tile != null && DirectContainerOutputOverlay.isPerTileEnabled(tile) ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
