package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.ReturnCentrifugeInputPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 离心机输入槽返还按钮。
 * <br/>
 * 基础机和工厂分别锚定在机器槽区域与玩家物品栏之间的空白带，
 * 避免高等级工厂首个输入槽靠近边缘时覆盖相邻输入槽或左侧流体控件。
 */
public final class CentrifugeInputReturnButton extends MekanismButton {

	private static final int SIZE = 14;
	private static final int RIGHT_MARGIN = 7;
	private static final int CENTRIFUGE_Y = 78;
	private static final int FACTORY_Y = 117;
	private static final Component LABEL = Component.literal("R");

	private CentrifugeInputReturnButton(IGuiWrapper gui, int x, int y, BlockPos pos) {
		super(gui, x, y, SIZE, SIZE, LABEL, (element, mouseX, mouseY) -> {
			if (element instanceof CentrifugeInputReturnButton) {
				PacketDistributor.sendToServer(new ReturnCentrifugeInputPayload(pos));
				return true;
			}
			return false;
		});
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		setTooltip(Tooltip.create(Component.translatable(
				"productivebeesgenesis.gui.centrifuge_input_return.tooltip")));
	}

	/**
	 * 为基础离心机创建按钮。
	 * <br/>
	 * Y=78 位于最后一个输出槽底部（75）与玩家物品栏顶部（100）之间。
	 *
	 * @param gui 所属 GUI
	 * @param imageWidth GUI 宽度
	 * @param pos 方块坐标
	 * @return 按钮
	 */
	public static CentrifugeInputReturnButton createForCentrifuge(IGuiWrapper gui,
			int imageWidth, BlockPos pos) {
		return createRightAligned(gui, imageWidth, CENTRIFUGE_Y, pos);
	}

	/**
	 * 为任意等级的离心机工厂创建按钮。
	 * <br/>
	 * Y=117 位于第三排输出槽底部（115）与玩家物品栏顶部（135）之间；
	 * 右对齐可避开不同宽度工厂居中或左对齐的物品栏标题。
	 *
	 * @param gui 所属 GUI
	 * @param imageWidth GUI 宽度
	 * @param pos 方块坐标
	 * @return 按钮
	 */
	public static CentrifugeInputReturnButton createForFactory(IGuiWrapper gui,
			int imageWidth, BlockPos pos) {
		return createRightAligned(gui, imageWidth, FACTORY_Y, pos);
	}

	private static CentrifugeInputReturnButton createRightAligned(IGuiWrapper gui,
			int imageWidth, int y, BlockPos pos) {
		return new CentrifugeInputReturnButton(gui, imageWidth - SIZE - RIGHT_MARGIN, y, pos);
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return isMouseOver(mouseX, mouseY) ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}
}
