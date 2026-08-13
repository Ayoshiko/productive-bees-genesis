package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
	 * 多流体槽窗口创建 Tab(SRP:仅负责触发窗口创建,不处理窗口内部逻辑)
	 * <br/>
	 * 位于 GUI 左侧 y=131,在 GuiWarningTab(y=101,底部127)下方(间距4px),与右侧 GuiRedstoneControlTab(y=131)对称。
	 * 颜色:MEK 灰阶 #232323(参考 GuiFeederTab/GuiPbUpgradeTab 的 MEK 原生 Tab 图标风格),
	 * 点击后打开 {@link GuiMultiFluidTanksWindow}。
	 * <p>
	 * 仅在 {@link IMultiFluidTankHost#isMultiFluidModeSynced()} 返回 true 时由 GUI 类添加(Task 1:基于同步值而非 holder 类型),
	 * SINGLE 模式不显示此 Tab。
	 * <p>
	 * 设计原则:
	 * <ul>
	 *   <li>继承 MEK {@link GuiWindowCreatorTab},复用窗口创建/关闭/重新挂载的标准生命周期</li>
	 *   <li>单一职责:仅负责创建窗口,窗口布局由 {@link GuiMultiFluidTanksLayoutHelper} 计算</li>
	 *   <li>OCP:通过泛型参数支持任意 IMultiFluidTankHost 子类(原版/ME/EME 工厂)</li>
	 * </ul>
	 *
	 * @param <TILE> 方块实体类型,必须实现 IMultiFluidTankHost
	 * @since Task 8
	 */
public class GuiMultiFluidTanksTab<TILE extends IMultiFluidTankHost>
		extends GuiWindowCreatorTab<TILE, GuiMultiFluidTanksTab<TILE>> {

	/** Tab 颜色 — MEK 灰阶基色 #232323 RGB(35,35,35)(ARGB 格式) */
	private static final int TAB_COLOR = 0xFF232323;

	/** Tab 图标资源路径(18×18 像素) */
	private static final ResourceLocation ICON =
		ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "textures/gui/multi_fluid_tanks_tab.png");

	/**
	 * 窗口数据 — UNSPECIFIED 类型(自定义窗口,不与 MEK 内置窗口冲突)
	 * <br/>
	 * UNSPECIFIED 的 canPin=false,GuiWindow 父类不会自动添加 GuiPinButton,
	 * 由 {@link GuiMultiFluidTanksWindow} 手动添加。
	 */
	private static final SelectedWindowData WINDOW_DATA = new SelectedWindowData(WindowType.UNSPECIFIED);

	static {
		// 设置独立的持久化 saveName,使窗口位置和固定状态持久化到 PB 配置
		// 本类为 client only,SelectedWindowDataMixin 在客户端必加载,cast 必成功
		// try-catch 仅防御 Mixin 应用失败(如配置错误)的极端场景
		try {
			((CustomWindowData) (Object) WINDOW_DATA)
					.productivebeesgenesis$setCustomSaveName("window_multi_fluid_tanks");
		} catch (ClassCastException e) {
			// Mixin 应用失败时降级,窗口位置不持久化
			ProductiveBeesGenesis.LOGGER.warn("GuiMultiFluidTanksTab Mixin 应用失败,窗口位置不持久化", e);
		}
	}

	/**
	 * 构造多流体槽 Tab
	 *
	 * @param gui             所属 GUI 包装器
	 * @param tile            方块实体数据源(必须实现 IMultiFluidTankHost)
	 * @param elementSupplier 自身引用供应器(用于窗口关闭后重新激活 Tab)
	 */
	public GuiMultiFluidTanksTab(IGuiWrapper gui, TILE tile, Supplier<GuiMultiFluidTanksTab<TILE>> elementSupplier) {
		// 左侧 Tab,y=131 位于 GuiWarningTab(101,底部127)下方(间距4px),与右侧 GuiRedstoneControlTab(131)对称
		// y 坐标链审计:GuiSortingTab(62,h=35,底97) → GuiWarningTab(101,h=26,底127) → 本Tab(131,h=26,底157) →
				// GuiEnergyTab(161,h=26,底187)
		super(ICON, gui, tile, -26, 131, 26, 18, true, elementSupplier);
		setTooltip(Tooltip.create(Component.translatable("gui.productivebeesgenesis.multi_fluid_tanks_tab.tooltip")));
	}

	/** 着色 Tab — 使用 MEK 灰阶基色 #232323 */
	@Override
	protected void colorTab(GuiGraphics guiGraphics) {
		MekanismRenderer.color(guiGraphics, TAB_COLOR);
	}

	/**
	 * 创建多流体槽窗口
	 * <br/>
	 * 窗口居中定位,y=15(与 MEK 标准窗口对齐),
	 * 宽度根据流体槽位数动态计算(由 {@link GuiMultiFluidTanksLayoutHelper} 提供)。
	 *
	 * @param windowData 窗口数据(包含固定/位置记忆信息)
	 * @return 新建的 {@link GuiMultiFluidTanksWindow}
	 */
	@Override
	protected GuiWindow createWindow(SelectedWindowData windowData) {
		int tankCount = dataSource.getFluidTankCount();
		int windowWidth = GuiMultiFluidTanksLayoutHelper.calculateWindowWidth(tankCount);
		// 水平居中:窗口左上角 X = max(0, (guiWidth - windowWidth) / 2)
		int x = Math.max(0, (getGuiWidth() - windowWidth) / 2);
		return new GuiMultiFluidTanksWindow(gui(), x, 15, dataSource, windowData);
	}

	/** 获取下一个窗口数据 */
	@Override
	protected SelectedWindowData getNextWindowData() {
		return WINDOW_DATA;
	}
}
