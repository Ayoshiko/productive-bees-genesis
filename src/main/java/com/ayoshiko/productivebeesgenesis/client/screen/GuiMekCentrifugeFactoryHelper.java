package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.client.jei.ProductiveBeesGenesisJEI;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiFluidGauge;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.neoforged.fml.ModList;

/**
	 * MEK离心机工厂GUI辅助工具类
	 * <br/>
	 * 抽取三个工厂GUI（原版/ME/EME）的公共widget创建逻辑，消除进度条、流体槽、
	 * 能量条与能量标签的重复代码。
	 * <p>
	 * 设计说明：
	 * <ul>
	 *   <li>三个GUI继承不同的Mekanism父类且泛型参数不同，无法通过继承抽取公共基类</li>
	 *   <li>addRenderableWidget是protected方法，工具类无法直接调用，因此本类只负责
	 *       创建并配置widget，由GUI自行调用addRenderableWidget添加</li>
	 *   <li>drawForegroundText调用了protected的renderTitleText/renderInventoryText，
	 *       无法抽取到工具类，由各GUI自行实现（实现完全相同）</li>
	 * </ul>
	 * 布局参数通过 {@link com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper} 动态计算，
	 * 工具类只负责widget的创建与链式配置。
	 */
public final class GuiMekCentrifugeFactoryHelper {

	private GuiMekCentrifugeFactoryHelper() {
	}

	/**
	 * 创建进度条循环（输入槽与主输出槽之间）
	 * <br/>
	 * recipeViewerCategories注册双配方类型：SMELTING + PB离心配方，支持JEI双配方跳转。
	 * 每个进度条配置INPUT_DOESNT_PRODUCE_OUTPUT警告。
	 *
	 * @param gui GUI实例（GuiProgress构造需要IGuiWrapper）
	 * @param processes 进程数（tile.tier.processes）
	 * @param scaledProgress 进度供应商（cacheIndex -> 进度比例，缩放参数固定为1）
	 * @param warningCheck 警告检查供应商（cacheIndex -> BooleanSupplier）
	 * @param baseX 起始X坐标（FactoryLayoutHelper.getBaseX）
	 * @param baseXMult X间距（FactoryLayoutHelper.getBaseXMult）
	 * @return 配置好的进度条列表，GUI需逐个调用addRenderableWidget添加
	 */
	public static List<GuiProgress> createProgressBars(
			IGuiWrapper gui,
			BlockEntity tile,
			int processes,
			IntToDoubleFunction scaledProgress,
			IntFunction<BooleanSupplier> warningCheck,
			int baseX, int baseXMult) {
		List<GuiProgress> bars = new ArrayList<>(processes);
		for (int i = 0; i < processes; i++) {
			int cacheIndex = i;
			int xPos = baseX + (i * baseXMult);
			// 进度条DOWN类型在输入槽与主输出槽之间
			// 物品输出槽由dynamicSlots自动渲染蓝色边框，无需手动添加GuiSlot
			GuiProgress progress = new GuiProgress(
					() -> ProgressDisplaySmoother.smooth(tile, cacheIndex, scaledProgress.applyAsDouble(cacheIndex)),
					ProgressType.DOWN, gui, 4 + xPos, 33);
			progress.recipeViewerCategories(RecipeViewerRecipeType.SMELTING, jeiViewerTypeOrNull())
					.warning(WarningType.INPUT_DOESNT_PRODUCE_OUTPUT, warningCheck.apply(cacheIndex));
			bars.add(progress);
		}
		return bars;
	}


	/**
	 * 返回 JEI 的 PB 离心配方查看器类型；JEI 未安装时返回 null。
	 * <br/>
	 * 原理：ProductiveBeesGenesisJEI implements mezz.jei.api.IModPlugin（硬引用 JEI 类），
	 * 直接调用其静态方法会在 JEI 未安装时触发 NoClassDefFoundError；
	 * 通过 ModList 守卫确保仅 JEI 已加载时才触达该类引用（JVM 惰性类加载）。
	 *
	 * @return JEI 配方查看器类型，JEI 未安装时为 null
	 */
	@org.jetbrains.annotations.Nullable
	public static mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType
			<cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe> jeiViewerTypeOrNull(
	) {
		if (ModList.get() != null && ModList.get().isLoaded("jei")) {
			return ProductiveBeesGenesisJEI.getPbCentrifugeViewerType();
		}
		return null;
	}

	/**
	 * 创建标准能量条（右侧布局，用于原版4等级/ME/EME）
	 * <br/>
	 * 位置：x = imageWidth - 12, y = 16, 高度 = 73。
	 * EM高等级使用左侧布局，由GuiMekCentrifugeFactory自行处理。
	 *
	 * @param gui GUI实例
	 * @param energyContainer 能量容器（tile.getEnergyContainer()）
	 * @param imageWidth GUI图像宽度
	 * @return 未添加警告的GuiVerticalPowerBar，GUI需链式调用.warning()并addRenderableWidget
	 */
	public static GuiVerticalPowerBar createStandardPowerBar(
			IGuiWrapper gui,
			IEnergyContainer energyContainer,
			int imageWidth) {
		return new GuiVerticalPowerBar(gui, energyContainer, imageWidth - 12, 16, 73);
	}

	/**
	 * 创建能量标签页
	 * <br/>
	 * 使用默认构造（Y=137），显示能量使用信息。
	 *
	 * @param gui GUI实例
	 * @param energyContainer 能量容器（tile.getEnergyContainer()）
	 * @param lastUsage 最近能量使用量供应商（tile::getLastUsage）
	 * @return GuiEnergyTab，GUI需调用addRenderableWidget添加
	 */
	public static GuiEnergyTab createEnergyTab(
			IGuiWrapper gui,
			MachineEnergyContainer<?> energyContainer,
			LongSupplier lastUsage) {
		return new GuiEnergyTab(gui, energyContainer, new EnergyUsageDisplaySmoother(lastUsage));
	}

	/**
	 * 创建共享流体输出槽
	 * <br/>
	 * 位置通过FactoryLayoutHelper动态计算，避免与输出槽重叠。
	 * 使用SMALL尺寸（适配3行输出槽布局）。
	 *
	 * @param gui GUI实例
	 * @param tankSupplier 流体槽供应商（tile::getFluidOutputTank）
	 * @param tanksSupplier 流体槽列表供应商（() -> tile.getFluidTanks(null)）
	 * @param x X坐标（FactoryLayoutHelper.getFluidTankX）
	 * @param y Y坐标（FactoryLayoutHelper.getFluidTankY）
	 * @return GuiFluidGauge，GUI需调用addRenderableWidget添加
	 */
	public static GuiFluidGauge createFluidGauge(
			IGuiWrapper gui,
			Supplier<IExtendedFluidTank> tankSupplier,
			Supplier<List<IExtendedFluidTank>> tanksSupplier,
			int x, int y) {
		return new GuiFluidGauge(tankSupplier, tanksSupplier, GaugeType.SMALL, gui, x, y);
	}
}
