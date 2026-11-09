package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.window.GuiSideConfiguration;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 保留直输和熔炼按钮使用的数据类型；输入配置控件由原生窗口直接创建。 */
public final class AeInputOverlay {
	private AeInputOverlay() {
	}

	public record OverlayTarget(GuiMekanism<?> gui, GuiSideConfiguration<?> sideConfig,
			BlockEntity tile, TransmissionType type) {
	}
}
