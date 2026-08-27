package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.network.ToggleApiaryFeederConversionPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 喂食槽转化开关按钮 — 与项目其他 per-tile 开关同款视觉语言
 * <br/>
 * DEFAULT 灰色背景 + 单字符标签 + 无阴影，开启时文字转绿（0x009E45），关闭时深灰（0x232323），
 * 与 {@code ApiaryDirectEjectButton}/{@code SmeltingCompatButton} 保持一致。
 * <p>
 * 状态直接从 tile 读取：{@code feederConversionEnabled} 已由 {@code ApiaryContainerTrackers}
 * 注册为容器 tracker，客户端读到的是服务端同步值，无需本地镜像字段（避免客户端/服务端状态漂移）。
 * 点击仅发包，实际写入由服务端 handler 完成（客户端不擅自改状态，等 tracker 回传）。
 */
final class FeederConversionButton extends MekanismButton {

	private final TileEntityMekApiary tile;

	/** 上一次写入 tooltip 时的开关状态（-1=未初始化），避免每帧重建 Tooltip 对象产生垃圾 */
	private int tooltipState = -1;

	FeederConversionButton(IGuiWrapper gui, int x, int y, int width, int height, TileEntityMekApiary tile) {
		super(gui, x, y, width, height, Component.literal("\u8F6C"), (element, mouseX, mouseY) -> {
			if (element instanceof FeederConversionButton button) {
				PacketDistributor.sendToServer(
						new ToggleApiaryFeederConversionPayload(button.tile.getBlockPos()));
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.tile = tile;
	}

	@Override
	public void updateTooltip(int mouseX, int mouseY) {
		int state = tile.isFeederConversionEnabled() ? 1 : 0;
		if (state == tooltipState) return;
		tooltipState = state;
		setTooltip(Tooltip.create(Component.translatable(state == 1
				? "gui.productivebeesgenesis.feeder_window.conversion.enabled"
				: "gui.productivebeesgenesis.feeder_window.conversion.disabled")));
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return tile.isFeederConversionEnabled() ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
