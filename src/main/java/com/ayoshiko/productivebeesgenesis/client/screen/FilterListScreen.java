package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 万象创世过滤列表编辑屏幕
 * <br/>
 * 提供 GUI 界面编辑蜜蜂类型过滤列表，解决 NeoForge 默认 ConfigurationScreen
 * 不支持空列表添加项的问题。
 * <p>
 * 功能：
 * <ol>
 *   <li>过滤模式图标按钮切换（DISABLED/BLACKLIST/WHITELIST）</li>
 *   <li>蜜蜂类型列表展示（序号、复选框、类型ID、蜜蜂名称、产物信息）</li>
 *   <li>批量删除、单条删除、拖拽排序</li>
 *   <li>全选/反选当前可见条目</li>
 *   <li>输入验证（ResourceLocation 格式 + 存在性检查 + 去重）</li>
 *   <li>保存到配置文件</li>
 * </ol>
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class FilterListScreen extends Screen {

	// ========== 布局常量（包级可见，供 FilterListRenderer / 辅助类使用）==========
	/** 单个条目高度 */
	static final int ENTRY_HEIGHT = 24;
	/** 条目垂直间距（包含行间隙） */
	static final int ENTRY_SPACING = 28;
	/** 列表区域顶部 Y 坐标 */
	static final int LIST_TOP_Y = 58;
	/** 列表区域底部距屏幕底边的距离 */
	static final int LIST_BOTTOM_MARGIN = 80;
	/** 删除按钮宽度（图标按钮，避免遮挡滚动条） */
	static final int DELETE_BUTTON_WIDTH = 16;
	/** 删除按钮高度（图标按钮） */
	static final int DELETE_BUTTON_HEIGHT = 16;
	/** 滚动条宽度 */
	static final int SCROLL_BAR_WIDTH = 6;
	/** 滚动条右侧预留边距 */
	static final int SCROLL_BAR_RIGHT_MARGIN = 8;
	/** 输入框宽度（包级可见，供布局计算使用） */
	static final int INPUT_WIDTH = 180;
	/** 底部控制栏元素间距（包级可见，供 FilterListActionBar 使用） */
	static final int CONTROL_SPACING = 6;
	/** 屏幕左右边距 */
	static final int SCREEN_MARGIN = 20;
	/** 序号列宽度（缩小以靠近复选框） */
	static final int INDEX_COLUMN_WIDTH = 18;
	/** 图标列宽度 */
	static final int ICON_COLUMN_WIDTH = 20;
	/** 复选框列宽度 */
	static final int CHECKBOX_COLUMN_WIDTH = 16;
	/** 拖拽手柄列宽度 */
	static final int DRAG_HANDLE_WIDTH = 12;
	/** 右侧操作区固定宽度（仅保留删除按钮，并预留滚动条空间） */
	static final int ACTION_AREA_WIDTH = DELETE_BUTTON_WIDTH + SCROLL_BAR_RIGHT_MARGIN + SCROLL_BAR_WIDTH + 4;

	/**
	 * 计算删除按钮的 X 坐标（左边界）。
	 * <p>
	 * 删除按钮位于滚动条左侧，避免遮挡滚动条。
	 * {@link FilterListRenderer#getActionColumnX()} 复用此方法，保证操作列坐标与删除按钮位置一致。
	 *
	 * @param screenWidth 屏幕宽度
	 * @return 删除按钮左边界 X 坐标
	 */
	static int getDeleteButtonX(int screenWidth) {
		return screenWidth - SCREEN_MARGIN - SCROLL_BAR_RIGHT_MARGIN - SCROLL_BAR_WIDTH - DELETE_BUTTON_WIDTH;
	}

	// ========== 状态数据 ==========
	private final Screen parent;
	/** 本地编辑副本（用户修改后点击保存才写入配置） */
	final List<String> beeTypes = new ArrayList<>();
	/** 选择管理器（委托模式，SRP） */
	private final FilterListSelectionManager selectionManager = new FilterListSelectionManager();

	/** @return 选中的类型集合（供渲染层读取） */
	Set<String> getSelectedTypes() {
		return selectionManager.getSelectedTypes();
	}

	/** 过滤模式（包级可见，供 FilterListActionBar 读取以发送同步包） */
	ModConfig.FilterMode filterMode;
	/** 滚动偏移（以条目为单位） */
	int scrollOffset = 0;
	/**
	 * 初始化标志位 — 区分首次加载与重建
	 * <p>
	 * 解决添加/删除/拖拽条目调用 rebuildWidgets() 触发 init()，
	 * init() 再次调用 loadFromConfig() 会覆盖本地未保存的修改。
	 * 首次 init() 加载配置后置为 true，后续 rebuildWidgets() 触发的 init() 跳过 loadFromConfig()。
	 */
	private boolean initialized = false;
	/** 蜜蜂信息缓存（图标/名称/产物），委托给独立缓存类（SRP） */
	final FilterListBeeInfoCache beeInfoCache = new FilterListBeeInfoCache();
	/** 列表渲染辅助类 */
	final FilterListRenderer renderer = new FilterListRenderer(this);
	/** 拖拽与滚动条交互处理器（组合模式） */
	private final FilterListDragHandler dragHandler = new FilterListDragHandler(this);
	/** 输入框与添加验证处理器（组合模式） */
	private final FilterListInputHelper inputHelper = new FilterListInputHelper(this);
	/** 操作栏处理器（组合模式） */
	private final FilterListActionBar actionBar = new FilterListActionBar(this);

	/**
	 * 当前可见条目的删除按钮列表
	 * <p>
	 * 滚动时仅需重建这些按钮（因为回调捕获了条目索引），而非全量 {@link #rebuildWidgets()}，
	 * 避免每帧销毁并重建搜索框、模式按钮、输入框等与滚动无关的组件。
	 */
	final List<Button> entryButtons = new ArrayList<>();

	// ========== UI 组件 ==========
	/** 过滤模式选择器（组合模式，SRP） */
	private final FilterListModeSelector modeSelector = new FilterListModeSelector(this);
	private Button deleteSelectedButton;

	public FilterListScreen(Screen parent) {
		super(Component.translatable("productivebeesgenesis.config.myriad_creations_filter_title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		// 仅首次初始化时从配置加载，避免 rebuildWidgets() 触发的重建覆盖本地未保存的修改
		if (!initialized) {
			loadFromConfig();
			initialized = true;
		}
		initTopBar();
		initControlBar();
		initBottomBar();
		createEntryButtons();
	}

	/** 初始化顶部栏：全选/反选按钮、工具按钮（重置/导入/导出）、过滤模式按钮 */
	private void initTopBar() {
		int topButtonY = 24;
		int topButtonW = 58;
		addRenderableWidget(Button.builder(
				Component.translatable("productivebeesgenesis.config.select_all"),
				button -> selectAllVisible()
		).bounds(SCREEN_MARGIN, topButtonY, topButtonW, 20).build());

		addRenderableWidget(Button.builder(
				Component.translatable("productivebeesgenesis.config.invert_selection"),
				button -> invertVisible()
		).bounds(SCREEN_MARGIN + topButtonW + 4, topButtonY, topButtonW, 20).build());

		// 重置 / 导入 / 导出小工具按钮（委托给操作栏辅助类）
		actionBar.initUtilityButtons(topButtonY, SCREEN_MARGIN + 2 * (topButtonW + 4));

		// 过滤模式图标按钮组（委托给模式选择器）
		modeSelector.createModeButtons(topButtonY);
	}

	/** 初始化中部控制栏：添加/删除选中/输入框（输入框委托给输入辅助类） */
	private void initControlBar() {
		int bottomY = height - LIST_BOTTOM_MARGIN + 10;
		int addButtonW = 50;
		int deleteSelectedW = 66;
		int confirmW = 40;
		int cancelW = 40;
		int maxBarWidth = width - 40;
		int fixedWidth = addButtonW + deleteSelectedW + confirmW + cancelW + 4 * CONTROL_SPACING;
		int inputW = Math.min(INPUT_WIDTH, Math.max(80, maxBarWidth - fixedWidth));
		int totalControlWidth = fixedWidth + inputW;
		int controlStartX = width / 2 - totalControlWidth / 2;

		// 添加按钮
		addRenderableWidget(Button.builder(
				Component.translatable("productivebeesgenesis.config.add"),
				button -> inputHelper.toggleInputVisibility(true)
		).bounds(controlStartX, bottomY, addButtonW, 20).build());

		// 删除选中按钮
		int deleteSelectedX = controlStartX + addButtonW + CONTROL_SPACING;
		deleteSelectedButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.delete_selected"),
				button -> deleteSelected()
		).bounds(deleteSelectedX, bottomY, deleteSelectedW, 20).build();
		deleteSelectedButton.active = selectionManager.hasSelection();
		addRenderableWidget(deleteSelectedButton);

		// 输入框、确认、取消按钮（委托给输入辅助类，坐标在此计算保证布局连贯）
		int inputX = deleteSelectedX + deleteSelectedW + CONTROL_SPACING;
		int confirmX = inputX + inputW + CONTROL_SPACING;
		int cancelX = confirmX + confirmW + CONTROL_SPACING;
		inputHelper.initInputField(bottomY, inputX, inputW, confirmX, confirmW, cancelX, cancelW);
	}

	/** 初始化底部操作栏：保存/从列表选择/返回（委托给操作栏辅助类） */
	private void initBottomBar() {
		actionBar.initBottomBar(height - 28);
	}

	/**
	 * 执行实际的重置操作（清空列表、重置模式）。
	 * <p>
	 * 仅修改本地状态数据，不调用 rebuildWidgets() — 由 {@link FilterListActionBar} 的
	 * resetToDefault() 中的 setScreen(this) 触发 init() 完成组件重建。
	 * 包级可见，供 FilterListActionBar 回调。
	 */
	void performResetToDefault() {
		beeTypes.clear();
		selectionManager.clear();
		filterMode = ModConfig.FilterMode.DISABLED;
		clampScrollOffset();
	}

	private void loadFromConfig() {
		beeTypes.clear();
		selectionManager.clear();
		try {
			if (ModConfig.SERVER_SPEC.isLoaded()) {
				beeTypes.addAll(ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get());
				filterMode = ModConfig.SERVER.myriadCreationsFilterMode.get();
			} else {
				filterMode = ModConfig.FilterMode.DISABLED;
			}
		} catch (IllegalStateException e) {
			ProductiveBeesGenesis.LOGGER.warn("加载服务端过滤配置失败，回退到 DISABLED", e);
			filterMode = ModConfig.FilterMode.DISABLED;
		}
		clampScrollOffset();
	}

	/**
	 * 创建可见列表条目的操作按钮（仅保留删除）
	 * <p>
	 * 同时将按钮引用存入 {@link #entryButtons}，便于滚动时仅移除并重建这些按钮。
	 */
	void createEntryButtons() {
		// rebuildWidgets 路径下旧按钮已被 clearWidgets 移除，这里仅清空引用
		entryButtons.clear();
		int visibleCount = getVisibleEntryCount();
		int startIndex = scrollOffset;
		int endIndex = Math.min(startIndex + visibleCount, beeTypes.size());
		// 删除按钮左移到滚动条左侧，避免遮挡滚动条
		int deleteX = getDeleteButtonX(width);

		for (int i = startIndex; i < endIndex; i++) {
			int entryY = LIST_TOP_Y + (i - startIndex) * ENTRY_SPACING;
			final int index = i;
			// 图标按钮垂直居中显示在条目内
			int buttonY = entryY + (ENTRY_HEIGHT - DELETE_BUTTON_HEIGHT) / 2;

			Button btn = Button.builder(
					Component.literal("\u2715"),
					button -> deleteEntry(index)
			).bounds(deleteX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT).build();
			entryButtons.add(btn);
			addRenderableWidget(btn);
		}
	}

	/**
	 * 包级桥接方法：移除指定组件。
	 * <p>
	 * {@code Screen.removeWidget} 为 protected 访问，组合式辅助类（如 {@link FilterListDragHandler}）
	 * 非 Screen 子类无法直接调用，故通过此包级方法转发。
	 */
	void removeWidgetBridge(Button btn) {
		removeWidget(btn);
	}

	/**
	 * 包级桥接方法：重建全部控件。
	 * <p>
	 * {@code Screen.rebuildWidgets} 为 protected 访问，需通过此包级方法转发给辅助类。
	 */
	void rebuildWidgetsBridge() {
		rebuildWidgets();
	}

	/**
	 * 包级桥接方法：添加可渲染组件。
	 * <p>
	 * {@code Screen.addRenderableWidget} 为 protected 访问，组合式辅助类
	 * （{@link FilterListInputHelper}、{@link FilterListActionBar}）非 Screen 子类无法直接调用，
	 * 故通过此包级方法转发。
	 *
	 * @param widget 待添加的可渲染组件（Button、EditBox 等）
	 */
	void addRenderableWidgetBridge(AbstractWidget widget) {
		addRenderableWidget(widget);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 全屏纯色不透明背景
		graphics.fill(0, 0, width, height, GuiColors.BG_SCREEN_DARK);
		// 渲染标题
		graphics.drawCenteredString(font, this.title, width / 2, 8, GuiColors.TEXT_TITLE);

		// 渲染过滤模式标签（模式按钮左侧，委托给模式选择器）
		modeSelector.renderModeLabel(graphics);

		// 渲染列表区域背景（委托给渲染辅助类）
		int listBottom = height - LIST_BOTTOM_MARGIN;
		renderer.renderListBackground(graphics, listBottom);

		// 渲染列表表头
		renderer.renderHeader(graphics, beeTypes, getSelectedTypes(), scrollOffset);

		// 启用裁剪区域
		graphics.enableScissor(SCREEN_MARGIN, LIST_TOP_Y, width - SCREEN_MARGIN, listBottom);
		renderer.renderEntries(graphics, beeTypes, getSelectedTypes(), scrollOffset, mouseX, mouseY);
		graphics.disableScissor();

		// 渲染滚动条
		dragHandler.renderScrollBar(graphics);

		// 手动渲染组件
		for (var renderable : renderables) {
			renderable.render(graphics, mouseX, mouseY, partialTick);
		}

		// 高亮当前过滤模式按钮（委托给模式选择器）
		modeSelector.renderModeButtonHighlight(graphics);

		// 在裁剪区域外渲染拖放指示线与幽灵
		renderer.renderDragOverlay(graphics, beeTypes, scrollOffset,
				dragHandler.getDragSourceIndex(), dragHandler.getDragInsertIndex(), mouseX, mouseY);

		// 渲染输入验证提示（委托给输入辅助类）
		inputHelper.renderInputHint(graphics, mouseX, mouseY);

		// 渲染导入结果限时提示（委托给操作栏辅助类）
		actionBar.renderImportResult(graphics);
	}

	// ========== 事件处理 ==========

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY > 0) {
			scrollOffset = Math.max(0, scrollOffset - 1);
		} else if (scrollY < 0) {
			scrollOffset = Math.min(Math.max(0, beeTypes.size() - getVisibleEntryCount()), scrollOffset + 1);
		}
		// 仅重建条目删除按钮，避免全量 rebuildWidgets 重建搜索框/模式按钮等无关组件
		dragHandler.rebuildEntryButtonsOnly();
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		// 滚动条交互：左键拖动滑块，点击轨道空白处快速跳转到对应位置
		if (dragHandler.handleScrollbarClick(mouseX, mouseY, button)) {
			return true;
		}

		// 表头复选框：左键切换当前可见条目的全选/取消全选
		if (button == 0 && renderer.isHeaderCheckboxHit(mouseX, mouseY)) {
			toggleHeaderCheckbox();
			return true;
		}

		// 行复选框
		Integer checkboxIndex = renderer.getRowCheckboxIndex(mouseX, mouseY, beeTypes, scrollOffset);
		if (button == 0 && checkboxIndex != null) {
			toggleSelection(checkboxIndex);
			return true;
		}

		// 拖拽手柄
		Integer dragIndex = renderer.getDragHandleIndex(mouseX, mouseY, beeTypes, scrollOffset);
		if (button == 0 && dragIndex != null) {
			dragHandler.startDrag(dragIndex);
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		// 委托拖拽与滚动条拖拽逻辑给 DragHandler（Task 11 修复保留于处理器内）
		if (dragHandler.handleMouseDragged(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragHandler.handleMouseReleased(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private void toggleHeaderCheckbox() {
		selectionManager.toggleHeaderCheckbox(beeTypes, scrollOffset, getVisibleEntryCount());
		selectionManager.updateButtonState(deleteSelectedButton);
	}

	private void toggleSelection(int index) {
		selectionManager.toggleSelection(beeTypes, index);
		selectionManager.updateButtonState(deleteSelectedButton);
	}

	private void selectAllVisible() {
		selectionManager.selectAllVisible(beeTypes, scrollOffset, getVisibleEntryCount());
		selectionManager.updateButtonState(deleteSelectedButton);
	}

	private void invertVisible() {
		selectionManager.invertVisible(beeTypes, scrollOffset, getVisibleEntryCount());
		selectionManager.updateButtonState(deleteSelectedButton);
	}

	private void deleteSelected() {
		if (selectionManager.deleteSelected(beeTypes)) {
			clampScrollOffset();
			selectionManager.updateButtonState(deleteSelectedButton);
			rebuildWidgets();
		}
	}

	private void deleteEntry(int index) {
		if (index < 0 || index >= beeTypes.size()) return;
		selectionManager.onEntryDeleted(beeTypes.get(index));
		beeTypes.remove(index);
		clampScrollOffset();
		selectionManager.updateButtonState(deleteSelectedButton);
		rebuildWidgets();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/**
	 * 获取蜜蜂代表图标（委托给缓存类）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 图标 ItemStack，无法解析或世界未加载时返回空栈
	 */
	ItemStack getBeeIcon(String beeTypeId) {
		return beeInfoCache.getBeeIcon(beeTypeId);
	}

	/** 获取蜜蜂显示名称（委托给缓存类） */
	Component getBeeDisplayName(String beeTypeId) {
		return beeInfoCache.getBeeDisplayName(beeTypeId);
	}

	/** 获取蜜蜂产物信息（委托给缓存类） */
	Component getBeeProductInfo(String beeTypeId) {
		return beeInfoCache.getBeeProductInfo(beeTypeId);
	}

	int getVisibleEntryCount() {
		int availableHeight = height - LIST_BOTTOM_MARGIN - LIST_TOP_Y - 4;
		return Math.max(1, availableHeight / ENTRY_SPACING);
	}

	/** 限制滚动偏移到合法范围（包级可见，供辅助类调用） */
	void clampScrollOffset() {
		int maxScroll = Math.max(0, beeTypes.size() - getVisibleEntryCount());
		scrollOffset = Math.min(Math.max(0, scrollOffset), maxScroll);
	}

	/** 滚动到底部（包级可见，供辅助类调用） */
	void scrollToBottom() {
		scrollOffset = Math.max(0, beeTypes.size() - getVisibleEntryCount());
	}
}