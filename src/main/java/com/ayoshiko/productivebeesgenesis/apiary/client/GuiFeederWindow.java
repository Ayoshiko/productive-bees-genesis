package com.ayoshiko.productivebeesgenesis.apiary.client;

import java.util.ArrayList;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.apiary.IFeederSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

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
 *   <li>每页固定 {@value #MAX_ROWS_PER_PAGE} 行 × feederCols 列槽位（高等级 5×6=30）</li>
 *   <li>标题栏右侧 ◀/▶ 翻页按钮，鼠标滚轮也可翻页</li>
 *   <li>页码显示在右侧信息面板</li>
 *   <li>低等级（feederRows ≤ 6）不显示翻页按钮，行为与原版一致</li>
 *   <li>槽位总数 = feederRows × feederCols（严格矩形，多出空槽保持布局）</li>
 * </ul>
 * <p>
 * 布局参数（以窗口左上角为原点）：
 * <ul>
 *   <li>左侧网格区：x=6, y=18, 宽=cols×20-2, 高=visibleRows×20-2</li>
 *   <li>右侧信息面板：x=gridRight+gap, y=18, 宽=INFO_PANEL_WIDTH, 高=gridHeight</li>
 *   <li>翻页按钮：y=4, x=width-N*(btnWidth+gap)-8（标题栏右侧）</li>
 *   <li>底部提示：y=gridBottom+4</li>
 * </ul>
 */
public class GuiFeederWindow extends GuiWindow {

	/** 槽位步进（18+2=20px） */
	private static final int SLOT_PITCH = 20;

	/** 左侧内边距 */
	private static final int LEFT_PADDING = 6;

	/** 标题栏高度 */
	private static final int TITLE_HEIGHT = 18;

	/** 底部提示区域高度 */
	private static final int HINT_HEIGHT = 14;

	/** 网格与信息面板间距 */
	private static final int PANEL_GAP = 4;

	/** 右侧信息面板固定宽度 */
	private static final int INFO_PANEL_WIDTH = 59;

	/**
	 * 花朵名称最大渲染宽度（缩放前）
	 * <br/>
	 * 模块 5 修复（v2.4 最终版）：玩家实测 85px 在高级/精英等级蜂箱中仍超出边框。
	 * 虽然理论计算 55/0.55≈100px，但实际渲染因 startX 偏移、缩放像素对齐、字体度量差异
	 * 及不同等级蜂箱的窗口尺寸差异，需要更大的安全余量。改为 75px 确保所有等级
	 * （基础/高级/精英/终极）的 9 种不同花朵名称都不超出信息面板边框。
	 * <p>
	 * 理论计算：信息面板宽度 {@link #INFO_PANEL_WIDTH}=59px，扣除左右内边距各 2px 后实际可用 55px，
	 * 花朵名称使用 0.55F 缩放，等效渲染前最大宽度 = 55 / 0.55 ≈ 100px。
	 * 但 75px 渲染后 = 75 × 0.55 = 41.25px，远小于 55px，留出 13.75px 安全余量。
	 */
	private static final int MAX_FLOWER_NAME_WIDTH = 75;

	/** 省略号字符串（与 {@code BeeNameRenderer} 保持一致） */
	private static final String ELLIPSIS = "...";

	/** 固定按钮 X 偏移 */
	private static final int PIN_X_OFFSET = 16;

	/** 固定按钮 Y 偏移 */
	private static final int PIN_Y_OFFSET = 6;

	/** 单页最大行数（翻页时每页显示 6 行，5×6=30 槽） */
	private static final int MAX_ROWS_PER_PAGE = 6;

	/** 翻页按钮宽度 */
	private static final int PAGE_BTN_WIDTH = 18;

	/** 翻页按钮高度 */
	private static final int PAGE_BTN_HEIGHT = 12;

	/** 翻页按钮 Y 偏移（位于标题栏内） */
	private static final int PAGE_BTN_Y_OFFSET = 4;

	/** 翻页按钮间距 */
	private static final int PAGE_BTN_GAP = 2;

	/** 翻页按钮右侧内边距 */
	private static final int PAGE_BTN_RIGHT_MARGIN = 8;

	/** 喂食槽列数（3 或 5） */
	private final int feederCols;

	/** 喂食槽行数（数据层） */
	private final int feederRows;

	/** 喂食槽总槽位数（feederRows × feederCols） */
	private final int feederSlotCount;

	/** 当前可见行数 = min(feederRows, MAX_ROWS_PER_PAGE) */
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
				calculateHeight(tile.getFeederSlotManager().getFeederRows(), countFlowerTypes(tile)), windowData);
		this.tile = tile;
		this.feederCols = tile.getFeederSlotManager().getFeederCols();
		this.feederRows = tile.getFeederSlotManager().getFeederRows();
		this.feederSlotCount = this.feederRows * this.feederCols;
		this.visibleRows = Math.min(this.feederRows, MAX_ROWS_PER_PAGE);
		this.slotsPerPage = this.feederCols * this.visibleRows;
		this.paginated = this.feederSlotCount > this.slotsPerPage;
		this.totalPages = this.paginated ? (int) Math.ceil((double) this.feederSlotCount / this.slotsPerPage) : 1;
		this.gridWidth = this.feederCols * SLOT_PITCH - 2;
		this.gridHeight = this.visibleRows * SLOT_PITCH - 2;
		// 模块 4 修复：信息面板高度 = max(网格高度, 内容所需高度)，确保花朵名称列表不超出面板底部
		this.infoPanelHeight = Math.max(this.gridHeight, calculateInfoPanelHeight(this.feederRows, countFlowerTypes(tile)));
		this.interactionStrategy = InteractionStrategy.ALL;

		// 固定按钮（标题栏左侧）
		addChild(new GuiPinButton(gui(), relativeX + PIN_X_OFFSET, relativeY + PIN_Y_OFFSET, this));

		// 翻页按钮（仅 paginated 时添加，位于标题栏右侧）
		if (paginated) {
			int prevX = relativeX + width - 2 * (PAGE_BTN_WIDTH + PAGE_BTN_GAP) - PAGE_BTN_RIGHT_MARGIN;
			int nextX = relativeX + width - (PAGE_BTN_WIDTH + PAGE_BTN_GAP) - PAGE_BTN_RIGHT_MARGIN;
			int btnY = relativeY + PAGE_BTN_Y_OFFSET;
			PageButton prevBtn = new PageButton(gui(), prevX, btnY, PAGE_BTN_WIDTH, PAGE_BTN_HEIGHT,
					"\u25C0", () -> changePage(-1));
			prevBtn.setTooltip(Tooltip.create(Component.translatable(
					"gui.productivebeesgenesis.feeder_window.prev_page.tooltip")));
			PageButton nextBtn = new PageButton(gui(), nextX, btnY, PAGE_BTN_WIDTH, PAGE_BTN_HEIGHT,
					"\u25B6", () -> changePage(1));
			nextBtn.setTooltip(Tooltip.create(Component.translatable(
					"gui.productivebeesgenesis.feeder_window.next_page.tooltip")));
			addChild(prevBtn);
			addChild(nextBtn);
		}

		// 左侧喂食槽网格背景（GuiElementHolder）
		addChild(new GuiElementHolder(gui(), relativeX + LEFT_PADDING, relativeY + TITLE_HEIGHT,
				gridWidth, gridHeight));

		// 右侧信息面板（GuiInnerScreen）— 模块 4 修复：高度自适应内容
		int infoX = relativeX + getInfoPanelX();
		addChild(new GuiInnerScreen(gui(), infoX, relativeY + TITLE_HEIGHT,
				INFO_PANEL_WIDTH, infoPanelHeight));

		// 构建虚拟槽位
		buildFeederSlotElements();
	}

	/**
	 * 计算窗口宽度 — 供 {@link GuiFeederTab} 居中定位使用
	 * <br/>
	 * 模块 4 修复（v2.4 最终版）：暴露为 public，避免 GuiFeederTab 使用错误的窗口宽度
	 * 导致窗口定位偏右超出 GUI 右边框。原 GuiFeederTab 计算 windowWidth = cols*20+20，
	 * 而实际窗口宽度 = cols*20+75（含信息面板），差异 55px 导致居中定位错误。
	 */
	public static int calculateWidth(int cols) {
		return LEFT_PADDING + (cols * SLOT_PITCH - 2) + PANEL_GAP + INFO_PANEL_WIDTH + LEFT_PADDING;
	}

	/**
	 * 计算窗口高度 — 基于实际可见行数与信息面板内容所需高度
	 * <br/>
	 * 模块 4 修复（v2.4 最终版）：窗口高度 = 标题 + max(网格高度, 信息面板内容高度) + 底部提示。
	 * 修复前窗口高度仅由网格高度决定，基础蜂箱（3 行喂食槽，面板高 58px）放入 9 种不同花朵时，
	 * 第 5/6 个花朵名称及"+N 更多"超出信息面板底部边框。现在面板高度按内容动态计算。
	 *
	 * @param rows        喂食槽行数（数据层）
	 * @param flowerCount 已放置的不同花朵类型数
	 * @return 窗口总高度
	 */
	public static int calculateHeight(int rows, int flowerCount) {
		int visibleRows = Math.min(rows, MAX_ROWS_PER_PAGE);
		int gridHeight = visibleRows * SLOT_PITCH - 2;
		int panelHeight = calculateInfoPanelHeight(rows, flowerCount);
		return TITLE_HEIGHT + Math.max(gridHeight, panelHeight) + HINT_HEIGHT;
	}

	/**
	 * 统计喂食槽中已放置的不同花朵类型数量
	 * <br/>
	 * 按物品 + 组件（ItemStack.isSameItemSameComponents）去重，与 {@link #renderInfoPanel} 统计逻辑一致。
	 *
	 * @param tile 蜂箱方块实体
	 * @return 不同类型花朵数
	 */
	private static int countFlowerTypes(TileEntityMekApiary tile) {
		List<ItemStack> types = new ArrayList<>();
		for (var slot : tile.getFeederSlots()) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) continue;
			boolean found = false;
			for (ItemStack existing : types) {
				if (ItemStack.isSameItemSameComponents(existing, stack)) {
					found = true;
					break;
				}
			}
			if (!found) {
				types.add(stack);
			}
		}
		return types.size();
	}

	/**
	 * 计算右侧信息面板所需高度（内容自适应）
	 * <br/>
	 * 与 {@link #renderInfoPanel} 的 textY 递增逻辑保持一致：
	 * <ul>
	 *   <li>槽位使用率 + 状态 + 花朵标题 + 花朵列表首行偏移 = 36px（花朵1 的 textY）</li>
	 *   <li>花朵列表：每行 7px，最多 6 行</li>
	 *   <li>花朵超过 6 种时 "+N 更多" 额外 +7px</li>
	 *   <li>翻页时页码额外 +18px（页码标签 + 页码值）</li>
	 *   <li>底部留出文字高度 + 内边距余量（6px）</li>
	 * </ul>
	 *
	 * @param rows        喂食槽行数（用于判断是否翻页）
	 * @param flowerCount 已放置的不同花朵类型数
	 * @return 信息面板所需高度
	 */
	private static int calculateInfoPanelHeight(int rows, int flowerCount) {
		int shown = Math.min(flowerCount, 6);
		// 36 = 槽位(10) + 状态(8) + 花朵标题(10) + 花朵列表偏移(8)，即首朵花的 textY
		int contentHeight = 36 + shown * 7 + (flowerCount > 6 ? 7 : 0);
		// 翻页（rows > MAX_ROWS_PER_PAGE）时页码额外占用 18px
		if (rows > MAX_ROWS_PER_PAGE) {
			contentHeight += 18;
		}
		// 底部文字高度 + 内边距余量
		return contentHeight + 6;
	}

	/** 获取信息面板 X 坐标 */
	private int getInfoPanelX() {
		return LEFT_PADDING + gridWidth + PANEL_GAP;
	}

	/**
	 * 标题左侧内边距 — 为关闭按钮和固定按钮留出空间
	 */
	@Override
	protected int getTitlePadStart() {
		return 14 + GuiPinButton.WIDTH;
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
			int slotX = LEFT_PADDING + col * SLOT_PITCH;
			int slotY = TITLE_HEIGHT + row * SLOT_PITCH;
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

		// 底部提示
		Component hint = Component.translatable("gui.productivebeesgenesis.feeder_window.hint");
		int hintWidth = font().width(hint);
		int hintX = relativeX + (width - hintWidth) / 2;
		int hintY = relativeY + height - HINT_HEIGHT + 2;
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
		int infoY = relativeY + TITLE_HEIGHT;
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
		Component name = truncateFlowerName(font(), flower.getHoverName());
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

	/**
	 * 截断过长的花朵名称 — 模块 5 修复
	 * <br/>
	 * 信息面板宽度有限（{@link #INFO_PANEL_WIDTH}=59px），花朵名称使用 0.55F 缩放渲染，
	 * 当 9 种不同花朵填满喂食槽时，长名称（如"蓝色风信子"）会超出边框。
	 * 本方法使用 {@link net.minecraft.client.gui.Font#plainSubstrByWidth} 截断到
	 * {@link #MAX_FLOWER_NAME_WIDTH} 内并追加 "..." 省略号。
	 * <p>
	 * 实现参考 {@code BeeNameRenderer.truncateIfNeeded}，但常量针对喂食器面板调整。
	 *
	 * @param font Minecraft 字体
	 * @param name 原始花朵名称组件
	 * @return 截断后的名称组件（超长时带省略号）
	 */
	private static Component truncateFlowerName(net.minecraft.client.gui.Font font, Component name) {
		if (font.width(name) <= MAX_FLOWER_NAME_WIDTH) {
			return name;
		}
		String plainText = name.getString();
		int ellipsisWidth = font.width(ELLIPSIS);
		int maxTextWidth = MAX_FLOWER_NAME_WIDTH - ellipsisWidth;
		if (maxTextWidth <= 0) {
			return Component.literal(ELLIPSIS);
		}
		String truncated = font.plainSubstrByWidth(plainText, maxTextWidth);
		return Component.literal(truncated + ELLIPSIS);
	}

	@Override
	public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.setColor(1, 1, 1, 1);
	}

	/**
	 * 翻页按钮 — 继承 MekanismButton 复用渲染管线
	 * <br/>
	 * DEFAULT 灰色背景，显示 ◀/▶ 箭头符号，点击触发翻页回调。
	 */
	private static final class PageButton extends MekanismButton {
		PageButton(IGuiWrapper gui, int x, int y, int width, int height, String symbol, Runnable onClick) {
			super(gui, x, y, width, height, Component.literal(symbol), (e, mx, my) -> {
				onClick.run();
				return true;
			});
			setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		}

		@Override
		protected int getButtonTextColor(int mouseX, int mouseY) {
			return 0x232323;
		}

		@Override
		protected boolean displayButtonTextShadow() {
			return false;
		}
	}
}
