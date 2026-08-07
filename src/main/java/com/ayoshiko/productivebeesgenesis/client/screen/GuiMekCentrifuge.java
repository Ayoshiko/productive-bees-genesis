package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeTab;
import com.ayoshiko.productivebeesgenesis.client.jei.ProductiveBeesGenesisJEI;
import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiFluidGauge;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.tab.GuiRedstoneControlTab;
import mekanism.client.gui.element.tab.GuiWarningTab;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.IWarningTracker;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * 基础MEK离心机Screen
 * <br/>
 * 继承Mekanism的GuiConfigurableTile，使用dynamicSlots=true自动渲染槽位背景。
 * <p>
 * 布局（imageWidth=176, imageHeight=178）：
 * - 流体输出槽(8,35) 位置通过FactoryLayoutHelper动态计算
 * - 输入槽(64,17) 红色边框（dynamicSlots自动渲染）
 * - 能量槽(64,53) POWER样式（dynamicSlots自动渲染）
 * - 主输出槽(134,17) 蓝色边框（dynamicSlots自动渲染）
 * - 副输出槽1(134,35) 蓝色边框（dynamicSlots自动渲染）
 * - 副输出槽2(134,53) 蓝色边框（dynamicSlots自动渲染）
 * - 进度条(86,38) BAR类型
 * - 能量条(164,15) 垂直
 * <p>
 * imageHeight增加12以容纳流体槽（底部到83），背包标签下移到84。
 */
public class GuiMekCentrifuge extends GuiConfigurableTile<TileEntityMekCentrifuge, MekanismTileContainer<TileEntityMekCentrifuge>> {

	private GuiPbUpgradeTab<TileEntityMekCentrifuge> pbUpgradeTab;

	public GuiMekCentrifuge(MekanismTileContainer<TileEntityMekCentrifuge> container, Inventory inv, Component title) {
		super(container, inv, title);
		dynamicSlots = true;
		// 流体槽底部到83，需要增加imageHeight以避免与背包标签重叠(+12)
		// Tab 布局重排后 GuiEnergyTab 下移至 y=156，需要额外 +6 容纳底部 Tab(+6) → 总 +18(178→184)
		imageHeight += 18;
		// 物品栏标题上移到物品栏格子之上（背包格子位于y=imageHeight-94=90，占y=90-108）
		// 默认inventoryLabelY=imageHeight-94=90，需上移6到y=84
		inventoryLabelY -= 6;
	}

	@Override
	protected void addGuiElements() {
		super.addGuiElements();

		addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(), 164, 15)
				.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY)));
		addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getActive));
		// Tab 布局重排:GuiEnergyTab 下移 Δ=+19(y=137→156),与左侧 Tab 布局对称
		for (GuiEventListener child : children()) {
			if (child instanceof GuiEnergyTab energyTab) {
				energyTab.move(0, 19);
				break;
			}
		}
		pbUpgradeTab = addRenderableWidget(new GuiPbUpgradeTab<>(this, tile, () -> pbUpgradeTab));
		// 流体输出槽 — 位置通过FactoryLayoutHelper动态计算，避免与输出槽重叠
		addRenderableWidget(new GuiFluidGauge(() -> tile.getFluidOutputTank(), () -> tile.getFluidTanks(null), GaugeType.SMALL, this, FactoryLayoutHelper.getCentrifugeFluidTankX(), FactoryLayoutHelper.getCentrifugeFluidTankY()));
		addRenderableWidget(new GuiProgress(
				() -> ProgressDisplaySmoother.smooth(tile, 0, tile.getScaledProgress()),
				ProgressType.BAR, this, 86, 38)
				.recipeViewerCategories(RecipeViewerRecipeType.SMELTING, ProductiveBeesGenesisJEI.getPbCentrifugeViewerType())
				.warning(WarningType.INPUT_DOESNT_PRODUCE_OUTPUT, tile.getWarningCheck(RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT)));
	}

	/**
	 * Tab 布局重排 — 通过 move() 平移 MEK 原生 Tab 实现左右对称
	 * <br/>
	 * 不修改 MEK 源码保持升级兼容性,参考 GuiMekApiary 三种 Tab 移动模式:
	 * 模式1(本方法)、模式2(addGenericTabs)、模式3(addGuiElements 中 GuiEnergyTab)。
	 */
	@Override
	protected void addWarningTab(IWarningTracker warningTracker) {
		// 警告 Tab 上移 Δ=-11(y=109→98),与左侧 Tab 序列对齐
		GuiWarningTab tab = addRenderableWidget(new GuiWarningTab(this, warningTracker, true));
		tab.move(0, -11);
	}

	@Override
	protected void addGenericTabs() {
		super.addGenericTabs();
		// 红石 Tab 上移 Δ=-11(y=137→126),与左侧 GuiMultiFluidTanksTab(y=126)对称
		for (GuiEventListener child : children()) {
			if (child instanceof GuiRedstoneControlTab redstoneTab) {
				redstoneTab.move(0, -11);
				break;
			}
		}
	}

	@Override
	protected void drawForegroundText(@NotNull net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
		renderTitleText(guiGraphics);
		renderInventoryText(guiGraphics);
		super.drawForegroundText(guiGraphics, mouseX, mouseY);
	}
}
