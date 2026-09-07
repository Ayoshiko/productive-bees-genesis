package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import com.ayoshiko.productivebeesgenesis.apiary.IPagedOutputContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.client.screen.CompactStackCountScreen;
import com.ayoshiko.productivebeesgenesis.client.screen.EnergyUsageDisplaySmoother;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiFluidGauge;
import mekanism.client.gui.element.slot.GuiSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.tab.GuiRedstoneControlTab;
import mekanism.client.gui.element.tab.GuiWarningTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.IWarningTracker;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * MEK 蜂箱 GUI 主类（编排层）
 * <br/>
 * 基于 Mekanism 的 {@link GuiConfigurableTile}，只负责"装配元素 + 在正确时机把渲染/交互
 * 转交给专职协作者"，具体实现按职责拆分到：
 * <ul>
 *   <li>{@link ApiaryBeeSlotGeometry} — 蜜蜂槽坐标换算与命中判定（唯一坐标真源）</li>
 *   <li>{@link ApiaryBeeVisualsRenderer} — 蜜蜂模型/状态灯/名称/Tooltip 渲染与批处理</li>
 *   <li>{@link ApiaryBeeSlotInteraction} — 蜜蜂槽点击派发（选中 / 喂食小食 / 桶式蜂笼）</li>
 *   <li>{@link ApiaryOutputPageControls} — 输出区翻页按钮与页码文本</li>
 *   <li>{@link FeederSlotTooltipDecorator} — 喂食槽禁用状态的 Tooltip 追加</li>
 * </ul>
 * 本类保留的职责：GUI 尺寸/布局参数、MEK Tab 的创建与位移修正、生命周期钩子。
 * <p>
 * 子类扩展点（protected 方法，子类可覆盖以适配不同规模的蜂箱）：
 * <ul>
 *   <li>{@link #getBeeCols()} / {@link #getBeeRows()} / {@link #getOutputCols()}：蜜蜂与输出槽位规模</li>
 *   <li>{@link #getBeeRowH()}：蜜蜂行高（紧凑模式返回较小值）</li>
 *   <li>{@link #addBeeSlotBackgrounds()}：蜜蜂槽位背景渲染</li>
 *   <li>{@link #renderBeeVisuals(GuiGraphics, int, int)}：蜜蜂可视化内容渲染</li>
 *   <li>{@link #renderBeeTooltipIfHovered(GuiGraphics, int, int)}：蜜蜂 Tooltip 渲染</li>
 * </ul>
 *
 * @param <TILE>      蜂箱方块实体类型，必须继承 {@link TileEntityMekApiary}
 * @param <CONTAINER> 蜂箱容器类型，必须继承 {@link MekanismTileContainer}
 */
public class GuiMekApiary<TILE extends TileEntityMekApiary, CONTAINER extends MekanismTileContainer<TILE>>
		extends GuiConfigurableTile<TILE, CONTAINER>
		implements CompactStackCountScreen {

	private static final int BEE_COLS = 3;
	private static final int BEE_ROWS = 1;
	private static final int OUTPUT_COLS = 3;

	private GuiFeederTab feederTab;
	private GuiPbUpgradeTab<TileEntityMekApiary> pbUpgradeTab;

	/** 蜜蜂可视化渲染器（持有实体/名称/Tooltip 三个子渲染器） */
	protected final ApiaryBeeVisualsRenderer beeVisuals = new ApiaryBeeVisualsRenderer();

	/**
	 * 蜜蜂槽几何 — 懒初始化后缓存
	 * <br/>
	 * 蜂箱尺寸在构造后即固定（子类在构造器里就设好 beeCols/beeRows），
	 * 缓存后渲染与命中判定每帧不再重算布局常量。
	 */
	private ApiaryBeeSlotGeometry beeGeometry;

	/** 输出区翻页控件布局 — 仅多页容器时创建 */
	private ApiaryOutputPageControls outputPageControls;

	public GuiMekApiary(CONTAINER container, Inventory inv, Component title) {
		super(container, inv, title);
		dynamicSlots = true;
		imageHeight = ApiaryGuiLayoutHelper.getImageHeight(BEE_ROWS, OUTPUT_COLS);
		inventoryLabelY = ApiaryGuiLayoutHelper.getInventoryLabelY(BEE_ROWS);
	}

	/** 获取蜜蜂槽位列数。子类可覆盖以适配不同规模蜂箱（如工厂版动态读取 tile 配置） */
	protected int getBeeCols() {
		return BEE_COLS;
	}

	/** 获取蜜蜂槽位行数。子类可覆盖；行数影响 GUI 高度、紧凑模式开关与 Tab 位移计算 */
	protected int getBeeRows() {
		return BEE_ROWS;
	}

	/** 获取输出槽位列数。子类可覆盖；列数影响 GUI 宽度与物品栏标签水平对齐 */
	protected int getOutputCols() {
		return OUTPUT_COLS;
	}

	/** 获取蜜蜂行高。5行+蜂箱启用紧凑模式（行高20px，不显示名称），适配 scale=4@1080p 高度限制 */
	protected int getBeeRowH() {
		return ApiaryGuiLayoutHelper.getBeeRowH(getBeeRows());
	}

	/**
	 * 蜜蜂槽几何（渲染、命中判定、背景装配共用同一份坐标换算）
	 * <br/>
	 * 懒初始化而非在构造器里建：子类的 beeCols/beeRows 字段在 super() 之后才赋值，
	 * 构造期取值会拿到 0。首次使用（addGuiElements 阶段）时尺寸已确定。
	 */
	protected final ApiaryBeeSlotGeometry beeGeometry() {
		if (beeGeometry == null) {
			beeGeometry = ApiaryBeeSlotGeometry.of(imageWidth, getBeeCols(), getBeeRows(),
					getBeeRowH(), tile.getBeeSlotCount());
		}
		return beeGeometry;
	}

	/**
	 * 添加 GUI 元素：能量条、流体槽、能量 Tab、喂食 Tab、PB 升级 Tab、蜜蜂槽位背景与翻页控件
	 * <br/>
	 * 调用父类后追加蜂箱专属元素，并下移 MEK 能量 Tab 避免与喂食 Tab 视觉冲突。
	 * 子类覆盖时应先调用 super 以保留基础元素。
	 */
	@Override
	protected void addGuiElements() {
		super.addGuiElements();

		int beeRows = getBeeRows();
		// 能量条高度：从顶部延伸至输出区底部（MEK标准布局）
		int beeBottom = ApiaryGuiLayoutHelper.getBeeBottom(beeRows);
		int outputBottom = ApiaryGuiLayoutHelper.getOutputY(beeBottom, beeRows) + ApiaryGuiLayoutHelper.getOutputH();
		int powerBarHeight = ApiaryGuiLayoutHelper.getPowerBarHeight(outputBottom);

		addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(),
				ApiaryGuiLayoutHelper.getPowerBarX(imageWidth),
				ApiaryGuiLayoutHelper.getPowerBarY(), powerBarHeight)
				.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY)));

		// Bug 5：注册蜂箱独有警告到 MEK 警告 Tab
		// WAITING_FLOWER（蜜蜂找不到有效花朵）映射到 NO_MATCHING_RECIPE（无匹配配方）
		// 不绑定到具体 GUI 元素，仅注册到 warningTracker 使其显示在 Issues Tab
		trackWarning(WarningType.NO_MATCHING_RECIPE, tile.getWarningCheck(RecipeError.NOT_ENOUGH_INPUT));

		addRenderableWidget(new GuiFluidGauge(() -> tile.getFluidTank(),
				() -> tile.getFluidTanks(null), GaugeType.SMALL, this,
				ApiaryGuiLayoutHelper.TANK_X, ApiaryGuiLayoutHelper.TANK_Y));

		addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(),
				new EnergyUsageDisplaySmoother(() -> tile.getActive()
						? tile.getEnergyContainer().getEnergyPerTick() : 0L)));

		feederTab = addRenderableWidget(new GuiFeederTab(this, tile, () -> feederTab));
		pbUpgradeTab = addRenderableWidget(new GuiPbUpgradeTab<>(this, tile, () -> pbUpgradeTab));

		// 下移 MEK 能量 Tab 至警告 Tab 下方，避免与喂食 Tab 视觉冲突
		int energyDeltaY = ApiaryGuiLayoutHelper.getEnergyTabDeltaY(beeRows);
		if (energyDeltaY != 0) {
			for (GuiEventListener child : children()) {
				if (child instanceof GuiEnergyTab energyTab) {
					energyTab.move(0, energyDeltaY);
					break;
				}
			}
		}

		addBeeSlotBackgrounds();
		addOutputPageControls();
		// AE2 输出按钮已移至 MEK 侧面配置 Tab，由 AeOutputOverlay 动态注入
	}

	/** 装配输出区翻页控件（单页容器不装配，控件布局对象也保持 null 以跳过页码绘制） */
	private void addOutputPageControls() {
		if (!(menu instanceof IPagedOutputContainer paged) || paged.getOutputPageCount() <= 1) return;
		outputPageControls = ApiaryOutputPageControls.of(imageWidth, getBeeCols(), getBeeRows(), getOutputCols());
		outputPageControls.addButtons(this, paged, menu, this::addRenderableWidget);
	}

	/**
	 * 重写 addGenericTabs — 下移红石 Tab 至 PB 升级 Tab 下方
	 * <br/>
	 * MEK 默认红石 Tab 在 y=137（右侧），与 PB 升级 Tab（y=98）无冲突，
	 * 但为保持左右两侧 Tab 布局对称，将其下移至与警告 Tab 相同 Y。
	 */
	@Override
	protected void addGenericTabs() {
		super.addGenericTabs();
		int redstoneDeltaY = ApiaryGuiLayoutHelper.getRedstoneTabDeltaY(getBeeRows());
		if (redstoneDeltaY != 0) {
			for (GuiEventListener child : children()) {
				if (child instanceof GuiRedstoneControlTab redstoneTab) {
					redstoneTab.move(0, redstoneDeltaY);
					break;
				}
			}
		}
	}

	/**
	 * 重写 addWarningTab — 下移警告 Tab 至喂食 Tab 下方
	 * <br/>
	 * MEK 默认警告 Tab 在 y=109（左侧），与喂食 Tab（y=98, 高18）重叠 7px。
	 * 动态计算目标 Y：max(物品栏Y, 喂食Tab底部+间距)，参考物品栏位置布局。
	 */
	@Override
	protected void addWarningTab(IWarningTracker warningTracker) {
		GuiWarningTab tab = addRenderableWidget(new GuiWarningTab(this, warningTracker, true));
		int warningDeltaY = ApiaryGuiLayoutHelper.getWarningTabDeltaY(getBeeRows());
		if (warningDeltaY != 0) {
			tab.move(0, warningDeltaY);
		}
	}

	/**
	 * 渲染蜜蜂槽位背景与蜂笼输入/输出槽位叠加层
	 * <br/>
	 * 遍历所有蜜蜂槽位添加 GuiSlot 背景（坐标取自 {@link #beeGeometry()}），
	 * 并在输入/输出槽位上叠加 modularbees 风格纹理。
	 * 槽位有物品时不渲染叠加纹理，避免遮挡蜜蜂笼。子类可覆盖以自定义槽位背景。
	 */
	protected void addBeeSlotBackgrounds() {
		ApiaryBeeSlotGeometry geometry = beeGeometry();
		for (int i = 0; i < geometry.getSlotCount(); i++) {
			addRenderableWidget(new GuiSlot(SlotType.NORMAL, this, geometry.slotX(i), geometry.slotY(i)));
		}
		// 蜂笼输入/输出槽：dynamicSlots=true 已由 MEK 自动渲染槽位边框（输入红框/输出蓝框）
		// 此处分别在输入槽、输出槽上叠加 modularbees 风格纹理（16×16），不重复渲染槽位边框
		// 槽位有物品时不渲染纹理，避免遮挡蜜蜂笼
		int beeCols = getBeeCols();
		int cageInX = ApiaryGuiLayoutHelper.getCageInX(imageWidth, beeCols);
		int cageOutX = ApiaryGuiLayoutHelper.getCageOutX(imageWidth, beeCols);
		int cageY = ApiaryGuiLayoutHelper.getCageY(getBeeRows());
		addRenderableWidget(GuiCageSlotOverlay.input(this, cageInX, cageY, () -> tile.getCageInSlot().isEmpty()));
		addRenderableWidget(GuiCageSlotOverlay.output(this, cageOutX, cageY, () -> tile.getCageOutSlot().isEmpty()));
	}

	/**
	 * 绘制前景文本与蜜蜂可视化
	 * <br/>
	 * 依次渲染标题、物品栏标签、输出页码、蜜蜂可视化（高亮/模型/状态灯/名称），再调用父类。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标
	 * @param mouseY      鼠标 Y 坐标
	 */
	@Override
	protected void drawForegroundText(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		renderTitleText(guiGraphics);
		renderInventoryText(guiGraphics);
		if (outputPageControls != null && menu instanceof IPagedOutputContainer paged) {
			outputPageControls.renderPageText(guiGraphics, font, paged);
		}
		renderBeeVisuals(guiGraphics, mouseX, mouseY);
		super.drawForegroundText(guiGraphics, mouseX, mouseY);
	}

	/**
	 * 渲染蜜蜂可视化内容（选中高亮、蜜蜂模型、状态灯、名称）
	 * <br/>
	 * 在 drawForegroundText 阶段调用，使用局部坐标（已被父类 translate）。
	 * 紧凑模式（5行及以上蜂箱）跳过名称渲染以节省垂直空间。
	 * 保留为 protected 扩展点，子类可覆盖以扩展蜜蜂可视化渲染。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标（保留供子类覆盖使用）
	 * @param mouseY      鼠标 Y 坐标（保留供子类覆盖使用）
	 */
	protected void renderBeeVisuals(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		boolean compactMode = getBeeRows() >= ApiaryGuiLayoutHelper.COMPACT_MODE_THRESHOLD;
		beeVisuals.render(guiGraphics, tile, beeGeometry(), font(), compactMode);
	}

	/**
	 * 蜜蜂槽位点击处理 — 左键选中 + 右键桶式操作/喂食
	 * <br/>
	 * 命中判定与具体动作分别委托 {@link ApiaryBeeSlotGeometry} 与
	 * {@link ApiaryBeeSlotInteraction}，本方法只做"该不该拦这次点击"的编排。
	 * <p>
	 * Bug 修复保留：本方法在 super.mouseClicked 之前执行蜜蜂选择逻辑，而 super 才负责
	 * 将点击派发给窗口。当喂食器/升级窗口覆盖在蜜蜂格子上时，必须先判断点击是否落在
	 * 已打开窗口内，否则拖动窗口会误选蜜蜂。
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (isClickOnOpenWindow(mouseX, mouseY)) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		int clickedSlot = beeGeometry().hitTest(leftPos, topPos, mouseX, mouseY);
		if (clickedSlot >= 0) {
			if (button == 0 && ApiaryBeeSlotInteraction.handleSelect(tile, clickedSlot)) {
				return true;
			}
			if (button == 1 && ApiaryBeeSlotInteraction.handleRightClick(
					tile, getMenu().getCarried(), clickedSlot)) {
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/**
	 * 检查点击坐标是否落在任意已打开的MEK窗口内
	 * <br/>
	 * 窗口（喂食器窗口、升级窗口）作为浮层覆盖在蜜蜂格子上方，
	 * 点击应优先由窗口处理而非触发底层的蜜蜂选择逻辑。
	 * 使用MEK的 {@link GuiWindow#isMouseOver} 检查窗口bounds（含子元素）。
	 *
	 * @return true 表示点击在某个窗口内，应跳过蜜蜂选择
	 */
	private boolean isClickOnOpenWindow(double mouseX, double mouseY) {
		for (GuiWindow window : getWindows()) {
			if (window.isMouseOver(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 渲染 Tooltip — 优先显示蜜蜂 Tooltip，未悬停蜜蜂时回退到父类默认行为
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标
	 * @param mouseY      鼠标 Y 坐标
	 */
	@Override
	protected void renderTooltip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (renderBeeTooltipIfHovered(guiGraphics, mouseX, mouseY)) {
			return;
		}
		super.renderTooltip(guiGraphics, mouseX, mouseY);
	}

	/**
	 * 渲染鼠标悬停蜜蜂槽位的 Tooltip
	 * <br/>
	 * 委托 {@link ApiaryBeeVisualsRenderer}；保留为 protected 扩展点供子类覆盖。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标（屏幕坐标）
	 * @param mouseY      鼠标 Y 坐标（屏幕坐标）
	 * @return true 表示鼠标悬停在蜜蜂槽位上并已渲染 Tooltip；false 表示未悬停
	 */
	protected boolean renderBeeTooltipIfHovered(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		return beeVisuals.renderTooltipIfHovered(guiGraphics, tile, beeGeometry(),
				mouseX, mouseY, leftPos, topPos);
	}

	/**
	 * 悬停物品 Tooltip — 已禁用的喂食槽追加禁用说明
	 * <br/>
	 * 挂在原版 Tooltip 通道（而非 MEK widget tooltip）避免两个浮窗叠加渲染，
	 * 具体判定委托 {@link FeederSlotTooltipDecorator}（SRP）。
	 */
	@NotNull
	@Override
	protected List<Component> getTooltipFromContainerItem(@NotNull ItemStack stack) {
		return FeederSlotTooltipDecorator.decorate(super.getTooltipFromContainerItem(stack),
				tile, menu, getSlotUnderMouse());
	}

	@Override
	public void removed() {
		super.removed();
		beeVisuals.clearCaches();
	}
}
