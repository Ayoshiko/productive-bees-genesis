package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionCache;
import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 蜜蜂选择屏幕
 * <br/>
 * 显示所有已注册的 ProductiveBees 蜜蜂列表，供用户批量勾选并添加到过滤列表。
 * <p>
 * 功能：
 * <ol>
 *   <li>展示所有已注册蜜蜂（名称、类型ID、产物信息）</li>
 *   <li>搜索框实时过滤（匹配类型ID或显示名称）</li>
 *   <li>按 namespace 分组显示，支持折叠/展开</li>
 *   <li>按名称/类型ID/模组排序</li>
 *   <li>全选/反选当前过滤结果</li>
 *   <li>滚动列表支持大量蜜蜂类型</li>
 *   <li>未添加蜜蜂条目左侧复选框，点击切换选中状态</li>
 *   <li>底部“添加选中”按钮一次性将勾选蜜蜂批量回调给父屏幕</li>
 * </ol>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP：仅负责蜜蜂选择，不涉及配置读写；状态管理委托给 {@link BeeSelectionState}</li>
 *   <li>DIP：依赖 BeeInfoHelper 抽象获取蜜蜂信息，依赖 {@code Consumer<List<String>>} 批量回调</li>
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
	private static final int SORT_BUTTON_WIDTH = 50;
	/** 顶部全选/反选按钮宽度 */
	private static final int SELECT_BUTTON_WIDTH = 50;
	/** 顶部“仅显示未添加”切换按钮宽度 */
	private static final int TOGGLE_BUTTON_WIDTH = 60;
	/** 顶部按钮高度 */
	private static final int TOP_BUTTON_HEIGHT = 20;
	/** 顶部按钮之间的间距 */
	private static final int TOP_BUTTON_GAP = 4;

	// ========== 状态数据 ==========
	private final Screen parent;
	/** 选择蜜蜂后的批量回调，参数为选中的蜜蜂类型ID字符串列表 */
	private final Consumer<List<String>> onSelectBees;
	/** 运行时状态（搜索、滚动、过滤、已选） */
	private final BeeSelectionState state = new BeeSelectionState();
	/** 所有蜜蜂条目（预计算缓存） */
	private List<BeeEntry> allEntries;
	/** 过滤后的蜜蜂条目（不含分组标题） */
	private List<BeeEntry> filteredEntries;
	/** 带分组标题的显示列表 */
	private final List<BeeSelectionRenderer.DisplayItem> displayItems = new ArrayList<>();
	/**
	 * 初始化标志位 — 区分首次加载与重建
	 * <p>
	 * 首次 init() 加载蜜蜂数据后置为 true，后续 rebuildWidgets() 触发的 init() 跳过 loadBeeEntries()，
	 * 保留用户的搜索状态、滚动位置及已勾选集合。
	 */
	private boolean initialized = false;
	/** 首次设置搜索框值时避免触发滚动重置 */
	private boolean ignoreNextSearchReset = false;

	// ========== UI 组件 ==========
	private EditBox searchField;
	/** 已存在于过滤列表的蜜蜂类型（用于去重显示） */
	private final List<String> existingBeeTypes;
	/** 显示全部 / 仅未添加 切换按钮 */
	private Button toggleButton;
	/** 排序按钮 */
	private Button sortButton;
	/** 全选 / 反选按钮 */
	private Button selectAllButton;
	private Button invertButton;
	/** 回到顶部按钮 */
	private Button scrollToTopButton;
	/** 添加选中 按钮 */
	private Button addSelectedButton;
	/** 列表渲染辅助类 */
	private final BeeSelectionRenderer renderer = new BeeSelectionRenderer(this);
	/** 是否正在拖动滚动条滑块 */
	private boolean isDraggingScrollBar = false;

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
		this.existingBeeTypes = existingBeeTypes.stream()
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
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
	public static BeeSelectionScreen forSingleSelection(Screen parent, List<String> existingBeeTypes, Consumer<String> onSelectBee) {
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
			loadBeeEntries();
			initialized = true;
		} else {
			recomputeFilteredEntries();
		}

		// 顶部按钮布局：排序按钮在搜索框左侧，全选/反选/切换在右侧
		// 当右侧空间不足时，自动缩小搜索框宽度以避免按钮重叠
		int desiredSearchWidth = SEARCH_WIDTH;
		int searchX = width / 2 - desiredSearchWidth / 2;
		int searchRight = searchX + desiredSearchWidth;
		int rightArea = width - searchRight - SIDE_PADDING;
		int requiredRight = SELECT_BUTTON_WIDTH * 2 + TOGGLE_BUTTON_WIDTH + TOP_BUTTON_GAP * 2;
		int actualSearchWidth = desiredSearchWidth;
		if (rightArea < requiredRight) {
			actualSearchWidth = Math.max(140, desiredSearchWidth - (requiredRight - rightArea));
			searchX = width / 2 - actualSearchWidth / 2;
			searchRight = searchX + actualSearchWidth;
		}

		// 搜索框 — 顶部居中
		searchField = new EditBox(font, searchX, TOP_ROW_Y, actualSearchWidth, TOP_BUTTON_HEIGHT,
				Component.translatable("productivebeesgenesis.config.search"));
		searchField.setMaxLength(64);
		searchField.setHint(Component.translatable("productivebeesgenesis.config.search_hint"));
		searchField.setResponder(this::onSearchChanged);
		// 恢复之前的搜索文本；init 期间禁止滚动重置
		ignoreNextSearchReset = true;
		searchField.setValue(state.getSearchText());
		ignoreNextSearchReset = false;
		addRenderableWidget(searchField);

		// 排序按钮 — 搜索框左侧
		sortButton = Button.builder(
				Component.translatable(getSortKey()),
				button -> cycleSortMode()
		).bounds(searchX - SORT_BUTTON_WIDTH - TOP_BUTTON_GAP, TOP_ROW_Y, SORT_BUTTON_WIDTH, TOP_BUTTON_HEIGHT).build();
		addRenderableWidget(sortButton);

		// 全选 / 反选 / 显示切换按钮 — 搜索框右侧依次排列，保持固定间距
		int rightBtnX = searchRight + TOP_BUTTON_GAP;
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
		int toggleWidth = Math.min(TOGGLE_BUTTON_WIDTH, width - SIDE_PADDING - rightBtnX);
		toggleButton = Button.builder(
				getToggleMessage(),
				button -> toggleShowOnlyUnadded()
		).bounds(rightBtnX, TOP_ROW_Y, Math.max(50, toggleWidth), TOP_BUTTON_HEIGHT).build();
		addRenderableWidget(toggleButton);

		// 自动聚焦搜索框，方便用户立即输入
		setFocused(searchField);

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

	/**
	 * 加载所有蜜蜂类型并预计算显示信息
	 * <p>
	 * 预计算产物信息可能涉及配方查询，在 init() 时一次性完成，
	 * 避免渲染每帧时重复查询，提升滚动和搜索性能。
	 */
	private void loadBeeEntries() {
		Level level = this.minecraft.level;
		List<ResourceLocation> beeTypes = BeeInfoHelper.getAllBeeTypes();
		allEntries = new ArrayList<>(beeTypes.size());
		for (ResourceLocation beeType : beeTypes) {
			Component displayName = BeeInfoHelper.getBeeDisplayName(beeType);
			Component productInfo = level != null
					? BeeInfoHelper.getBeeProductInfo(level, beeType)
					: Component.empty();
			// 预计算代表图标，避免每帧创建 ItemStack；世界为空时返回空栈不渲染
			ItemStack icon = level != null ? BeeInfoHelper.resolveBeeIcon(level, beeType) : ItemStack.EMPTY;
			allEntries.add(new BeeEntry(beeType, displayName, productInfo, icon));
		}
		recomputeFilteredEntries();
	}

	/**
	 * 搜索框内容变化回调
	 * <p>
	 * 大小写不敏感匹配类型ID或显示名称。
	 *
	 * @param text 输入文本
	 */
	private void onSearchChanged(String text) {
		state.setSearchText(text);
		if (!ignoreNextSearchReset) {
			state.resetScroll();
		}
		recomputeFilteredEntries();
	}

	/**
	 * 重新计算过滤后的列表与分组显示列表
	 * <p>
	 * 先按当前排序规则排序，再应用“仅未添加”过滤与搜索过滤，
	 * 最后按 namespace 分组并插入可折叠的组标题。
	 */
	private void recomputeFilteredEntries() {
		allEntries.sort(getSortComparator());

		String lower = state.getSearchText().toLowerCase().trim();
		Stream<BeeEntry> stream = allEntries.stream();
		if (state.isShowOnlyUnadded()) {
			stream = stream.filter(entry -> !isAlreadyAdded(entry));
		}
		if (!lower.isEmpty()) {
			stream = stream.filter(entry -> entry.typeIdLower.contains(lower)
					|| entry.displayNameLower.contains(lower));
		}
		filteredEntries = stream.collect(Collectors.toList());

		buildDisplayItems();
		state.clampScrollOffset(Math.max(0, displayItems.size() - getVisibleEntryCount()));
	}

	/**
	 * 根据当前排序规则生成比较器。
	 */
	private Comparator<BeeEntry> getSortComparator() {
		return switch (state.getSortMode()) {
			case NAME -> Comparator.comparing(entry -> entry.displayName.getString(), String.CASE_INSENSITIVE_ORDER);
			case ID -> Comparator.comparing(entry -> entry.typeId, String.CASE_INSENSITIVE_ORDER);
			case MOD -> Comparator.comparing((BeeEntry entry) -> entry.type.getNamespace(), String.CASE_INSENSITIVE_ORDER)
					.thenComparing(entry -> entry.displayName.getString(), String.CASE_INSENSITIVE_ORDER);
		};
	}

	/**
	 * 按 namespace 分组并构建带标题的显示列表。
	 * <p>
	 * 组标题按 namespace 字母顺序排列，组内按当前排序规则排序。
	 * 已折叠的分组仅保留标题。
	 */
	private void buildDisplayItems() {
		displayItems.clear();
		Map<String, List<BeeEntry>> groups = new TreeMap<>();
		for (BeeEntry entry : filteredEntries) {
			groups.computeIfAbsent(entry.type.getNamespace(), k -> new ArrayList<>()).add(entry);
		}

		Comparator<BeeEntry> comparator = getSortComparator();
		for (Map.Entry<String, List<BeeEntry>> group : groups.entrySet()) {
			String namespace = group.getKey();
			List<BeeEntry> entries = group.getValue();
			entries.sort(comparator);
			boolean collapsed = state.isGroupCollapsed(namespace);
			displayItems.add(new BeeSelectionRenderer.HeaderItem(namespace, entries.size(), collapsed));
			if (!collapsed) {
				for (BeeEntry entry : entries) {
					displayItems.add(new BeeSelectionRenderer.EntryItem(entry));
				}
			}
		}
	}

	/**
	 * 切换指定分组的折叠状态。
	 */
	private void toggleGroupCollapsed(String namespace) {
		state.toggleGroupCollapsed(namespace);
		buildDisplayItems();
		state.clampScrollOffset(Math.max(0, displayItems.size() - getVisibleEntryCount()));
	}

	/**
	 * 切换“仅显示未添加”状态，并刷新列表和按钮文本
	 */
	private void toggleShowOnlyUnadded() {
		state.toggleShowOnlyUnadded();
		toggleButton.setMessage(getToggleMessage());
		state.resetScroll();
		recomputeFilteredEntries();
	}

	/** 获取切换按钮的当前文本 */
	private Component getToggleMessage() {
		return Component.translatable(state.isShowOnlyUnadded()
				? "productivebeesgenesis.config.show_unadded"
				: "productivebeesgenesis.config.show_all");
	}

	/** 判断指定蜜蜂是否已在过滤列表中 */
	boolean isAlreadyAdded(BeeEntry entry) {
		return existingBeeTypes.contains(entry.typeId);
	}

	/**
	 * 循环切换排序规则。
	 */
	private void cycleSortMode() {
		state.cycleSortMode();
		if (sortButton != null) {
			sortButton.setMessage(Component.translatable(getSortKey()));
		}
		state.resetScroll();
		recomputeFilteredEntries();
	}

	private String getSortKey() {
		return switch (state.getSortMode()) {
			case NAME -> "productivebeesgenesis.config.sort_by_name";
			case ID -> "productivebeesgenesis.config.sort_by_id";
			case MOD -> "productivebeesgenesis.config.sort_by_mod";
		};
	}

	/**
	 * 全选当前过滤结果中未添加的蜜蜂。
	 */
	private void selectAllFiltered() {
		List<String> selectable = filteredEntries.stream()
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
		List<String> selectable = filteredEntries.stream()
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
		// 全屏纯色不透明背景，彻底消除后方世界虚化/透视对可读性的影响
		graphics.fill(0, 0, width, height, 0xFF101010);
		// 标题
		graphics.drawCenteredString(font, this.title, width / 2, 8, 0xFFFFFF);

		// 列表区域背景（不透明深灰背景，四边边框，确保列表内容清晰可见）
		int listBottom = height - LIST_BOTTOM_MARGIN;
		graphics.fill(SIDE_PADDING, LIST_TOP_Y - 2, width - SIDE_PADDING, listBottom, 0xFF1A1A1A);
		// 上边框
		graphics.fill(SIDE_PADDING, LIST_TOP_Y - 2, width - SIDE_PADDING, LIST_TOP_Y - 1, 0xFF707070);
		// 下边框
		graphics.fill(SIDE_PADDING, listBottom - 1, width - SIDE_PADDING, listBottom, 0xFF707070);
		// 左边框
		graphics.fill(SIDE_PADDING, LIST_TOP_Y - 2, SIDE_PADDING + 1, listBottom, 0xFF707070);
		// 右边框
		graphics.fill(width - SIDE_PADDING - 1, LIST_TOP_Y - 2, width - SIDE_PADDING, listBottom, 0xFF707070);

		// 表头（与条目文字列对齐，位置下调避免高 GUI 缩放下与搜索框重叠）
		int nameHeaderX = SIDE_PADDING + 4 + CHECKBOX_COLUMN_WIDTH + ICON_COLUMN_WIDTH + 6;
		int headerLabelY = LIST_TOP_Y - 6;
		graphics.drawString(font, Component.translatable("productivebeesgenesis.config.bee_name"),
				nameHeaderX, headerLabelY, 0xFFFFFFFF);
		graphics.drawString(font, Component.translatable("productivebeesgenesis.config.bee_type_id"),
				nameHeaderX + PRODUCT_OFFSET_X, headerLabelY, 0xFFB0B0B0);

		// 裁剪并渲染列表
		graphics.enableScissor(SIDE_PADDING, LIST_TOP_Y, width - SIDE_PADDING, listBottom);
		renderer.renderDisplayList(graphics, displayItems, state.getScrollOffset(), mouseX, mouseY, state);
		graphics.disableScissor();

		// 滚动条
		renderScrollBar(graphics);

		// 渲染组件（搜索框、按钮）— 手动渲染避免Screen默认renderBackground渲染半透明背景
		for (var renderable : renderables) {
			renderable.render(graphics, mouseX, mouseY, partialTick);
		}
	}

	/**
	 * 重写renderBackground — 阻止Screen默认半透明dirt背景渲染
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0xFF101010);
	}

	/**
	 * 滚动条滑块位置/高度记录
	 */
	private record ScrollBarThumb(int y, int height) {
	}

	/**
	 * 渲染滚动条
	 */
	private void renderScrollBar(GuiGraphics graphics) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) return;

		int listBottom = height - LIST_BOTTOM_MARGIN;
		int scrollX = getScrollBarX();
		graphics.fill(scrollX, LIST_TOP_Y, scrollX + SCROLL_BAR_WIDTH, listBottom, 0xFF404040);
		graphics.fill(scrollX, thumb.y, scrollX + SCROLL_BAR_WIDTH, thumb.y + thumb.height, 0xFFA0A0A0);
	}

	/**
	 * 计算当前滚动条滑块位置与高度；列表无需滚动时返回 {@code null}。
	 */
	private ScrollBarThumb calculateScrollBarThumb() {
		int total = displayItems.size();
		int visible = getVisibleEntryCount();
		if (total <= visible) {
			return null;
		}
		int listBottom = height - LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - LIST_TOP_Y;
		int thumbHeight = Math.max(20, trackHeight * visible / total);
		int maxScroll = total - visible;
		int thumbY = LIST_TOP_Y + (trackHeight - thumbHeight) * state.getScrollOffset() / Math.max(1, maxScroll);
		return new ScrollBarThumb(thumbY, thumbHeight);
	}

	/**
	 * 获取滚动条轨道左侧 X 坐标。
	 */
	private int getScrollBarX() {
		return width - SIDE_PADDING - SCROLL_BAR_WIDTH;
	}

	/**
	 * 判断鼠标是否位于滚动条轨道区域内。
	 */
	private boolean isMouseOverScrollBar(double mouseX, double mouseY) {
		return mouseX >= getScrollBarX() && mouseX < getScrollBarX() + SCROLL_BAR_WIDTH
				&& mouseY >= LIST_TOP_Y && mouseY < height - LIST_BOTTOM_MARGIN;
	}

	/**
	 * 判断鼠标是否位于滚动条滑块上。
	 */
	private boolean isMouseOverScrollBarThumb(double mouseX, double mouseY) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return false;
		}
		return mouseY >= thumb.y && mouseY < thumb.y + thumb.height;
	}

	/**
	 * 根据鼠标 Y 坐标更新滚动偏移，用于拖动滚动条。
	 */
	private void updateScrollOffsetFromMouseY(double mouseY) {
		int total = displayItems.size();
		int visible = getVisibleEntryCount();
		int maxScroll = total - visible;
		if (maxScroll <= 0) {
			return;
		}
		int listBottom = height - LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - LIST_TOP_Y;
		int thumbHeight = Math.max(20, trackHeight * visible / total);
		int available = trackHeight - thumbHeight;
		if (available <= 0) {
			return;
		}
		double relative = mouseY - LIST_TOP_Y - thumbHeight / 2.0;
		int offset = (int) Math.round(relative * maxScroll / available);
		state.setScrollOffset(offset);
		state.clampScrollOffset(maxScroll);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 优先处理搜索框和按钮
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		// 滚动条交互：左键拖动滑块，点击轨道空白处快速跳转到对应位置
		if (button == 0 && displayItems.size() > getVisibleEntryCount() && isMouseOverScrollBar(mouseX, mouseY)) {
			if (isMouseOverScrollBarThumb(mouseX, mouseY)) {
				isDraggingScrollBar = true;
			} else {
				updateScrollOffsetFromMouseY(mouseY);
			}
			return true;
		}

		// 分组标题：点击切换折叠/展开
		Integer headerIndex = renderer.getHeaderIndexAt(mouseY, displayItems, state.getScrollOffset());
		if (headerIndex != null) {
			BeeSelectionRenderer.DisplayItem item = displayItems.get(headerIndex);
			if (item instanceof BeeSelectionRenderer.HeaderItem header) {
				toggleGroupCollapsed(header.namespace);
			}
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
		if (scrollY > 0) {
			state.setScrollOffset(state.getScrollOffset() - 1);
		} else if (scrollY < 0) {
			int maxScroll = Math.max(0, displayItems.size() - getVisibleEntryCount());
			state.setScrollOffset(state.getScrollOffset() + 1);
			state.clampScrollOffset(maxScroll);
		}
		updateScrollToTopButton();
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (isDraggingScrollBar && button == 0) {
			updateScrollOffsetFromMouseY(mouseY);
			updateScrollToTopButton();
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	/**
	 * 更新回到顶部按钮的激活状态
	 */
	private void updateScrollToTopButton() {
		if (scrollToTopButton != null) {
			scrollToTopButton.active = state.getScrollOffset() > 0;
		}
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (isDraggingScrollBar && button == 0) {
			isDraggingScrollBar = false;
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
	 * 预计算类型ID、显示名称及产物信息，避免渲染和过滤时重复查询。
	 */
	static final class BeeEntry {
		final ResourceLocation type;
		final String typeId;
		final String typeIdLower;
		final Component displayName;
		final String displayNameLower;
		final Component productInfo;
		/** 预计算的代表物品图标，避免每帧创建 ItemStack */
		final ItemStack icon;

		BeeEntry(ResourceLocation type, Component displayName, Component productInfo, ItemStack icon) {
			this.type = type;
			this.typeId = type.toString();
			this.typeIdLower = this.typeId.toLowerCase();
			this.displayName = displayName;
			this.displayNameLower = displayName.getString().toLowerCase();
			this.productInfo = productInfo;
			this.icon = icon;
		}
	}
}
