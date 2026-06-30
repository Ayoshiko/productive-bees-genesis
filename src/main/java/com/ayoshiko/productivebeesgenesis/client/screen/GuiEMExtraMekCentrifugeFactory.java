package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityEMExtraMekCentrifugeFactory;
import io.github.masyumero.emextras.client.gui.element.tab.EMExtraGuiSortingTab;

import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.WarningTracker.WarningType;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * EME扩展版离心机工厂Screen
 * <br/>
 * 继承Mekanism的GuiConfigurableTile，使用dynamicSlots=true自动渲染槽位背景。
 * 每进程：1红色输入槽 + 3蓝色输出槽（主/副1/副2）+ 共享流体槽。
 * <p>
 * 布局参数通过 {@link FactoryLayoutHelper} 的EMExtraFactoryTier重载方法动态计算，
 * 支持EME 4等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）。
 * <p>
 * 与原版/ME工厂GUI的差异：
 * - 使用EME的EMExtraGuiSortingTab（而非原版GuiSortingTab或ME的ExtraGuiSortingTab）
 * - 3行输出槽需要额外高度（+40），inventoryLabelY=125
 * - EME tier直接存储imageWidth和inventoryLabelX，无需公式推导
 * - 流体输出槽在左侧固定位置
 * - 进度条使用SMELTING + PB离心配方的双配方跳转
 */
public class GuiEMExtraMekCentrifugeFactory extends GuiConfigurableTile<TileEntityEMExtraMekCentrifugeFactory, MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>> {

	public GuiEMExtraMekCentrifugeFactory(MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory> container, Inventory inv, Component title) {
		super(container, inv, title);
		// 3行输出槽需要额外高度：标准187 + 副输出1(20) + 副输出2(20) = 227
		imageHeight = 187 + 40;
		inventoryLabelY = 125;

		// EME tier直接存储imageWidth增量值
		imageWidth += FactoryLayoutHelper.getImageWidthAddition(tile.tier);

		// EME tier直接存储inventoryLabelX值
		inventoryLabelX = FactoryLayoutHelper.getInventoryLabelX(tile.tier);
		titleLabelY = 4;
		dynamicSlots = true;
	}

	@Override
	protected void addGuiElements() {
		super.addGuiElements();
		addRenderableWidget(new EMExtraGuiSortingTab(this, tile));
		// 标准能量条（右侧布局）+ 能量标签
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createStandardPowerBar(this, tile.getEnergyContainer(), imageWidth))
				.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0));
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage));

		// 进度条循环（输入槽与主输出槽之间，双配方跳转）
		int baseX = FactoryLayoutHelper.getBaseX(tile.tier);
		int baseXMult = FactoryLayoutHelper.getBaseXMult(tile.tier);
		for (GuiProgress bar : GuiMekCentrifugeFactoryHelper.createProgressBars(
				this, tile.tier.processes,
				i -> tile.getScaledProgress(1, i),
				i -> tile.getWarningCheck(RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT, i),
				baseX, baseXMult)) {
			addRenderableWidget(bar);
		}

		// 共享流体输出槽 — 位置通过FactoryLayoutHelper动态计算，避免与输出槽重叠
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createFluidGauge(
				this,
				tile::getFluidOutputTank,
				() -> tile.getFluidTanks(null),
				FactoryLayoutHelper.getFluidTankX(tile.tier),
				FactoryLayoutHelper.getFluidTankY(tile.tier)));
	}

	@Override
	protected void drawForegroundText(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		renderTitleText(guiGraphics);
		renderInventoryText(guiGraphics);
		super.drawForegroundText(guiGraphics, mouseX, mouseY);
	}
}
