package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionCache;
import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;
import com.ayoshiko.productivebeesgenesis.util.BeeProductModProfile;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
	 * 蜜蜂选择屏幕
	 * <br/>
	 * 显示所有已注册的 ProductiveBees 蜜蜂列表，供用户批量勾选并添加到过滤列表。
	 * <p>
	 * 功能：
	 * <ol>
	 *   <li>展示所有已注册蜜蜂（名称、类型ID、产物信息）</li>
	 *   <li>搜索框实时过滤（匹配类型ID、显示名称或产物模组ID）</li>
	 *   <li>按离心最终产物所属模组分组显示，支持折叠/展开</li>
	 *   <li>按名称/类型ID/产物模组排序</li>
	 *   <li>全选/反选当前过滤结果</li>
	 *   <li>滚动列表支持大量蜜蜂类型</li>
	 *   <li>未添加蜜蜂条目左侧复选框，点击切换选中状态</li>
	 *   <li>底部“添加选中”按钮一次性将勾选蜜蜂批量回调给父屏幕</li>
	 * </ol>
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP：仅负责蜜蜂选择，不涉及配置读写；状态管理委托给 {@link BeeSelectionState}，
	 *       排序/过滤逻辑委托给 {@link BeeSelectionSorter}</li>
	 *   <li>DIP：依赖 {@code Consumer<List<String>>} 批量回调，蜜蜂信息获取由排序器间接依赖 BeeInfoHelper</li>
	 *   <li>性能：预计算 BeeEntry 缓存，避免渲染时重复查询</li>
	 * </ul>
	 * <br/>
	 * 线程安全：客户端 GUI 单线程访问。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class BeeSelectionScreen extends Screen {

	// ========== 布局常量（包级可见，供 BeeSelectionRenderer 使用）==========
	static final int ENTRY_HEIGHT = 30;
	static final int ENTRY_SPACING = 32;
	/** 列表区域顶部 Y 坐标（下移以留出表头行，避免与搜索框重叠） */
	static final int LIST_TOP_Y = 74;
	static final int LIST_BOTTOM_MARGIN = 40;
	static final int SIDE_PADDING = 20;
	static final int SEARCH_WIDTH = 200;
	static final int SCROLL_BAR_WIDTH = 6;
	/** 顶部搜索框/按钮行的 Y 坐标 */
	static final int TOP_ROW_Y = 24;
	/** 复选框列宽度，条目左侧预留 16px 用于展示 ☐/☑ */
	static final int CHECKBOX_COLUMN_WIDTH = 16;
	/** 图标列宽度，条目左侧预留 20px 用于展示 16x16 代表物品 */
	static final int ICON_COLUMN_WIDTH = 20;
	/** 产物信息在条目内的横向偏移 */
	static final int PRODUCT_OFFSET_X = 210;
	/** 底部按钮宽度 */
	private static final int BOTTOM_BUTTON_WIDTH = 100;
	/** 底部按钮高度 */
	private static final int BOTTOM_BUTTON_HEIGHT = 20;
	/** 底部两个按钮之间的间距 */
	private static final int BOTTOM_BUTTON_GAP = 20;
	/** 顶部排序按钮宽度 */
	static final int SORT_BUTTON_WIDTH = 50;
	/** 顶部全选/反选按钮宽度 */
	static final int SELECT_BUTTON_WIDTH = 50;
	/** 顶部“仅显示未添加”切换按钮宽度 */
	static final int TOGGLE_BUTTON_WIDTH = 60;
	/** 顶部按钮高度 */
	static final int TOP_BUTTON_HEIGHT = 20;
	/** 顶部按钮之间的间距 */
	static final int TOP_BUTTON_GAP = 4;

	// ========== 状态数据 ==========
	private final Screen parent;
	/** 选择蜜蜂后的批量回调，参数为选中的蜜蜂类型ID字符串列表 */
	private final Consumer<List<String>> onSelectBees;
	/** 运行时状态（搜索、滚动、过滤、已选） */
	private final BeeSelectionState state = new BeeSelectionState();
	/** 排序与过滤逻辑处理器（组合模式） */
	private final BeeSelectionSorter sorter = new BeeSelectionSorter(this, state);
	/**
	 * 初始化标志位 — 区分首次加载与重建
	 * <p>
	 * 首次 init() 加载蜜蜂数据后置为 true，后续 rebuildWidgets() 触发的 init() 跳过 loadBeeEntries()，
	 * 保留用户的搜索状态、滚动位置及已勾选集合。
	 */
	private boolean initialized = false;

	// ========== UI 组件 ==========
	/**
	 * 已存在于过滤列表的蜜蜂类型（用于去重显示）。
	 * <p>
	 * Task 16.1: 使用 {@link HashSet} 替代 List，将 {@link #isAlreadyAdded} 的
	 * contains 查找从 O(n) 降为 O(1)，提升大量蜜蜂类型下的渲染过滤性能。
	 */
	private final Set<String> existingBeeTypes;
	/** 全选 / 反选按钮 */
	private Button selectAllButton;
	private Button invertButton;
	/** 回到顶部按钮 */
	private Button scrollToTopButton;
	/** 添加选中 按钮 */
	private Button addSelectedButton;
	/** 列表渲染辅助类 */
	private final BeeSelectionRenderer renderer = new BeeSelectionRenderer(this);
	/** 搜索框与排序按钮管理器（组合模式） */
	private final BeeSelectionSearchBar searchBar = new BeeSelectionSearchBar(this, state, sorter);
	/** 分组折叠与显示切换管理器（组合模式） */
	private final BeeSelectionGroupRenderer groupRenderer = new BeeSelectionGroupRenderer(state, sorter, renderer);
	/** 滚动条交互与渲染辅助类（组合模式） */
	private final BeeSelectionScrollBar scrollBar = new BeeSelectionScrollBar(this, state, sorter);

	/**
	 * 构造蜜蜂选择屏幕（多选模式）
	 *
	 * @param parent            父级屏幕（FilterListScreen）
	 * @param existingBeeTypes  当前已存在于过滤列表的蜜蜂类型ID（会复制一份快照）
	 * @param onSelectBees      选择蜜蜂后的批量回调，接收选中的蜜蜂类型ID列表
	 */
	public BeeSelectionScreen(Screen parent, List<String> existingBeeTypes, Consumer<List<String>> onSelectBees) {
		super(Component.translatable("productivebeesgenesis.config.bee_selection_title"));
		this.parent = parent;
		// Task 16.1: 复制为 HashSet 以获得 O(1) 查找性能（构造参数仍为 List 保持 API 兼容）
		this.existingBeeTypes = existingBeeTypes.stream()
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(HashSet::new));
		this.onSelectBees = onSelectBees;
		// 恢复上次关闭时保存的搜索与界面状态（不恢复已选项）
		BeeSelectionCache.getInstance().restore(state);
	}

	/**
	 * 创建单选兼容模式的蜜蜂选择屏幕
	 * <p>
	 * 由于 Java 类型擦除，{@code Consumer<String>} 与 {@code Consumer<List<String>>} 无法作为
	 * 构造函数重载同时存在，因此提供静态工厂方法。内部将批量回调包装为单元素列表后调用单选回调。
	 *
	 * @param parent            父级屏幕
	 * @param existingBeeTypes  当前已存在于过滤列表的蜜蜂类型ID
	 * @param onSelectBee       选择单个蜜蜂后的回调
	 * @return 配置为单选模式的选择屏幕实例
	 */
	public static BeeSelectionScreen forSingleSelection(
		Screen parent,
		List<String> existingBeeTypes,
		Consumer<String> onSelectBee
	) {
		return new BeeSelectionScreen(parent, existingBeeTypes, selected -> {
			if (selected != null && !selected.isEmpty()) {
				onSelectBee.accept(selected.get(0));
			}
		});
	}

	@Override
	protected void init() {
		super.init();

		// 仅首次初始化时加载蜜蜂数据，避免 rebuildWidgets() 触发的重建覆盖本地未保存的修改
		if (!initialized) {
			// 每次打开新界面时清空上次勾选，避免误操作
			state.clearSelection();
			sorter.loadBeeEntries();
			initialized = true;
		} else {
			sorter.recomputeFilteredEntries();
		}

		// 搜索框与排序按钮 — 由 BeeSelectionSearchBar 管理布局与创建
		searchBar.init(width);
		addRenderableWidget(searchBar.getSearchField());
		addRenderableWidget(searchBar.getSortButton());

		// 全选 / 反选 / 显示切换按钮 — 搜索框右侧依次排列，保持固定间距
		int rightBtnX = searchBar.getSearchRight() + TOP_BUTTON_GAP;
		selectAllButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.select_all"),
				button -> selectAllFiltered()
		).bounds(rightBtnX, TOP_ROW_Y, SELECT_BUTTON_WIDTH, TOP_BUTTON_HEIGHT).build();
		addRenderableWidget(selectAllButton);

		rightBtnX += SELECT_BUTTON_WIDTH + TOP_BUTTON_GAP;
		invertButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.invert_selection"),
				button -> invertFiltered()
		).bounds(rightBtnX, TOP_ROW_Y, SELECT_BUTTON_WIDTH, TOP_BUTTON_HEIGHT).build();
		addRenderableWidget(invertButton);

		rightBtnX += SELECT_BUTTON_WIDTH + TOP_BUTTON_GAP;
		addRenderableWidget(groupRenderer.createToggleButton(rightBtnX, TOP_ROW_Y, width - SIDE_PADDING - rightBtnX));

		// 自动聚焦搜索框，方便用户立即输入
		setFocused(searchBar.getSearchField());

		// 底部按钮栏 — 添加选中 + 返回
		int bottomY = height - 28;
		int totalButtonWidth = BOTTOM_BUTTON_WIDTH * 3 + BOTTOM_BUTTON_GAP * 2;
		int startX = width / 2 - totalButtonWidth / 2;

		addSelectedButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.add_selected"),
				button -> confirmSelection()
		).bounds(startX, bottomY, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT).build();
		addSelectedButton.active = state.hasSelection();
		addRenderableWidget(addSelectedButton);

		addRenderableWidget(Button.builder(
				Component.translatable("gui.back"),
				button -> onClose()
		).bounds(startX + BOTTOM_BUTTON_WIDTH + BOTTOM_BUTTON_GAP, bottomY,
				BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT).build());

		scrollToTopButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.scroll_to_top"),
				button -> state.resetScroll()
		).bounds(startX + BOTTOM_BUTTON_WIDTH * 2 + BOTTOM_BUTTON_GAP * 2, bottomY,
				BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT).build();
		scrollToTopButton.active = state.getScrollOffset() > 0;
		addRenderableWidget(scrollToTopButton);
	}

	/** 判断指定蜜蜂是否已在过滤列表中 */
	boolean isAlreadyAdded(BeeEntry entry) {
		return existingBeeTypes.contains(entry.typeId);
	}

	/**
	 * 全选当前过滤结果中未添加的蜜蜂。
	 */
	private void selectAllFiltered() {
		List<String> selectable = sorter.getFilteredEntries().stream()
				.filter(entry -> !isAlreadyAdded(entry))
				.map(entry -> entry.typeId)
				.distinct()
				.toList();
		state.selectAll(selectable);
		updateAddSelectedButton();
	}

	/**
	 * 反选当前过滤结果中未添加的蜜蜂。
	 */
	private void invertFiltered() {
		List<String> selectable = sorter.getFilteredEntries().stream()
				.filter(entry -> !isAlreadyAdded(entry))
				.map(entry -> entry.typeId)
				.distinct()
				.toList();
		state.invertSelection(selectable);
		updateAddSelectedButton();
	}

	private void updateAddSelectedButton() {
		if (addSelectedButton != null) {
			addSelectedButton.active = state.hasSelection();
		}
	}

	/**
	 * 确认批量选择：将所有勾选的蜜蜂通过回调返回给父屏幕，然后关闭
	 */
	private void confirmSelection() {
		if (!state.hasSelection()) {
			return;
		}
		onSelectBees.accept(state.getSelectedAsList());
		onClose();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// v9-L2 修复：复用 renderBackground 避免重复 fill 调用
		renderBackground(graphics, mouseX, mouseY, partialTick);
		// 标题
		graphics.drawCenteredString(font, this.title, width / 2, 8, GuiColors.TEXT_TITLE);

		// 列表区域背景（不透明深灰背景，四边边框，确保列表内容清晰可见）
		int listBottom = height - LIST_BOTTOM_MARGIN;
		graphics.fill(SIDE_PADDING, LIST_TOP_Y - 2, width - SIDE_PADDING, listBottom, GuiColors.BG_LIST_PANEL);
		// 上边框
		graphics.fill(SIDE_PADDING, LIST_TOP_Y - 2, width - SIDE_PADDING, LIST_TOP_Y - 1, GuiColors.BORDER_GRAY);
		// 下边框
		graphics.fill(SIDE_PADDING, listBottom - 1, width - SIDE_PADDING, listBottom, GuiColors.BORDER_GRAY);
		// 左边框
		graphics.fill(SIDE_PADDING, LIST_TOP_Y - 2, SIDE_PADDING + 1, listBottom, GuiColors.BORDER_GRAY);
		// 右边框
		graphics.fill(width - SIDE_PADDING - 1, LIST_TOP_Y - 2, width - SIDE_PADDING, listBottom, GuiColors.BORDER_GRAY);

		// 表头（与条目文字列对齐，位置下调避免高 GUI 缩放下与搜索框重叠）
		int nameHeaderX = SIDE_PADDING + 4 + CHECKBOX_COLUMN_WIDTH + ICON_COLUMN_WIDTH + 6;
		int headerLabelY = LIST_TOP_Y - 6;
		graphics.drawString(font, Component.translatable("productivebeesgenesis.config.bee_name"),
				nameHeaderX, headerLabelY, GuiColors.TEXT_WHITE);
		graphics.drawString(font, Component.translatable("productivebeesgenesis.config.bee_type_id"),
				nameHeaderX + PRODUCT_OFFSET_X, headerLabelY, GuiColors.TEXT_DIM_GRAY);

		// 裁剪并渲染列表
		graphics.enableScissor(SIDE_PADDING, LIST_TOP_Y, width - SIDE_PADDING, listBottom);
		renderer.renderDisplayList(graphics, sorter.getDisplayItems(), state.getScrollOffset(), mouseX, mouseY, state);
		graphics.disableScissor();

		// 滚动条（委托给滚动条辅助类）
		scrollBar.renderScrollBar(graphics);

		// 渲染组件（搜索框、按钮）— 因未调用 super.render() 需手动渲染 renderables
		for (var renderable : renderables) {
			renderable.render(graphics, mouseX, mouseY, partialTick);
		}
	}

	/**
	 * 重写renderBackground — 阻止Screen默认半透明dirt背景渲染
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, GuiColors.BG_SCREEN_DARK);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 优先处理搜索框和按钮
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		// 获取当前显示列表快照（同一渲染帧内复用，避免重复调用）
		List<BeeSelectionRenderer.DisplayItem> displayItems = sorter.getDisplayItems();

		// 滚动条交互（委托给滚动条辅助类）
		if (scrollBar.handleMouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		// 分组标题：点击切换折叠/展开
		if (groupRenderer.handleHeaderClick(mouseY, displayItems, state.getScrollOffset())) {
			return true;
		}

		// 蜜蜂条目：点击切换选中状态
		Integer entryIndex = renderer.getEntryIndexAt(mouseY, displayItems, state.getScrollOffset());
		if (entryIndex != null) {
			BeeSelectionRenderer.DisplayItem item = displayItems.get(entryIndex);
			if (item instanceof BeeSelectionRenderer.EntryItem entryItem && !isAlreadyAdded(entryItem.entry)) {
				toggleSelection(entryItem.entry.typeId);
			}
			return true;
		}

		return false;
	}

	/**
	 * 切换指定蜜蜂类型的选中状态
	 *
	 * @param typeId 蜜蜂类型ID
	 */
	private void toggleSelection(String typeId) {
		state.toggleSelection(typeId);
		updateAddSelectedButton();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// v9-L3 修复：仅当鼠标在列表区域内时才处理滚动，避免悬停在按钮上时误滚列表
		int listBottom = height - LIST_BOTTOM_MARGIN;
		if (mouseY < LIST_TOP_Y || mouseY > listBottom) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		if (scrollY > 0) {
			state.setScrollOffset(state.getScrollOffset() - 1);
		} else if (scrollY < 0) {
			state.setScrollOffset(state.getScrollOffset() + 1);
		}
		// 统一钳制滚动偏移：搜索过滤可能使 maxScroll 缩小，两个方向滚动后均需校验上界，避免显示空白行
		int maxScroll = Math.max(0, sorter.getDisplayItems().size() - getVisibleEntryCount());
		state.clampScrollOffset(maxScroll);
		updateScrollToTopButton();
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (scrollBar.handleMouseDragged(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	/**
	 * 更新回到顶部按钮的激活状态（包级可见，供 BeeSelectionScrollBar 调用）
	 */
	void updateScrollToTopButton() {
		if (scrollToTopButton != null) {
			scrollToTopButton.active = state.getScrollOffset() > 0;
		}
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (scrollBar.handleMouseReleased(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/**
	 * 屏幕被移除时持久化搜索与界面状态
	 */
	@Override
	public void removed() {
		BeeSelectionCache.getInstance().save(state);
		super.removed();
	}

	/**
	 * 获取可见条目数量（含分组标题）
	 */
	int getVisibleEntryCount() {
		int availableHeight = height - LIST_BOTTOM_MARGIN - LIST_TOP_Y - 4;
		return Math.max(1, availableHeight / ENTRY_SPACING);
	}

	/**
	 * 蜜蜂条目缓存
	 * <p>
	 * 预计算类型ID、显示名称、产物信息及最终产物模组，避免渲染和过滤时重复查询。
	 */
	static final class BeeEntry {
		final String typeId;
		final String typeIdLower;
		final Component displayName;
		final String displayNameLower;
		final Component productInfo;
		final String productModId;
		final String searchableProductModIdsLower;
		/** 预计算的代表物品图标，避免每帧创建 ItemStack */
		final ItemStack icon;

		BeeEntry(ResourceLocation type, Component displayName, Component productInfo,
				BeeProductModProfile productMods, ItemStack icon) {
			this.typeId = type.toString();
			this.typeIdLower = this.typeId.toLowerCase(Locale.ROOT);
			this.displayName = displayName;
			this.displayNameLower = displayName.getString().toLowerCase(Locale.ROOT);
			this.productInfo = productInfo;
			this.productModId = productMods.primaryModId();
			this.searchableProductModIdsLower = String.join(" ", productMods.allModIds())
					.toLowerCase(Locale.ROOT);
			this.icon = icon;
		}
	}
}
