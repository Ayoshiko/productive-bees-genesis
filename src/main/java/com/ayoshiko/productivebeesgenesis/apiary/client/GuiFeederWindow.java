package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.IFeederSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
	 * 喂食器窗口 — MEK标准复杂实现（5×6=30 翻页版）
	 * <br/>
	 * 1:1复刻MEK窗口设计规范：
	 * <ul>
	 *   <li>左侧：GuiElementHolder背景的喂食槽网格，支持虚拟槽位完整交互</li>
	 *   <li>右侧：GuiInnerScreen信息面板，显示槽位统计、页码和喂食器状态</li>
	 *   <li>标题栏：固定按钮(Pin) + 翻页按钮(◀▶) + 禁用模式开关(禁) + 转化开关(转) + 关闭按钮 + 标题文字</li>
	 *   <li>底部：使用提示文字</li>
	 *   <li>动态尺寸：根据喂食槽列数自适应窗口大小</li>
	 * </ul>
	 * <p>
	 * 逐格禁用（{@link FeederDisableToggleSlot}）：
	 * <ul>
	 *   <li>点标题栏「禁」进入禁用编辑模式，此后左键点击格子即切换该格禁用；不进模式时按住 Alt 左键同效</li>
	 *   <li>Shift + 点击「禁」= 一次性全禁 / 全启（还有生效格子就全禁，否则全启）</li>
	 *   <li>禁用格渲染半透明深灰遮罩，悬停 Tooltip 追加禁用说明</li>
	 *   <li>空格子点击无效（服务端亦拒绝），退出模式后左键拿取/右键分堆等原版交互完全不受影响</li>
	 * </ul>
	 * <p>
	 * 翻页机制（高等级蜂箱喂食槽位数 &gt; 30 时启用）：
	 * <ul>
	 *   <li>每页固定 {@value FeederWindowLayoutSupport#MAX_ROWS_PER_PAGE} 行 × feederCols 列槽位（高等级 5×6=30）</li>
	 *   <li>标题栏右侧 ◀/▶ 翻页按钮，鼠标滚轮也可翻页</li>
	 *   <li>页码显示在右侧信息面板</li>
	 *   <li>低等级（feederRows ≤ 6）不显示翻页按钮，行为与原版一致</li>
	 *   <li>槽位总数 = feederRows × feederCols（严格矩形，多出空槽保持布局）</li>
	 * </ul>
	 * <p>
	 * 布局参数（以窗口左上角为原点）：
	 * <ul>
	 *   <li>左侧网格区：x=6, y=18, 宽=cols×20-2, 高=visibleRows×20-2</li>
	 *   <li>右侧信息面板：x=gridRight+gap, y=18, 宽=FeederWindowLayoutSupport.INFO_PANEL_WIDTH, 高=gridHeight</li>
	 *   <li>翻页按钮：y=4, x=width-N*(btnWidth+gap)-8（标题栏右侧）</li>
	 *   <li>底部提示：y=gridBottom+4</li>
	 * </ul>
	 */
public class GuiFeederWindow extends GuiWindow {

	/** 喂食槽列数（3 或 5） */
	private final int feederCols;

	/** 喂食槽行数（数据层） */
	private final int feederRows;

	/** 喂食槽总槽位数（feederRows × feederCols） */
	private final int feederSlotCount;

	/** 当前可见行数 = min(feederRows, FeederWindowLayoutSupport.MAX_ROWS_PER_PAGE) */
	private final int visibleRows;

	/** 右侧信息面板实际高度 = max(gridHeight, 内容所需高度) — 模块 4 修复 */
	private final int infoPanelHeight;

	/** 每页槽位数 = feederCols × visibleRows */
	private final int slotsPerPage;

	/** 是否启用翻页（feederSlotCount > slotsPerPage） */
	private final boolean paginated;

	/** 总页数 */
	private final int totalPages;

	/** 网格区域宽度 */
	private final int gridWidth;

	/** 网格区域高度 */
	private final int gridHeight;

	/** 当前页码（0-based） */
	private int currentPage = 0;

	/**
	 * 禁用编辑模式（纯客户端状态）
	 * <br/>
	 * 开启后左键点击格子被解释为"切换该格禁用"而非拿取物品；关闭时按住 Alt 亦可临时触发。
	 * 该状态只影响本地点击解释，不改变任何服务端数据，故无需 tracker 同步。
	 */
	private boolean disableEditMode;

	/** 信息面板统计缓存（按喂食槽状态版本号失效，避免每帧重扫全部槽位并新建列表） */
	private final FeederStatsCache statsCache = new FeederStatsCache();

	/** 当前已添加的虚拟槽位元素列表（用于翻页时移除重建） */
	private final List<GuiVirtualSlot> slotElements = new ArrayList<>();

	/** Tile实体引用（用于查询喂食器状态） */
	private final TileEntityMekApiary tile;

	/**
	 * 构造喂食器窗口（由 GuiFeederTab.createWindow 调用）
	 *
	 * @param gui        所属 GUI 包装器
	 * @param x          窗口 X 坐标
	 * @param y          窗口 Y 坐标
	 * @param tile       方块实体（提供喂食槽数量和状态信息）
	 * @param windowData 窗口数据
	 */
	public GuiFeederWindow(IGuiWrapper gui, int x, int y, TileEntityMekApiary tile, SelectedWindowData windowData) {
		super(gui, x, y, calculateWidth(tile.getFeederSlotManager().getFeederCols()),
				calculateHeight(tile.getFeederSlotManager().getFeederRows(),
						FeederWindowLayoutSupport.countFlowerTypes(tile)),
				windowData);
		this.tile = tile;
		this.feederCols = tile.getFeederSlotManager().getFeederCols();
		this.feederRows = tile.getFeederSlotManager().getFeederRows();
		this.feederSlotCount = this.feederRows * this.feederCols;
		this.visibleRows = Math.min(this.feederRows, FeederWindowLayoutSupport.MAX_ROWS_PER_PAGE);
		this.slotsPerPage = this.feederCols * this.visibleRows;
		this.paginated = this.feederSlotCount > this.slotsPerPage;
		this.totalPages = this.paginated ? (int) Math.ceil((double) this.feederSlotCount / this.slotsPerPage) : 1;
		this.gridWidth = this.feederCols * FeederWindowLayoutSupport.SLOT_PITCH - 2;
		this.gridHeight = this.visibleRows * FeederWindowLayoutSupport.SLOT_PITCH - 2;
		// 模块 4 修复：信息面板高度 = max(网格高度, 内容所需高度)，确保花朵名称列表不超出面板底部
		this.infoPanelHeight = Math.max(this.gridHeight, FeederWindowLayoutSupport.calculateInfoPanelHeight(this.feederRows,
			FeederWindowLayoutSupport.countFlowerTypes(tile)));
		this.interactionStrategy = InteractionStrategy.ALL;

		// 固定按钮（标题栏左侧）
		addChild(new GuiPinButton(gui(), relativeX + FeederWindowLayoutSupport.PIN_X_OFFSET,
				relativeY + FeederWindowLayoutSupport.PIN_Y_OFFSET,
				this));

		// 翻页按钮（仅 paginated 时添加，位于标题栏右侧；索引 2/3 让位给转化开关与禁用模式开关）
		if (paginated) {
			int prevX = relativeX + FeederWindowLayoutSupport.titleButtonX(width, 3);
			int nextX = relativeX + FeederWindowLayoutSupport.titleButtonX(width, 2);
			int btnY = relativeY + FeederWindowLayoutSupport.PAGE_BTN_Y_OFFSET;
			FeederPageButton prevBtn = new FeederPageButton(gui(), prevX, btnY,
					FeederWindowLayoutSupport.PAGE_BTN_WIDTH, FeederWindowLayoutSupport.PAGE_BTN_HEIGHT,
					"\u25C0", () -> changePage(-1));
			prevBtn.setTooltip(Tooltip.create(Component.translatable(
					"gui.productivebeesgenesis.feeder_window.prev_page.tooltip")));
			FeederPageButton nextBtn = new FeederPageButton(gui(), nextX, btnY,
					FeederWindowLayoutSupport.PAGE_BTN_WIDTH, FeederWindowLayoutSupport.PAGE_BTN_HEIGHT,
					"\u25B6", () -> changePage(1));
			nextBtn.setTooltip(Tooltip.create(Component.translatable(
					"gui.productivebeesgenesis.feeder_window.next_page.tooltip")));
			addChild(prevBtn);
			addChild(nextBtn);
		}

		// 转化功能开关（标题栏最右侧；索引 0 恒为最右，保持原有位置不因新增按钮而漂移）
		addChild(new FeederConversionButton(gui(),
				relativeX + FeederWindowLayoutSupport.titleButtonX(width, 0),
				relativeY + FeederWindowLayoutSupport.PAGE_BTN_Y_OFFSET,
				FeederWindowLayoutSupport.PAGE_BTN_WIDTH, FeederWindowLayoutSupport.PAGE_BTN_HEIGHT,
				tile));

		// 逐格禁用编辑模式开关（索引 1，紧邻转化开关左侧；Shift + 点击 = 全禁/全启）
		addChild(new FeederDisableModeButton(gui(),
				relativeX + FeederWindowLayoutSupport.titleButtonX(width, 1),
				relativeY + FeederWindowLayoutSupport.PAGE_BTN_Y_OFFSET,
				FeederWindowLayoutSupport.PAGE_BTN_WIDTH, FeederWindowLayoutSupport.PAGE_BTN_HEIGHT,
				tile, () -> disableEditMode, () -> disableEditMode = !disableEditMode));

		// 左侧喂食槽网格背景（GuiElementHolder）
		addChild(new GuiElementHolder(gui(), relativeX + FeederWindowLayoutSupport.LEFT_PADDING,
				relativeY + FeederWindowLayoutSupport.TITLE_HEIGHT,
				gridWidth, gridHeight));

		// 右侧信息面板（GuiInnerScreen）— 模块 4 修复：高度自适应内容
		int infoX = relativeX + getInfoPanelX();
		addChild(new GuiInnerScreen(gui(), infoX, relativeY + FeederWindowLayoutSupport.TITLE_HEIGHT,
				FeederWindowLayoutSupport.INFO_PANEL_WIDTH, infoPanelHeight));

		// 构建虚拟槽位
		buildFeederSlotElements();
	}

	/** 计算窗口宽度 — 委托 {@link FeederWindowLayoutSupport}，供 {@link GuiFeederTab} 居中定位使用 */
	public static int calculateWidth(int cols) {
		return FeederWindowLayoutSupport.calculateWidth(cols);
	}

	/** 计算窗口高度 — 委托 {@link FeederWindowLayoutSupport} */
	public static int calculateHeight(int rows, int flowerCount) {
		return FeederWindowLayoutSupport.calculateHeight(rows, flowerCount);
	}

	/** 获取信息面板 X 坐标 */
	private int getInfoPanelX() {
		return FeederWindowLayoutSupport.LEFT_PADDING + gridWidth + FeederWindowLayoutSupport.PANEL_GAP;
	}

	/**
	 * 标题左侧内边距 — 为关闭按钮和固定按钮留出空间
	 */
	@Override
	protected int getTitlePadStart() {
		return 14 + GuiPinButton.WIDTH;
	}

	/**
	 * 标题右侧内边距 — 为转化开关、禁用模式开关（+ 可选两个翻页按钮）让位
	 * <br/>
	 * MEK 的 drawTitleText 把标题居中于 [padStart, xSize - padEnd] 区间，
	 * 不让位时长标题会压到标题栏右侧按钮上。
	 */
	@Override
	protected int getTitlePadEnd() {
		return FeederWindowLayoutSupport.titleButtonsReservedWidth(paginated ? 4 : 2);
	}

	/**
	 * 构建喂食槽位 GuiVirtualSlot 元素
	 * <br/>
	 * 从容器获取 VirtualInventoryContainerSlot 列表，按当前 currentPage 只构建可见页的槽位。
	 * 每个槽位映射到 (currentPage * slotsPerPage + i) 索引的容器槽位。
	 * 翻页时通过 {@link #rebuildSlotElements()} 移除旧槽位并重建。
	 * 最后一页可能包含空槽位（保持严格矩形 5×6=30 布局）。
	 * <p>
	 * 槽位实现使用 {@link FeederDisableToggleSlot}：容器索引即喂食槽索引，
	 * 直接透传给切换包与灰色遮罩查询，翻页后索引仍然正确。
	 */
	private void buildFeederSlotElements() {
		if (!(gui() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen)) {
			return;
		}
		if (!(containerScreen.getMenu() instanceof IFeederSlotContainer feederContainer)) {
			return;
		}
		List<VirtualInventoryContainerSlot> virtualSlots = feederContainer.getFeederSlots();
		if (virtualSlots == null) return;

		int startIdx = currentPage * slotsPerPage;
		for (int i = 0; i < slotsPerPage; i++) {
			int containerIdx = startIdx + i;
			if (containerIdx >= virtualSlots.size()) {
				// 超出容器槽位范围（最后一页的空槽位），不创建 GuiVirtualSlot
				break;
			}
			int col = i % feederCols;
			int row = i / feederCols;
			int slotX = FeederWindowLayoutSupport.LEFT_PADDING + col * FeederWindowLayoutSupport.SLOT_PITCH;
			int slotY = FeederWindowLayoutSupport.TITLE_HEIGHT + row * FeederWindowLayoutSupport.SLOT_PITCH;
			GuiVirtualSlot slot = new FeederDisableToggleSlot(this, gui(),
					relativeX + slotX, relativeY + slotY, virtualSlots.get(containerIdx),
					tile, containerIdx, () -> disableEditMode);
			addChild(slot);
			slotElements.add(slot);
		}
	}

	/**
	 * 翻页时移除旧槽位元素并按新 currentPage 重建
	 */
	private void rebuildSlotElements() {
		for (GuiVirtualSlot slot : slotElements) {
			children().remove(slot);
		}
		slotElements.clear();
		buildFeederSlotElements();
	}

	/**
	 * 切换页码
	 * <br/>
	 * 仅 paginated 时生效，clamp currentPage 到 [0, totalPages-1]，仅在值变化时重建槽位。
	 *
	 * @param delta 页码增量（-1 上一页 / +1 下一页）
	 */
	private void changePage(int delta) {
		if (!paginated) return;
		int newPage = currentPage + delta;
		newPage = Math.max(0, Math.min(totalPages - 1, newPage));
		if (newPage != currentPage) {
			currentPage = newPage;
			rebuildSlotElements();
		}
	}

	/**
	 * 鼠标滚轮事件处理
	 * <br/>
	 * 仅在窗口内且 paginated 时处理：scrollY &gt; 0 上一页，scrollY &lt; 0 下一页。
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (paginated && isMouseOver(mouseX, mouseY)) {
			int dir = scrollY > 0 ? -1 : 1;
			changePage(dir);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawTitleText(guiGraphics, Component.translatable("gui.productivebeesgenesis.feeder_window.title"), 5);

		// 右侧信息面板内容
		renderInfoPanel(guiGraphics);

		// 底部提示 — 禁用编辑模式优先；其次随转化开关切换文案
		// （开启转化后原料会被消耗，避免与"物品不会被消耗"矛盾）
		Component hint = Component.translatable(resolveHintKey());
		int hintWidth = font().width(hint);
		int hintX = relativeX + (width - hintWidth) / 2;
		int hintY = relativeY + height - FeederWindowLayoutSupport.HINT_HEIGHT + 2;
		guiGraphics.drawString(font(), hint, hintX, hintY, 0xFF808080, false);
	}

	/**
	 * 底部提示文案键选择
	 * <br/>
	 * 优先级：禁用编辑模式 &gt; 转化开启 &gt; 默认。模式提示优先是因为它描述的是
	 * "当前点击会做什么"，比"物品是否被消耗"更贴近玩家下一步操作。
	 */
	private String resolveHintKey() {
		if (disableEditMode) {
			return "gui.productivebeesgenesis.feeder_window.hint_disable_mode";
		}
		return tile.isFeederConversionEnabled()
				? "gui.productivebeesgenesis.feeder_window.hint_conversion"
				: "gui.productivebeesgenesis.feeder_window.hint";
	}

	/**
	 * 渲染右侧信息面板内容
	 * <br/>
	 * 显示喂食器统计信息：
	 * <ul>
	 *   <li>槽位使用率（已填充/总数）</li>
	 *   <li>当前状态（活跃/空闲）</li>
	 *   <li>页码（仅 paginated 时显示）</li>
	 *   <li>已放置的花朵类型列表</li>
	 * </ul>
	 */
	private void renderInfoPanel(GuiGraphics guiGraphics) {
		int infoX = relativeX + getInfoPanelX();
		int infoY = relativeY + FeederWindowLayoutSupport.TITLE_HEIGHT;
		int startX = infoX + 2;
		int startY = infoY + 2;

		// 统计按喂食槽状态版本号缓存：内容/禁用未变时不重扫 60 格、不重做去重、不新建列表
		statsCache.refresh(tile);
		int filledSlots = statsCache.getFilledSlots();
		int disabledSlots = statsCache.getDisabledSlots();
		List<ItemStack> flowerTypes = statsCache.getFlowerTypes();

		// 标题
		Component statsTitle = Component.translatable("gui.productivebeesgenesis.feeder_window.stats_title");
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(startX, startY, 0);
		guiGraphics.pose().scale(0.7F, 0.7F, 0.7F);
		guiGraphics.drawString(font(), statsTitle, 0, 0, screenTextColor(), false);
		guiGraphics.pose().popPose();

		// 槽位使用率
		int textY = startY + 10;
		Component slotsText = Component.translatable("gui.productivebeesgenesis.feeder_window.slots_count",
				filledSlots, feederSlotCount);
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(startX, textY, 0);
		guiGraphics.pose().scale(0.6F, 0.6F, 0.6F);
		guiGraphics.drawString(font(), slotsText, 0, 0, screenTextColor(), false);
		guiGraphics.pose().popPose();

		// 状态 — 全部格子被禁用时视为空闲（此时蜜蜂确实拿不到任何花朵）
		textY += 8;
		boolean isActive = statsCache.getActiveSlots() > 0;
		Component statusText = Component.translatable(
				isActive ? "gui.productivebeesgenesis.feeder_window.status_active"
						 : "gui.productivebeesgenesis.feeder_window.status_idle");
		int statusColor = isActive ? 0xFF4CAF50 : 0xFF9E9E9E;
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(startX, textY, 0);
		guiGraphics.pose().scale(0.6F, 0.6F, 0.6F);
		guiGraphics.drawString(font(), statusText, 0, 0, statusColor, false);
		guiGraphics.pose().popPose();

		// 页码（仅 paginated 时显示）
		if (paginated) {
			textY += 10;
			Component pageLabel = Component.translatable("gui.productivebeesgenesis.feeder_window.page");
			guiGraphics.pose().pushPose();
			guiGraphics.pose().translate(startX, textY, 0);
			guiGraphics.pose().scale(0.6F, 0.6F, 0.6F);
			guiGraphics.drawString(font(), pageLabel, 0, 0, screenTextColor(), false);
			guiGraphics.pose().popPose();

			textY += 8;
			Component pageText = Component.literal((currentPage + 1) + "/" + totalPages);
			guiGraphics.pose().pushPose();
			guiGraphics.pose().translate(startX, textY, 0);
			guiGraphics.pose().scale(0.6F, 0.6F, 0.6F);
			guiGraphics.drawString(font(), pageText, 0, 0, screenTextColor(), false);
			guiGraphics.pose().popPose();
		}

		// 花朵类型列表 — 有禁用格时标题追加禁用格数（不新增行，避免面板高度重算）
		textY += 10;
		Component flowersTitle = disabledSlots > 0
				? Component.translatable("gui.productivebeesgenesis.feeder_window.flowers_title_disabled",
						disabledSlots)
				: Component.translatable("gui.productivebeesgenesis.feeder_window.flowers_title");
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(startX, textY, 0);
		guiGraphics.pose().scale(0.6F, 0.6F, 0.6F);
		guiGraphics.drawString(font(), flowersTitle, 0, 0, screenTextColor(), false);
		guiGraphics.pose().popPose();

		textY += 8;
	int maxFlowers = Math.min(flowerTypes.size(), 6);
	for (int i = 0; i < maxFlowers; i++) {
		ItemStack flower = flowerTypes.get(i);
		// 模块 5 修复：截断过长花朵名称，避免超出信息面板边框
		Component name = FeederWindowLayoutSupport.truncateFlowerName(font(), flower.getHoverName());
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(startX, textY, 0);
		guiGraphics.pose().scale(0.55F, 0.55F, 0.55F);
		guiGraphics.drawString(font(), name, 0, 0, screenTextColor(), false);
		guiGraphics.pose().popPose();
		textY += 7;
	}

		if (flowerTypes.size() > 6) {
			Component more = Component.translatable("gui.productivebeesgenesis.feeder_window.more_flowers",
					flowerTypes.size() - 6);
			guiGraphics.pose().pushPose();
			guiGraphics.pose().translate(startX, textY, 0);
			guiGraphics.pose().scale(0.55F, 0.55F, 0.55F);
			guiGraphics.drawString(font(), more, 0, 0, 0xFF808080, false);
			guiGraphics.pose().popPose();
		}
	}

	@Override
	public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.setColor(1, 1, 1, 1);
	}

}
