package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.network.SetAllFeederSlotsDisabledPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.BooleanSupplier;

/**
 * 喂食槽「禁用编辑模式」开关按钮
 * <br/>
 * 两种点击语义：
 * <ul>
 *   <li><b>普通左键</b>：切换编辑模式（纯客户端状态，不发包）。开启后左键点格子即切换该格禁用</li>
 *   <li><b>Shift + 左键</b>：批量操作，一次性全禁 / 全启（发 {@link SetAllFeederSlotsDisabledPayload}）</li>
 * </ul>
 * 与 {@link FeederConversionButton} 同款视觉语言（DEFAULT 灰底 + 单字符 + 无阴影），
 * 模式开启时文字转橙（0xE08A00）以区别于转化开关的绿色。
 * <p>
 * 设计原因（为何模式是纯客户端状态）：编辑模式只影响本地点击的解释方式
 * （左键 = 切换禁用 而非 拿取物品），不改变任何服务端数据，因此无需 tracker 同步；
 * 而批量操作真正改数据，必须走服务端权威路径。
 */
final class FeederDisableModeButton extends MekanismButton {

	/** 模式状态查询（由窗口持有真值，按钮只读） */
	private final BooleanSupplier modeActive;

	/** 上一次写入 tooltip 时的开关状态（-1=未初始化），避免每帧重建 Tooltip 对象产生垃圾 */
	private int tooltipState = -1;

	FeederDisableModeButton(IGuiWrapper gui, int x, int y, int width, int height,
			TileEntityMekApiary tile, BooleanSupplier modeActive, Runnable onToggleMode) {
		super(gui, x, y, width, height, Component.literal("\u7981"), (element, mouseX, mouseY) -> {
			if (Screen.hasShiftDown()) {
				sendBatchRequest(tile);
			} else {
				onToggleMode.run();
			}
			return true;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.modeActive = modeActive;
	}

	/**
	 * 发送批量禁用 / 恢复请求
	 * <br/>
	 * 方向按当前同步状态判定：只要还存在生效格子（非空且未禁用）就全禁，否则全启。
	 * 传绝对目标状态而非"翻转"，多人同时点击时天然幂等。
	 * <p>
	 * {@code hasAnyFlower()} 的语义正是"存在生效格子"，且结果按喂食槽状态版本号缓存，
	 * 点击路径上是 O(1)。
	 */
	private static void sendBatchRequest(TileEntityMekApiary tile) {
		boolean disableAll = tile.getFeederSlotManager().hasAnyFlower();
		PacketDistributor.sendToServer(
				new SetAllFeederSlotsDisabledPayload(tile.getBlockPos(), disableAll));
	}

	@Override
	public void updateTooltip(int mouseX, int mouseY) {
		int state = modeActive.getAsBoolean() ? 1 : 0;
		if (state == tooltipState) return;
		tooltipState = state;
		setTooltip(Tooltip.create(Component.translatable(state == 1
				? "gui.productivebeesgenesis.feeder_window.disable_mode.on"
				: "gui.productivebeesgenesis.feeder_window.disable_mode.off")));
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return modeActive.getAsBoolean() ? 0xE08A00 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
