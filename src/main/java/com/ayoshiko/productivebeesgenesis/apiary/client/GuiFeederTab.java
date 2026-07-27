package com.ayoshiko.productivebeesgenesis.apiary.client;

import java.util.function.Supplier;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 喂食器窗口创建Tab
 * <br/>
 * 位于 GUI 左侧 y=98（排序Tab在y=62-97，间距1px；不与 MEK 原版 Tab 冲突：左侧 y=6/34/62/137）。
 * 颜色：绿色 #00695c，点击后打开 {@link GuiFeederWindow}。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>继承 MEK {@link GuiWindowCreatorTab}，复用窗口创建/关闭/重新挂载的标准生命周期</li>
 *   <li>单一职责：仅负责创建窗口，不处理窗口内部逻辑</li>
 * </ul>
 */
public class GuiFeederTab extends GuiWindowCreatorTab<TileEntityMekApiary, GuiFeederTab> {

	/** Tab 颜色 — 深青绿 #00695c（ARGB 格式） */
	private static final int TAB_COLOR = 0xFF00695C;

	/** 花朵图标资源 */
	private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "textures/gui/feeder_tab.png");

	/**
	 * 窗口数据 — 使用 UNSPECIFIED 类型（自定义窗口，不与 MEK 内置窗口冲突）
	 * <p>
	 * Task 10: 设置 customSaveName="window_feeder"，使位置和固定状态持久化到 PB 配置。
	 * UNSPECIFIED 的 canPin=false，GuiWindow 父类不会自动添加 GuiPinButton，
	 * 由 {@link GuiFeederWindow} 手动添加。
	 */
	private static final SelectedWindowData WINDOW_DATA = new SelectedWindowData(WindowType.UNSPECIFIED);

	static {
		// Task 10: 为喂食槽窗口设置独立的持久化 saveName
		// 本类为 client only，SelectedWindowDataMixin 在客户端必加载，cast 必成功
		// try-catch 仅防御 Mixin 应用失败（如配置错误）的极端场景
		try {
			((CustomWindowData) (Object) WINDOW_DATA)
					.productivebeesgenesis$setCustomSaveName("window_feeder");
		} catch (ClassCastException e) {
			// Mixin 应用失败时降级，窗口位置不持久化
			ProductiveBeesGenesis.LOGGER.warn("GuiFeederTab Mixin 应用失败，窗口位置不持久化", e);
		}
	}

	/**
	 * 构造喂食器 Tab
	 *
	 * @param gui             所属 GUI 包装器
	 * @param tile            方块实体数据源
	 * @param elementSupplier 自身引用供应器（用于窗口关闭后重新激活 Tab）
	 */
	public GuiFeederTab(IGuiWrapper gui, TileEntityMekApiary tile, Supplier<GuiFeederTab> elementSupplier) {
		super(ICON, gui, tile, -26, 98, 26, 18, true, elementSupplier);
		setTooltip(Tooltip.create(Component.translatable("gui.productivebeesgenesis.feeder_tab.tooltip")));
	}

	/**
	 * 着色 Tab — 使用深青绿色
	 */
	@Override
	protected void colorTab(GuiGraphics guiGraphics) {
		MekanismRenderer.color(guiGraphics, TAB_COLOR);
	}

	/**
	 * 创建喂食器窗口
	 * <br/>
	 * 窗口居中定位，y=15（与 MEK 标准窗口对齐），宽度根据喂食槽列数动态计算。
	 *
	 * @param windowData 窗口数据（包含固定/位置记忆信息）
	 * @return 新建的 {@link GuiFeederWindow}
	 */
	@Override
	protected GuiWindow createWindow(SelectedWindowData windowData) {
		int feederCols = dataSource.getFeederSlotManager().getFeederCols();
		int windowWidth = 11 * 2 + feederCols * 20 - 2;
		int x = Math.max(0, (getGuiWidth() - windowWidth) / 2);
		return new GuiFeederWindow(gui(), x, 15, dataSource, windowData);
	}

	/**
	 * 获取下一个窗口数据
	 *
	 * @return {@link #WINDOW_DATA}
	 */
	@Override
	protected SelectedWindowData getNextWindowData() {
		return WINDOW_DATA;
	}
}
