package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.tab.GuiSortingTab;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.factory.TileEntityFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * 工厂版MEK离心机Screen
 * <br/>
 * 继承Mekanism的GuiConfigurableTile，使用dynamicSlots=true自动渲染槽位背景。
 * 每进程：1红色输入槽 + 3蓝色输出槽（主/副1/副2）+ 共享流体槽。
 * <p>
 * 布局参数通过 {@link FactoryLayoutHelper} 动态计算，统一支持原版4等级
 * （BASIC/ADVANCED/ELITE/ULTIMATE）与EvolvedMekanism扩展高等级
 * （OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）。
 * <p>
 * 布局参考：
 * - 原版4等级：mekmm GuiMoreMachineFactory（baseX/baseXMult）+ Mekanism GuiFactory（ULTIMATE imageWidth+=34）
 * - EM高等级：EvolvedMekanism GuiFactoryMixin（imageWidthAddition公式、baseX=9、inventoryLabelX动态居中）
 * - 输入槽 y=13, 主输出 y=57, 副输出1 y=77, 副输出2 y=97
 * - 进度条DOWN类型在输入与主输出之间
 * - 原版4等级：垂直能量条在右侧，流体槽在左侧副输出2行
 * - EM高等级：能量条在左侧（GuiEnergyTab下边框与物品栏最下面一排对齐，GuiVerticalPowerBar上边框与物品栏最上面一排对齐），
 *   流体槽在右侧物品栏右边（间距与红石能量槽一致）
 * <p>
 * dynamicSlots=true自动根据侧面配置渲染红/蓝边框，无需手动添加GuiSlot。
 */
public class GuiMekCentrifugeFactory extends GuiConfigurableTile<TileEntityFactory<?>, MekanismTileContainer<TileEntityFactory<?>>> {

	@SuppressWarnings("unchecked")
	public GuiMekCentrifugeFactory(MekanismTileContainer<TileEntityFactory<?>> container, Inventory inv, Component title) {
		super(container, inv, title);
		// 3行输出槽需要额外高度：标准187 + 副输出1(20) + 副输出2(20) = 227
		imageHeight = 187 + 40;
		inventoryLabelY = 125;

		// 使用FactoryLayoutHelper动态计算imageWidth增量（原版ULTIMATE=34，EM高等级按公式计算）
		imageWidth += FactoryLayoutHelper.getImageWidthAddition(tile.tier);

		// EM高等级不使用inventoryLabelY=75（EM原版是1输出行机器，我们是3输出行）
		// 保持inventoryLabelY=125，避免标签与能量条/流体槽重叠

		// 使用FactoryLayoutHelper动态计算inventoryLabelX
		int labelX = FactoryLayoutHelper.getInventoryLabelX(tile.tier);
		if (labelX == -1) {
			// EM高等级动态居中：imageWidth/2 - font.width(playerInventoryTitle)/2
			inventoryLabelX = imageWidth / 2 - Minecraft.getInstance().font.width(playerInventoryTitle) / 2;
		} else {
			inventoryLabelX = labelX;
		}
		titleLabelY = 4;
		dynamicSlots = true;
	}

	@Override
	protected void addGuiElements() {
		super.addGuiElements();
		addRenderableWidget(new GuiSortingTab(this, tile));

		// 能量条与能量标签页布局：
		// - 原版4等级：能量条在右侧（标准布局），高度73
		// - EM高等级：一比一复刻EvolvedMekanism GuiFactoryMixin实现：
		//   从Container slot动态获取energySlotX（ContainerSlotType.POWER类型的slot.x）
		//   power bar x = energySlotX + 5
		//   power bar y = inventoryLabelY + 9
		//   power bar height = 52
		//   energy tab 使用默认构造（Y=137）
		if (FactoryLayoutHelper.isEMHighTier(tile.tier)) {
			// 从Container slot动态获取energySlotX — 与EM原版GuiFactoryMixin完全一致
			int energySlotX = menu.getInventoryContainerSlots().stream()
					.filter(slot -> slot.getSlotType() == ContainerSlotType.POWER)
					.findFirst()
					.map(slot -> slot.x)
					.orElse(FactoryLayoutHelper.getEnergySlotX(tile.tier));
			addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(), energySlotX + 5, this.inventoryLabelY + 9, 52))
					.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0));
		} else {
			// 原版4等级：标准能量条（右侧布局）
			addRenderableWidget(GuiMekCentrifugeFactoryHelper.createStandardPowerBar(this, tile.getEnergyContainer(), imageWidth))
					.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0));
		}
		// GuiEnergyTab使用默认构造（Y=137），与EM原版一致
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage));

		// 进度条循环（输入槽与主输出槽之间，双配方跳转）
		// 物品输出槽由dynamicSlots自动渲染蓝色边框，无需手动添加GuiSlot
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
		if (tile instanceof TileEntityMekCentrifugeFactory centrifugeFactory) {
			addRenderableWidget(GuiMekCentrifugeFactoryHelper.createFluidGauge(
					this,
					centrifugeFactory::getFluidOutputTank,
					() -> tile.getFluidTanks(null),
					FactoryLayoutHelper.getFluidTankX(tile.tier),
					FactoryLayoutHelper.getFluidTankY(tile.tier)));
		}
	}

	@Override
	protected void drawForegroundText(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		renderTitleText(guiGraphics);
		renderInventoryText(guiGraphics);
		super.drawForegroundText(guiGraphics, mouseX, mouseY);
	}
}
