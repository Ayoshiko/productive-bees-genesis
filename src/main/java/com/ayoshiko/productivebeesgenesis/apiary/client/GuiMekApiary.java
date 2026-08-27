package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.IPagedOutputContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.client.screen.CompactStackCountScreen;
import com.ayoshiko.productivebeesgenesis.client.screen.EnergyUsageDisplaySmoother;
import com.ayoshiko.productivebeesgenesis.network.ApiaryCageOperationPayload;
import com.ayoshiko.productivebeesgenesis.network.ApiaryFeedBeePayload;
import com.ayoshiko.productivebeesgenesis.network.ApiarySelectBeePayload;
import cy.jdkdigital.productivebees.common.item.HoneyTreat;
import cy.jdkdigital.productivebees.init.ModItems;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
	 * MEK 蜂箱 GUI 渲染主类
	 * <br/>
	 * 基于 Mekanism 的 {@link GuiConfigurableTile}，负责 MEK 蜂箱的客户端 GUI 渲染职责：
	 * <ul>
	 *   <li>蜜蜂实体展示：通过 {@link BeeEntityRenderer} 在槽位内渲染蜜蜂模型与状态灯</li>
	 *   <li>蜜蜂名称展示：通过 {@link BeeNameRenderer} 渲染蜜蜂显示名（紧凑模式跳过）</li>
	 *   <li>蜜蜂 Tooltip：通过 {@link BeeTooltipRenderer} 在鼠标悬停时显示蜜蜂信息</li>
	 *   <li>Tab 管理：喂食 Tab（{@link GuiFeederTab}）、PB 升级 Tab（{@link GuiPbUpgradeTab}）、
	 *       能量 Tab、红石 Tab、警告 Tab 的布局创建与位移调整</li>
	 *   <li>槽位交互：左键选中蜜蜂槽位、右键桶式蜂笼操作（取出/放入）</li>
	 * </ul>
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

	protected BeeEntityRenderer beeEntityRenderer;
	protected BeeNameRenderer beeNameRenderer;
	protected BeeTooltipRenderer beeTooltipRenderer;

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
	 * 添加 GUI 元素：能量条、流体槽、能量 Tab、喂食 Tab、PB 升级 Tab、蜜蜂槽位背景与渲染器
	 * <br/>
	 * 调用父类后追加蜂箱专属元素，并下移 MEK 能量 Tab 避免与喂食 Tab 视觉冲突。
	 * 子类覆盖时应先调用 super 以保留基础元素。
	 */
	@Override
	protected void addGuiElements() {
		super.addGuiElements();

		int imgW = imageWidth;
		int beeRows = getBeeRows();

		// 能量条高度：从顶部延伸至输出区底部（MEK标准布局）
		int beeBottom = ApiaryGuiLayoutHelper.getBeeBottom(beeRows);
		int outputBottom = ApiaryGuiLayoutHelper.getOutputY(beeBottom, beeRows) + ApiaryGuiLayoutHelper.getOutputH();
		int powerBarHeight = ApiaryGuiLayoutHelper.getPowerBarHeight(outputBottom);

		addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(),
				ApiaryGuiLayoutHelper.getPowerBarX(imgW),
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
		int energyDeltaY = ApiaryGuiLayoutHelper.getEnergyTabDeltaY(getBeeRows());
		if (energyDeltaY != 0) {
			for (GuiEventListener child : children()) {
				if (child instanceof GuiEnergyTab energyTab) {
					energyTab.move(0, energyDeltaY);
					break;
				}
			}
		}

		addBeeSlotBackgrounds();

		beeEntityRenderer = new BeeEntityRenderer();
		beeNameRenderer = new BeeNameRenderer();
		beeTooltipRenderer = new BeeTooltipRenderer();
		addOutputPageControls();
		// AE2 输出按钮已移至 MEK 侧面配置 Tab，由 AeOutputOverlay 动态注入
	}

	private void addOutputPageControls() {
		if (!(menu instanceof IPagedOutputContainer paged) || paged.getOutputPageCount() <= 1) return;
		int outputX = ApiaryGuiLayoutHelper.getOutputX(
				ApiaryGuiLayoutHelper.getBeeX(imageWidth, getBeeCols()),
				ApiaryGuiLayoutHelper.getBeeW(getBeeCols()),
				ApiaryGuiLayoutHelper.getOutputW(getOutputCols()));
		int outputBottom = ApiaryGuiLayoutHelper.getOutputY(
				ApiaryGuiLayoutHelper.getBeeBottom(getBeeRows()), getBeeRows())
				+ ApiaryGuiLayoutHelper.getOutputH();
		int outputWidth = ApiaryGuiLayoutHelper.getOutputW(getOutputCols());
		int buttonY = ApiaryGuiLayoutHelper.getOutputPageButtonY(outputBottom);
		FeederPageButton previous = new FeederPageButton(this,
				ApiaryGuiLayoutHelper.getOutputPagePreviousButtonX(outputX, outputWidth), buttonY,
				12, 12, "\u25C0", () -> changeOutputPage(paged, -1, IPagedOutputContainer.PREVIOUS_OUTPUT_PAGE_BUTTON));
		previous.setTooltip(Tooltip.create(Component.translatable(
				"gui.productivebeesgenesis.output_page.prev.tooltip")));
		FeederPageButton next = new FeederPageButton(this,
				ApiaryGuiLayoutHelper.getOutputPageNextButtonX(outputX, outputWidth), buttonY,
				12, 12, "\u25B6", () -> changeOutputPage(paged, 1, IPagedOutputContainer.NEXT_OUTPUT_PAGE_BUTTON));
		next.setTooltip(Tooltip.create(Component.translatable(
				"gui.productivebeesgenesis.output_page.next.tooltip")));
		addRenderableWidget(previous);
		addRenderableWidget(next);
	}

	private void changeOutputPage(IPagedOutputContainer paged, int delta, int buttonId) {
		paged.changeOutputPage(delta);
		if (minecraft.gameMode != null) {
			minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
		}
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
	 * 遍历所有蜜蜂槽位添加 GuiSlot 背景，并在输入/输出槽位上叠加 modularbees 风格纹理。
	 * 槽位有物品时不渲染叠加纹理，避免遮挡蜜蜂笼。子类可覆盖以自定义槽位背景。
	 */
	protected void addBeeSlotBackgrounds() {
		int imgW = imageWidth;
		int beeCols = getBeeCols();
		int beeRows = getBeeRows();
		int beeRowH = getBeeRowH();
		int beeX = ApiaryGuiLayoutHelper.getBeeX(imgW, beeCols);
		int beeY = ApiaryGuiLayoutHelper.getBeeY(beeRows);
		int beeSlotCount = tile.getBeeSlotCount();
		for (int i = 0; i < beeSlotCount; i++) {
			int col = i % beeCols;
			int row = i / beeCols;
			int slotX = beeX + col * (ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP);
			int slotY = beeY + row * beeRowH;
			addRenderableWidget(new GuiSlot(SlotType.NORMAL, this, slotX, slotY));
		}
		// 蜂笼输入/输出槽：dynamicSlots=true 已由 MEK 自动渲染槽位边框（输入红框/输出蓝框）
		// 此处分别在输入槽、输出槽上叠加 modularbees 风格纹理（16×16），不重复渲染槽位边框
		// 槽位有物品时不渲染纹理，避免遮挡蜜蜂笼
		int cageInX = ApiaryGuiLayoutHelper.getCageInX(imgW, beeCols);
		int cageOutX = ApiaryGuiLayoutHelper.getCageOutX(imgW, beeCols);
		int cageY = ApiaryGuiLayoutHelper.getCageY(beeRows);
		addRenderableWidget(GuiCageSlotOverlay.input(this, cageInX, cageY, () -> tile.getCageInSlot().isEmpty()));
		addRenderableWidget(GuiCageSlotOverlay.output(this, cageOutX, cageY, () -> tile.getCageOutSlot().isEmpty()));
	}

	/**
	 * 绘制前景文本与蜜蜂可视化
	 * <br/>
	 * 依次渲染标题、物品栏标签、蜜蜂可视化（高亮/模型/状态灯/名称），再调用父类。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标
	 * @param mouseY      鼠标 Y 坐标
	 */
	@Override
	protected void drawForegroundText(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		renderTitleText(guiGraphics);
		renderInventoryText(guiGraphics);
		if (menu instanceof IPagedOutputContainer paged && paged.getOutputPageCount() > 1) {
			int outputX = ApiaryGuiLayoutHelper.getOutputX(
					ApiaryGuiLayoutHelper.getBeeX(imageWidth, getBeeCols()),
					ApiaryGuiLayoutHelper.getBeeW(getBeeCols()),
					ApiaryGuiLayoutHelper.getOutputW(getOutputCols()));
			int outputWidth = ApiaryGuiLayoutHelper.getOutputW(getOutputCols());
			int outputBottom = ApiaryGuiLayoutHelper.getOutputY(
					ApiaryGuiLayoutHelper.getBeeBottom(getBeeRows()), getBeeRows())
					+ ApiaryGuiLayoutHelper.getOutputH();
			String pageText = (paged.getOutputPage() + 1) + "/" + paged.getOutputPageCount();
			int textX = outputX + (outputWidth - font.width(pageText)) / 2;
			guiGraphics.drawString(font, pageText, textX,
					ApiaryGuiLayoutHelper.getOutputPageButtonY(outputBottom) + 2, 0x404040, false);
		}
		renderBeeVisuals(guiGraphics, mouseX, mouseY);
		super.drawForegroundText(guiGraphics, mouseX, mouseY);
	}

	/**
	 * 渲染蜜蜂可视化内容（选中高亮、蜜蜂模型、状态灯、名称）
	 * <br/>
	 * 在 drawForegroundText 阶段调用，使用局部坐标（已被父类 translate）。
	 * 紧凑模式（5行及以上蜂箱）跳过名称渲染以节省垂直空间。
	 * 子类可覆盖以扩展蜜蜂可视化渲染。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标（保留供子类覆盖使用）
	 * @param mouseY      鼠标 Y 坐标（保留供子类覆盖使用）
	 */
	protected void renderBeeVisuals(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		int imgW = imageWidth;
		int beeCols = getBeeCols();
		int beeRows = getBeeRows();
		int beeRowH = getBeeRowH();
		boolean compactMode = beeRows >= ApiaryGuiLayoutHelper.COMPACT_MODE_THRESHOLD;
		int beeX = ApiaryGuiLayoutHelper.getBeeX(imgW, beeCols);
		int beeY = ApiaryGuiLayoutHelper.getBeeY(beeRows);
		float partialTick = mekanism.client.render.MekanismRenderer.getPartialTick();

		// Bug 9：渲染选中槽位高亮边框（在蜜蜂下方渲染，避免遮挡蜜蜂模型）
		int selectedSlot = tile.getClientSelectedBeeSlot();
		if (selectedSlot >= 0 && selectedSlot < tile.getBeeSlotCount()) {
			int selCol = selectedSlot % beeCols;
			int selRow = selectedSlot / beeCols;
			int selX = beeX + selCol * (ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP) - 1;
			int selY = beeY + selRow * beeRowH - 1;
			// 青色边框高亮，与正常槽位形成对比（GuiGraphics.fill 自动管理 blend 状态）
			guiGraphics.fill(selX, selY,
					selX + ApiaryGuiLayoutHelper.SLOT + 2, selY + ApiaryGuiLayoutHelper.SLOT + 2,
					0x40FF00A0);
		}

		BeeSlot[] beeSlots = tile.getBeeSlots();
		// 先提交整批实体渲染，再绘制状态灯和名称，避免每只蜜蜂都 endBatch() 并切换深度测试。
		boolean batchStarted = false;
		try {
			for (int i = 0; i < beeSlots.length; i++) {
				BeeSlot beeSlot = beeSlots[i];
				if (beeSlot.isEmpty()) continue;

				int col = i % beeCols;
				int row = i / beeCols;
				int slotX = beeX + col * (ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP);
				int slotY = beeY + row * beeRowH;
				if (!batchStarted) {
					beeEntityRenderer.beginBatch();
					batchStarted = true;
				}
				beeEntityRenderer.renderBee(guiGraphics, slotX, slotY, beeSlot, partialTick);
			}
		} finally {
			if (batchStarted) {
				beeEntityRenderer.endBatch();
			}
		}

		for (int i = 0; i < beeSlots.length; i++) {
			int col = i % beeCols;
			int row = i / beeCols;
			// Bug 1修复：drawForegroundText 的 PoseStack 已被父类 translate(leftPos, topPos)，
			// 此处必须使用局部坐标，否则蜜蜂/状态灯/名字会偏移到 GUI 右下角
			int slotX = beeX + col * (ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP);
			int slotY = beeY + row * beeRowH;

			BeeSlot beeSlot = beeSlots[i];
			if (beeSlot.isEmpty()) continue;

			beeEntityRenderer.renderStatusLight(guiGraphics, slotX, slotY, beeSlot.getState());
			// 紧凑模式（5行蜂箱）不渲染名称，节省垂直空间适配scale=4@1080p
			if (!compactMode) {
				beeNameRenderer.renderName(guiGraphics, slotX, slotY, beeSlot, font(), i);
			}
		}
	}

	/**
	 * 蜜蜂槽位点击处理 — 左键选中 + 右键桶式操作
	 * <br/>
	 * 左键（button=0）：选中目标槽位（支持空格子和非空格子），再次点击同一槽位取消选择。
	 * 右键（button=1）：桶式蜂笼操作，根据光标蜂笼状态和目标格子状态决定操作类型：
	 * <ul>
	 *   <li>空蜂笼 + 有蜜蜂 → 取出蜜蜂到蜂笼</li>
	 *   <li>含蜜蜂蜂笼 + 空格子 → 放入蜜蜂到格子</li>
	 * </ul>
	 * 与"放入蜂笼自动处理"和"点击选中"机制并存，不破坏现有功能。
	 * <p>
	 * Bug修复：本方法在 super.mouseClicked 之前执行蜜蜂选择逻辑，而 super 才负责将点击派发给窗口。
	 * 当喂食器/升级窗口覆盖在蜜蜂格子上时，点击会先穿透到蜜蜂选择逻辑。
	 * 修复方式：先检查点击是否落在已打开窗口内，若是则交给 super 派发给窗口处理（拖动等），跳过蜜蜂选择。
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 点击落在已打开窗口内时，交由窗口处理，避免拖动窗口误选蜜蜂
		if (isClickOnOpenWindow(mouseX, mouseY)) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		int clickedSlot = getClickedBeeSlot(mouseX, mouseY);
		if (clickedSlot >= 0) {
			if (button == 0) {
				// 左键：选中槽位（支持空格子和非空格子）
				int newSelection = (tile.getClientSelectedBeeSlot() == clickedSlot) ? -1 : clickedSlot;
				PacketDistributor.sendToServer(new ApiarySelectBeePayload(tile.getBlockPos(), newSelection));
				return true;
			} else if (button == 1) {
				if (handleHoneyTreatFeeding(clickedSlot)) {
					return true;
				}
				// 未手持基因小食时，继续处理桶式蜂笼操作
				if (handleCageOperation(clickedSlot)) {
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/**
	 * 检查光标上的基因小食，并请求服务端喂食指定槽位内的蜜蜂。
	 *
	 * @param slotIndex 目标蜜蜂槽位索引
	 * @return 满足喂食条件并已发送请求时返回 {@code true}
	 */
	private boolean handleHoneyTreatFeeding(int slotIndex) {
		if (slotIndex < 0 || slotIndex >= tile.getBeeSlotCount()) return false;
		BeeSlot beeSlot = tile.getBeeSlots()[slotIndex];
		ItemStack cursor = getMenu().getCarried();
		if (beeSlot.isEmpty() || !(cursor.getItem() instanceof HoneyTreat) || !HoneyTreat.hasGene(cursor)) {
			return false;
		}
		PacketDistributor.sendToServer(new ApiaryFeedBeePayload(tile.getBlockPos(), slotIndex));
		return true;
	}

	/**
	 * 获取鼠标点击的蜜蜂槽位索引
	 *
	 * @return 槽位索引（0~beeSlotCount-1），-1 表示未点击任何蜜蜂槽位
	 */
	private int getClickedBeeSlot(double mouseX, double mouseY) {
		int imgW = imageWidth;
		int beeCols = getBeeCols();
		int beeRowH = getBeeRowH();
		int beeX = leftPos + ApiaryGuiLayoutHelper.getBeeX(imgW, beeCols);
		int beeY = topPos + ApiaryGuiLayoutHelper.getBeeY(getBeeRows());
		int beeSlotCount = tile.getBeeSlotCount();
		for (int i = 0; i < beeSlotCount; i++) {
			int col = i % beeCols;
			int row = i / beeCols;
			int slotX = beeX + col * (ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP);
			int slotY = beeY + row * beeRowH;
			if (mouseX >= slotX && mouseX < slotX + ApiaryGuiLayoutHelper.SLOT
					&& mouseY >= slotY && mouseY < slotY + ApiaryGuiLayoutHelper.SLOT) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 桶式蜂笼操作：检测光标蜂笼状态并发送操作包
	 * <br/>
	 * 根据光标蜂笼是否含蜜蜂 + 目标格子是否有蜜蜂，决定操作类型：
	 * <ul>
	 *   <li>空蜂笼 + 有蜜蜂 → EXTRACT（取出）</li>
	 *   <li>含蜜蜂蜂笼 + 空格子 → INSERT（放入）</li>
	 * </ul>
	 *
	 * @param slotIndex 目标蜜蜂槽位索引
	 * @return true 如果发送了操作包
	 */
	private boolean handleCageOperation(int slotIndex) {
		// 边界检查：防止 slotIndex 越界访问 beeSlots 数组
		if (slotIndex < 0 || slotIndex >= tile.getBeeSlotCount()) return false;
		ItemStack cursor = getMenu().getCarried();
		if (!isCageItem(cursor)) return false;

		BeeSlot beeSlot = tile.getBeeSlots()[slotIndex];
		boolean cursorHasBee = isFilledCage(cursor);

		ApiaryCageOperationPayload.OperationType op = null;
		if (!cursorHasBee && !beeSlot.isEmpty()) {
			// 空蜂笼 + 有蜜蜂 → 取出
			op = ApiaryCageOperationPayload.OperationType.EXTRACT;
		} else if (cursorHasBee && beeSlot.isEmpty()) {
			// 含蜜蜂蜂笼 + 空格子 → 放入
			op = ApiaryCageOperationPayload.OperationType.INSERT;
		}

		if (op != null) {
			PacketDistributor.sendToServer(new ApiaryCageOperationPayload(
					tile.getBlockPos(), slotIndex, op));
			return true;
		}
		return false;
	}

	/** 检查物品栈是否为蜂笼（普通蜂笼或坚固蜂笼） */
	private static boolean isCageItem(ItemStack stack) {
		return stack.is(ModItems.BEE_CAGE.get()) || stack.is(ModItems.STURDY_BEE_CAGE.get());
	}

	/** 检查蜂笼是否装有蜜蜂（CUSTOM_DATA 含 entity 字段） */
	private static boolean isFilledCage(ItemStack stack) {
		var data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		// copyTag() 必返回非 null CompoundTag，无需冗余 null 检查
		return data.copyTag().contains("entity");
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

	@Override
	public void removed() {
		super.removed();
		if (beeNameRenderer != null) {
			beeNameRenderer.clearCache();
		}
	}

	/**
	 * 渲染鼠标悬停蜜蜂槽位的 Tooltip
	 * <br/>
	 * 遍历所有蜜蜂槽位，若鼠标悬停在含蜜蜂的槽位上则渲染该蜜蜂的 Tooltip。
	 * 子类可覆盖以自定义 Tooltip 渲染逻辑。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param mouseX      鼠标 X 坐标（屏幕坐标）
	 * @param mouseY      鼠标 Y 坐标（屏幕坐标）
	 * @return true 表示鼠标悬停在蜜蜂槽位上并已渲染 Tooltip；false 表示未悬停
	 */
	protected boolean renderBeeTooltipIfHovered(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		int imgW = imageWidth;
		int beeCols = getBeeCols();
		int beeRowH = getBeeRowH();
		int beeX = ApiaryGuiLayoutHelper.getBeeX(imgW, beeCols);
		int beeY = ApiaryGuiLayoutHelper.getBeeY(getBeeRows());

		BeeSlot[] beeSlots = tile.getBeeSlots();
		for (int i = 0; i < beeSlots.length; i++) {
			int col = i % beeCols;
			int row = i / beeCols;
			int slotX = leftPos + beeX + col * (ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP);
			int slotY = topPos + beeY + row * beeRowH;

			BeeSlot beeSlot = beeSlots[i];
			if (beeSlot.isEmpty()) continue;

			if (mouseX >= slotX && mouseX < slotX + ApiaryGuiLayoutHelper.SLOT
					&& mouseY >= slotY && mouseY < slotY + ApiaryGuiLayoutHelper.SLOT) {
				beeTooltipRenderer.renderTooltip(guiGraphics, mouseX, mouseY, beeSlot, slotX, slotY);
				return true;
			}
		}
		return false;
	}
}
