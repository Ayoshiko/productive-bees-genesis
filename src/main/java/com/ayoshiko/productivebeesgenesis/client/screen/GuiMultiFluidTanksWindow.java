package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiFluidGauge;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 多流体状态窗口；主槽留在机器界面，额外槽按屏幕宽度换行。 */
public class GuiMultiFluidTanksWindow extends GuiWindow {

	private static final int PIN_X_OFFSET = 16;
	private static final int PIN_Y_OFFSET = 6;

	private final IMultiFluidTankHost host;
	private int lastTankCount = -1;
	private int lastAvailableWidth = -1;
	private GuiElementHolder gridBackground;
	private final List<GuiFluidGauge> gaugeElements = new ArrayList<>();

	public GuiMultiFluidTanksWindow(IGuiWrapper gui, int x, int y, IMultiFluidTankHost host,
		SelectedWindowData windowData) {
		super(gui, x, y,
				GuiMultiFluidTanksLayoutHelper.calculateWindowWidth(extraTankCount(host), availableWidth()),
				GuiMultiFluidTanksLayoutHelper.calculateWindowHeight(extraTankCount(host),
						GuiMultiFluidTanksLayoutHelper.calculateCols(extraTankCount(host), availableWidth())),
				windowData);
		this.host = host;
		this.interactionStrategy = InteractionStrategy.ALL;
		addChild(new GuiPinButton(gui(), relativeX + PIN_X_OFFSET, relativeY + PIN_Y_OFFSET, this));
		updateLayout();
	}

	private static int extraTankCount(IMultiFluidTankHost host) {
		return Math.max(0, host.getFluidTankCount() - 1);
	}

	private static int availableWidth() {
		return GuiMultiFluidTanksLayoutHelper.calculateAvailableWidth(minecraft.getWindow().getGuiScaledWidth());
	}

	/** 布局变更只在构造、tick 和 resize 进行，避免渲染遍历期间修改子元素。 */
	private void updateLayout() {
		int tankCount = extraTankCount(host);
		int availableWidth = availableWidth();
		if (tankCount == lastTankCount && availableWidth == lastAvailableWidth) {
			return;
		}
		lastTankCount = tankCount;
		lastAvailableWidth = availableWidth;

		for (GuiFluidGauge gauge : gaugeElements) {
			children().remove(gauge);
		}
		gaugeElements.clear();
		if (gridBackground != null) {
			children().remove(gridBackground);
			gridBackground = null;
		}

		int cols = GuiMultiFluidTanksLayoutHelper.calculateCols(tankCount, availableWidth);
		int rows = GuiMultiFluidTanksLayoutHelper.calculateVisibleRows(tankCount, cols);
		width = GuiMultiFluidTanksLayoutHelper.calculateWindowWidth(tankCount, availableWidth);
		height = GuiMultiFluidTanksLayoutHelper.calculateWindowHeight(tankCount, cols);
		keepInsideViewport();
		if (tankCount == 0) {
			return;
		}

		int gridWidth = cols * GuiMultiFluidTanksLayoutHelper.SLOT_PITCH - GuiMultiFluidTanksLayoutHelper.GAUGE_GAP;
		int gridHeight = rows * GuiMultiFluidTanksLayoutHelper.ROW_PITCH - GuiMultiFluidTanksLayoutHelper.GAUGE_GAP;
		gridBackground = addChild(new GuiElementHolder(gui(),
				relativeX + GuiMultiFluidTanksLayoutHelper.LEFT_PADDING,
				relativeY + GuiMultiFluidTanksLayoutHelper.GRID_Y, gridWidth, gridHeight));
		for (int index = 0; index < tankCount; index++) {
			// 主界面已使用槽 0，窗口供应器继续指向原始槽 1..count-1。
			int tankIndex = index + 1;
			int gaugeX = GuiMultiFluidTanksLayoutHelper.calculateGaugeX(index, cols);
			int gaugeY = GuiMultiFluidTanksLayoutHelper.calculateGaugeY(index, cols);
			GuiFluidGauge gauge = addChild(new GuiFluidGauge(
					() -> host.getFluidTank(tankIndex),
					() -> host.getFluidTanks(),
					GaugeType.SMALL, gui(), relativeX + gaugeX, relativeY + gaugeY));
			gaugeElements.add(gauge);
		}
	}

	private void keepInsideViewport() {
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int screenHeight = minecraft.getWindow().getGuiScaledHeight();
		int targetX = Math.max(0, Math.min(getX(), screenWidth - width));
		int targetY = Math.max(0, Math.min(getY(), screenHeight - height));
		move(targetX - getX(), targetY - getY());
	}

	@Override
	protected int getTitlePadStart() {
		return 14 + GuiPinButton.WIDTH;
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawTitleText(guiGraphics,
				Component.translatable("gui.productivebeesgenesis.multi_fluid_tanks_window.title"), 5);
	}

	@Override
	public void tick() {
		super.tick();
		updateLayout();
	}

	@Override
	public void resize(int prevLeft, int prevTop, int left, int top) {
		super.resize(prevLeft, prevTop, left, top);
		updateLayout();
		keepInsideViewport();
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.setColor(1, 1, 1, 1);
	}
}
