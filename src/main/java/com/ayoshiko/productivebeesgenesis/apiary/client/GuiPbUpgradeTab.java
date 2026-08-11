package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
	 * PB 升级 TAB 按钮
	 * <br/>
	 * 重构：泛型化 {@code TILE extends IPbUpgradeProvider}，使蜂箱与离心机可共用本组件。
	 * 蜂箱传入 {@code GuiPbUpgradeTab<TileEntityMekApiary>}，离心机传入
	 * {@code GuiPbUpgradeTab<TileEntityMekCentrifuge>}，两者均实现 {@link IPbUpgradeProvider}。
	 *
	 * @param <TILE> 方块实体类型，必须实现 IPbUpgradeProvider
	 */
public class GuiPbUpgradeTab<TILE extends IPbUpgradeProvider> extends GuiWindowCreatorTab<TILE, GuiPbUpgradeTab<TILE>> {

	private static final int TAB_COLOR = 0xFFF57F17;

	private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "textures/gui/pb_upgrade_tab.png");

	public GuiPbUpgradeTab(IGuiWrapper gui, TILE tile, Supplier<GuiPbUpgradeTab<TILE>> elementSupplier) {
		super(ICON, gui, tile, gui.getXSize(), 98, 26, 18, false, elementSupplier);
		setTooltip(Tooltip.create(Component.translatable("gui.productivebeesgenesis.pb_upgrade_tab.tooltip")));
	}

	@Override
	protected void colorTab(GuiGraphics guiGraphics) {
		MekanismRenderer.color(guiGraphics, TAB_COLOR);
	}

	@Override
	protected GuiWindow createWindow(SelectedWindowData windowData) {
		int windowWidth = GuiPbUpgradeWindow.WINDOW_WIDTH;
		int x = Math.max(0, (getGuiWidth() - windowWidth) / 2);
		return new GuiPbUpgradeWindow(gui(), x, 15, dataSource, windowData);
	}

	@Override
	protected SelectedWindowData getNextWindowData() {
		return PbUpgradeInventorySlot.PB_UPGRADE_WINDOW_DATA;
	}
}
