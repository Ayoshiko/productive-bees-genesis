package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.IFeederSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
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
	 *   <li>标题栏：固定按钮(Pin) + 翻页按钮(◀▶) + 关闭按钮 + 标题文字</li>
	 *   <li>底部：使用提示文字</li>
	 *   <li>动态尺寸：根据喂食槽列数自适应窗口大小</li>
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

		// 翻页按钮（仅 paginated 时添加，位于标题栏右侧）
		if (paginated) {
			int prevX = relativeX + FeederWindowLayoutSupport.titleButtonX(width, 2);
			int nextX = relativeX + FeederWindowLayoutSupport.titleButtonX(width, 1);
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

		// 转化功能开关（标题栏最右侧；paginated 时排在两个翻页按钮之外，索引 0 恒为最右）
		addChild(new FeederConversionButton(gui(),
				relativeX + FeederWindowLayoutSupport.titleButtonX(width, 0),
				relativeY + FeederWindowLayoutSupport.PAGE_BTN_Y_OFFSET,
				FeederWindowLayoutSupport.PAGE_BTN_WIDTH, FeederWindowLayoutSupport.PAGE_BTN_HEIGHT,
				tile));

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
	 * 标题右侧内边距 — 为转化开关（+ 可选两个翻页按钮）让位
	 * <br/>
	 * MEK 的 drawTitleText 把标题居中于 [padStart, xSize - padEnd] 区间，
	 * 不让位时长标题会压到标题栏右侧按钮上。
	 */
	@Override
	protected int getTitlePadEnd() {
		return FeederWindowLayoutSupport.titleButtonsReservedWidth(paginated ? 3 : 1);
	}

	/**
	 * 构建喂食槽位 GuiVirtualSlot 元素
	 * <br/>
	 * 从容器获取 VirtualInventoryContainerSlot 列表，按当前 currentPage 只构建可见页的槽位。
	 * 每个槽位映射到 (currentPage * slotsPerPage + i) 索引的容器槽位。
	 * 翻页时通过 {@link #rebuildSlotElements()} 移除旧槽位并重建。
	 * 最后一页可能包含空槽位（保持严格矩形 5×6=30 布局）。
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
			GuiVirtualSlot slot = new GuiVirtualSlot(this, SlotType.NORMAL, gui(),
					relativeX + slotX, relativeY + slotY, virtualSlots.get(containerIdx));
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

		// 底部提示 — 随转化开关切换文案：开启后原料会被消耗，避免与"物品不会被消耗"矛盾
		Component hint = Component.translatable(tile.isFeederConversionEnabled()
				? "gui.productivebeesgenesis.feeder_window.hint_conversion"
				: "gui.productivebeesgenesis.feeder_window.hint");
		int hintWidth = font().width(hint);
		int hintX = relativeX + (width - hintWidth) / 2;
		int hintY = relativeY + height - FeederWindowLayoutSupport.HINT_HEIGHT + 2;
		guiGraphics.drawString(font(), hint, hintX, hintY, 0xFF808080, false);
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

		// 统计已填充槽位
		int filledSlots = 0;
		List<ItemStack> flowerTypes = new ArrayList<>();
		var feederSlots = tile.getFeederSlots();
		for (var slot : feederSlots) {
			ItemStack stack = slot.getStack();
			if (!stack.isEmpty()) {
				filledSlots++;
				boolean found = false;
				for (ItemStack existing : flowerTypes) {
					if (ItemStack.isSameItemSameComponents(existing, stack)) {
						found = true;
						break;
					}
				}
				if (!found) {
					flowerTypes.add(stack);
				}
			}
		}

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

		// 状态
		textY += 8;
		boolean isActive = filledSlots > 0;
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

		// 花朵类型列表
		textY += 10;
		Component flowersTitle = Component.translatable("gui.productivebeesgenesis.feeder_window.flowers_title");
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
