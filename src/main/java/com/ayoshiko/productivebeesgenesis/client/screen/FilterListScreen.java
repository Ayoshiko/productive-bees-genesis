package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.mojang.datafixers.util.Pair;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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

	// ========== 布局常量（包级可见，供 FilterListRenderer 使用）==========
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
	/** 输入框宽度 */
	private static final int INPUT_WIDTH = 180;
	/** 底部控制栏元素间距 */
	private static final int CONTROL_SPACING = 6;
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

	// ========== 状态数据 ==========
	private final Screen parent;
	/** 本地编辑副本（用户修改后点击保存才写入配置） */
	private final List<String> beeTypes = new ArrayList<>();
	/** 批量删除复选框选中的蜜蜂类型集合 */
	private final Set<String> selectedTypes = new HashSet<>();
	private ModConfig.FilterMode filterMode;
	/** 滚动偏移（以条目为单位） */
	private int scrollOffset = 0;
	/** 输入框是否可见 */
	private boolean inputVisible = false;
	/**
	 * 初始化标志位 — 区分首次加载与重建
	 * <p>
	 * 解决 confirmAddEntry()/deleteEntry()/moveEntry() 调用 rebuildWidgets() 触发 init()，
	 * init() 再次调用 loadFromConfig() 会覆盖本地未保存的修改。
	 * 首次 init() 加载配置后置为 true，后续 rebuildWidgets() 触发的 init() 跳过 loadFromConfig()。
	 */
	private boolean initialized = false;
	/** 蜜蜂图标缓存，按类型ID字符串缓存避免每帧创建 ItemStack */
	private final Map<String, ItemStack> beeIconCache = new ConcurrentHashMap<>();
	/** 蜜蜂显示名称缓存（避免每帧重复解析翻译键） */
	private final Map<String, Component> beeDisplayNameCache = new ConcurrentHashMap<>();
	/** 蜜蜂产物信息缓存（避免每帧遍历配方） */
	private final Map<String, Component> beeProductInfoCache = new ConcurrentHashMap<>();
	/** 列表渲染辅助类 */
	private final FilterListRenderer renderer = new FilterListRenderer(this);

	/** 拖拽排序状态 */
	private int dragSourceIndex = -1;
	private int dragInsertIndex = -1;
	private boolean isDragging = false;
	/** 是否正在拖动滚动条滑块 */
	private boolean isDraggingScrollBar = false;

	// ========== UI 组件 ==========
	private final Button[] modeButtons = new Button[ModConfig.FilterMode.values().length];
	private EditBox inputField;
	private Button confirmAddButton;
	private Button cancelButton;
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

		// 顶部左侧：全选 / 反选按钮
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

		// 顶部右侧：过滤模式图标按钮组
		createModeButtons(topButtonY);

		// 底部控制栏 — 居中排列，根据屏幕宽度自动适配输入框
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
				button -> toggleInputVisibility(true)
		).bounds(controlStartX, bottomY, addButtonW, 20).build());

		// 删除选中按钮
		int deleteSelectedX = controlStartX + addButtonW + CONTROL_SPACING;
		deleteSelectedButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.delete_selected"),
				button -> deleteSelected()
		).bounds(deleteSelectedX, bottomY, deleteSelectedW, 20).build();
		deleteSelectedButton.active = !selectedTypes.isEmpty();
		addRenderableWidget(deleteSelectedButton);

		// 输入框 — 默认隐藏
		int inputX = deleteSelectedX + deleteSelectedW + CONTROL_SPACING;
		inputField = new EditBox(font, inputX, bottomY, inputW, 20,
				Component.translatable("productivebeesgenesis.config.input_bee_type"));
		inputField.setMaxLength(128);
		inputField.setHint(Component.translatable("productivebeesgenesis.config.input_bee_type"));
		inputField.setVisible(false);
		inputField.setResponder(this::onInputChanged);
		addRenderableWidget(inputField);

		// 确认添加按钮
		int confirmX = inputX + inputW + CONTROL_SPACING;
		confirmAddButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.confirm"),
				button -> confirmAddEntry()
		).bounds(confirmX, bottomY, confirmW, 20).build();
		confirmAddButton.visible = false;
		addRenderableWidget(confirmAddButton);

		// 取消按钮（输入框可见时显示）
		int cancelX = confirmX + confirmW + CONTROL_SPACING;
		cancelButton = Button.builder(
				Component.translatable("productivebeesgenesis.config.cancel"),
				button -> toggleInputVisibility(false)
		).bounds(cancelX, bottomY, cancelW, 20).build();
		cancelButton.visible = false;
		addRenderableWidget(cancelButton);

		// 底部操作按钮 — 均匀分布
		int bottomBtnY = height - 28;
		int saveW = 90;
		int selectW = 120;
		int backW = 90;
		int totalBtnWidth = saveW + CONTROL_SPACING + selectW + CONTROL_SPACING + backW;
		int btnStartX = width / 2 - totalBtnWidth / 2;

		addRenderableWidget(Button.builder(
				Component.translatable("productivebeesgenesis.config.save"),
				button -> saveAndClose()
		).bounds(btnStartX, bottomBtnY, saveW, 20).build());

		addRenderableWidget(Button.builder(
				Component.translatable("productivebeesgenesis.config.select_from_list"),
				button -> openBeeSelection()
		).bounds(btnStartX + saveW + CONTROL_SPACING, bottomBtnY, selectW, 20).build());

		addRenderableWidget(Button.builder(
				Component.translatable("gui.back"),
				button -> onClose()
		).bounds(btnStartX + saveW + CONTROL_SPACING + selectW + CONTROL_SPACING, bottomBtnY, backW, 20).build());

		createEntryButtons();
	}

	private void createModeButtons(int y) {
		int modeButtonSize = 20;
		int modeButtonGap = 2;
		int totalModeWidth = modeButtonSize * ModConfig.FilterMode.values().length
				+ modeButtonGap * (ModConfig.FilterMode.values().length - 1);
		int firstModeX = width - SCREEN_MARGIN - totalModeWidth;

		// 模式标签绘制在 render() 中完成，这里仅创建并排列图标按钮
		for (ModConfig.FilterMode mode : ModConfig.FilterMode.values()) {
			int bx = firstModeX + mode.ordinal() * (modeButtonSize + modeButtonGap);
			Button btn = Button.builder(
					Component.literal(getModeIcon(mode)),
					button -> setFilterMode(mode)
			).bounds(bx, y, modeButtonSize, modeButtonSize)
					.tooltip(Tooltip.create(Component.translatable(
							"productivebeesgenesis.config.filter_mode." + mode.name().toLowerCase() + ".tooltip")))
					.build();
			modeButtons[mode.ordinal()] = btn;
			addRenderableWidget(btn);
		}
	}

	private String getModeIcon(ModConfig.FilterMode mode) {
		return switch (mode) {
			case DISABLED -> "\u26D4";
			case WHITELIST -> "\u2713";
			case BLACKLIST -> "\u2717";
		};
	}

	private void setFilterMode(ModConfig.FilterMode mode) {
		this.filterMode = mode;
	}

	private void loadFromConfig() {
		beeTypes.clear();
		selectedTypes.clear();
		try {
			if (ModConfig.SERVER_SPEC.isLoaded()) {
				beeTypes.addAll(ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get());
				filterMode = ModConfig.SERVER.myriadCreationsFilterMode.get();
			} else {
				filterMode = ModConfig.FilterMode.DISABLED;
			}
		} catch (IllegalStateException e) {
			filterMode = ModConfig.FilterMode.DISABLED;
		}
		clampScrollOffset();
	}

	/**
	 * 创建可见列表条目的操作按钮（仅保留删除）
	 */
	private void createEntryButtons() {
		int visibleCount = getVisibleEntryCount();
		int startIndex = scrollOffset;
		int endIndex = Math.min(startIndex + visibleCount, beeTypes.size());
		// 删除按钮左移到滚动条左侧，避免遮挡滚动条
		int deleteX = width - SCREEN_MARGIN - SCROLL_BAR_RIGHT_MARGIN - SCROLL_BAR_WIDTH - DELETE_BUTTON_WIDTH;

		for (int i = startIndex; i < endIndex; i++) {
			int entryY = LIST_TOP_Y + (i - startIndex) * ENTRY_SPACING;
			final int index = i;
			// 图标按钮垂直居中显示在条目内
			int buttonY = entryY + (ENTRY_HEIGHT - DELETE_BUTTON_HEIGHT) / 2;

			addRenderableWidget(Button.builder(
					Component.literal("\u2715"),
					button -> deleteEntry(index)
			).bounds(deleteX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT).build());
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 全屏纯色不透明背景
		graphics.fill(0, 0, width, height, 0xFF101010);
		// 渲染标题
		graphics.drawCenteredString(font, this.title, width / 2, 8, 0xFFFFFF);

		// 渲染过滤模式标签（模式按钮左侧）
		Component modeLabel = Component.translatable("productivebeesgenesis.config.filter_mode");
		int modeLabelWidth = font.width(modeLabel);
		int firstModeX = modeButtons[0] != null ? modeButtons[0].getX() : width - SCREEN_MARGIN - 64;
		graphics.drawString(font, modeLabel, firstModeX - modeLabelWidth - 8, 29, 0xFFB0B0B0);

		// 渲染列表区域背景
		int listBottom = height - LIST_BOTTOM_MARGIN;
		graphics.fill(SCREEN_MARGIN, LIST_TOP_Y - 2, width - SCREEN_MARGIN, listBottom, 0xFF1A1A1A);
		// 上边框
		graphics.fill(SCREEN_MARGIN, LIST_TOP_Y - 2, width - SCREEN_MARGIN, LIST_TOP_Y - 1, 0xFF707070);
		// 下边框
		graphics.fill(SCREEN_MARGIN, listBottom - 1, width - SCREEN_MARGIN, listBottom, 0xFF707070);
		// 左边框
		graphics.fill(SCREEN_MARGIN, LIST_TOP_Y - 2, SCREEN_MARGIN + 1, listBottom, 0xFF707070);
		// 右边框
		graphics.fill(width - SCREEN_MARGIN - 1, LIST_TOP_Y - 2, width - SCREEN_MARGIN, listBottom, 0xFF707070);
		// 列表顶部边框已作为表头与第一行条目之间的分隔线，不再额外绘制表头下边框，
		// 避免第一行蜜蜂类型 ID 下方出现重复横线。

		// 渲染列表表头
		renderer.renderHeader(graphics, beeTypes, selectedTypes, scrollOffset);

		// 启用裁剪区域
		graphics.enableScissor(SCREEN_MARGIN, LIST_TOP_Y, width - SCREEN_MARGIN, listBottom);
		renderer.renderEntries(graphics, beeTypes, selectedTypes, scrollOffset, mouseX, mouseY);
		graphics.disableScissor();

		// 渲染滚动条
		renderScrollBar(graphics);

		// 手动渲染组件
		for (var renderable : renderables) {
			renderable.render(graphics, mouseX, mouseY, partialTick);
		}

		// 高亮当前过滤模式按钮
		renderModeButtonHighlight(graphics);

		// 在裁剪区域外渲染拖放指示线与幽灵
		renderer.renderDragOverlay(graphics, beeTypes, scrollOffset, dragSourceIndex, dragInsertIndex, mouseX, mouseY);

		// 渲染输入验证提示
		renderInputHint(graphics, mouseX, mouseY);
	}

	private void renderModeButtonHighlight(GuiGraphics graphics) {
		for (ModConfig.FilterMode mode : ModConfig.FilterMode.values()) {
			Button btn = modeButtons[mode.ordinal()];
			if (btn == null || mode != filterMode) {
				continue;
			}
			// 当前激活模式绘制高亮边框
			graphics.fill(btn.getX() - 1, btn.getY() - 1, btn.getX() + btn.getWidth() + 1, btn.getY(), 0xFFFFFFFF);
			graphics.fill(btn.getX() - 1, btn.getY() + btn.getHeight(), btn.getX() + btn.getWidth() + 1,
					btn.getY() + btn.getHeight() + 1, 0xFFFFFFFF);
			graphics.fill(btn.getX() - 1, btn.getY(), btn.getX(), btn.getY() + btn.getHeight(), 0xFFFFFFFF);
			graphics.fill(btn.getX() + btn.getWidth(), btn.getY(), btn.getX() + btn.getWidth() + 1,
					btn.getY() + btn.getHeight(), 0xFFFFFFFF);
		}
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0xFF101010);
	}

	private record ScrollBarThumb(int y, int height) {}

	private ScrollBarThumb calculateScrollBarThumb() {
		int total = beeTypes.size();
		int visible = getVisibleEntryCount();
		if (total <= visible) {
			return null;
		}
		int listBottom = height - LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - LIST_TOP_Y;
		int thumbHeight = Math.max(16, trackHeight * visible / total);
		int maxScroll = total - visible;
		int thumbY = LIST_TOP_Y + (trackHeight - thumbHeight) * scrollOffset / Math.max(1, maxScroll);
		return new ScrollBarThumb(thumbY, thumbHeight);
	}

	private void renderScrollBar(GuiGraphics graphics) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) return;

		int listBottom = height - LIST_BOTTOM_MARGIN;
		int scrollX = getScrollBarX();
		graphics.fill(scrollX, LIST_TOP_Y, scrollX + SCROLL_BAR_WIDTH, listBottom, 0xFF404040);
		graphics.fill(scrollX, thumb.y, scrollX + SCROLL_BAR_WIDTH, thumb.y + thumb.height, 0xFFA0A0A0);
	}

	private int getScrollBarX() {
		return width - SCREEN_MARGIN - SCROLL_BAR_WIDTH;
	}

	private boolean isMouseOverScrollBar(double mouseX, double mouseY) {
		return mouseX >= getScrollBarX() && mouseX < getScrollBarX() + SCROLL_BAR_WIDTH
				&& mouseY >= LIST_TOP_Y && mouseY < height - LIST_BOTTOM_MARGIN;
	}

	private boolean isMouseOverScrollBarThumb(double mouseX, double mouseY) {
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return false;
		}
		return mouseY >= thumb.y && mouseY < thumb.y + thumb.height;
	}

	private void updateScrollOffsetFromMouseY(double mouseY) {
		int total = beeTypes.size();
		int visible = getVisibleEntryCount();
		int maxScroll = total - visible;
		if (maxScroll <= 0) {
			return;
		}
		int listBottom = height - LIST_BOTTOM_MARGIN;
		int trackHeight = listBottom - LIST_TOP_Y;
		ScrollBarThumb thumb = calculateScrollBarThumb();
		if (thumb == null) {
			return;
		}
		int available = trackHeight - thumb.height;
		if (available <= 0) {
			return;
		}
		double relative = mouseY - LIST_TOP_Y - thumb.height / 2.0;
		int offset = (int) Math.round(relative * maxScroll / available);
		scrollOffset = Math.max(0, Math.min(maxScroll, offset));
	}

	private void renderInputHint(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!inputVisible) return;
		String text = inputField.getValue();
		if (text.isEmpty()) return;

		Pair<Boolean, Component> validation = validateInput(text);
		if (!validation.getFirst()) {
			int bottomY = height - LIST_BOTTOM_MARGIN + 10;
			graphics.drawString(font, validation.getSecond(),
					width / 2 - INPUT_WIDTH / 2, bottomY + 22, 0xFFFF6060);
		}
	}

	// ========== 事件处理 ==========

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY > 0) {
			scrollOffset = Math.max(0, scrollOffset - 1);
		} else if (scrollY < 0) {
			scrollOffset = Math.min(Math.max(0, beeTypes.size() - getVisibleEntryCount()), scrollOffset + 1);
		}
		rebuildWidgets();
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		// 滚动条交互：左键拖动滑块，点击轨道空白处快速跳转到对应位置
		if (button == 0 && beeTypes.size() > getVisibleEntryCount() && isMouseOverScrollBar(mouseX, mouseY)) {
			if (isMouseOverScrollBarThumb(mouseX, mouseY)) {
				isDraggingScrollBar = true;
			} else {
				updateScrollOffsetFromMouseY(mouseY);
			}
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
			startDrag(dragIndex);
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (isDraggingScrollBar && button == 0) {
			updateScrollOffsetFromMouseY(mouseY);
			return true;
		}
		if (isDragging && button == 0) {
			dragInsertIndex = renderer.getInsertionIndex(mouseY, beeTypes, scrollOffset);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (isDraggingScrollBar && button == 0) {
			isDraggingScrollBar = false;
			return true;
		}
		if (isDragging && button == 0) {
			finishDrag();
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private void startDrag(int index) {
		this.dragSourceIndex = index;
		this.dragInsertIndex = index;
		this.isDragging = true;
	}

	private void finishDrag() {
		if (dragSourceIndex >= 0 && dragSourceIndex < beeTypes.size() && dragInsertIndex != dragSourceIndex) {
			int target = Math.max(0, Math.min(beeTypes.size(), dragInsertIndex));
			String moved = beeTypes.remove(dragSourceIndex);
			if (target > dragSourceIndex) {
				target--;
			}
			beeTypes.add(target, moved);
		}
		isDragging = false;
		dragSourceIndex = -1;
		dragInsertIndex = -1;
		rebuildWidgets();
	}

	private void toggleHeaderCheckbox() {
		int visibleCount = getVisibleEntryCount();
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		boolean allSelected = true;
		for (int i = scrollOffset; i < end; i++) {
			if (!selectedTypes.contains(beeTypes.get(i))) {
				allSelected = false;
				break;
			}
		}
		for (int i = scrollOffset; i < end; i++) {
			if (allSelected) {
				selectedTypes.remove(beeTypes.get(i));
			} else {
				selectedTypes.add(beeTypes.get(i));
			}
		}
		updateDeleteSelectedButton();
	}

	private void toggleSelection(int index) {
		String type = beeTypes.get(index);
		if (!selectedTypes.remove(type)) {
			selectedTypes.add(type);
		}
		updateDeleteSelectedButton();
	}

	private void selectAllVisible() {
		int visibleCount = getVisibleEntryCount();
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		for (int i = scrollOffset; i < end; i++) {
			selectedTypes.add(beeTypes.get(i));
		}
		updateDeleteSelectedButton();
	}

	private void invertVisible() {
		int visibleCount = getVisibleEntryCount();
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		for (int i = scrollOffset; i < end; i++) {
			String type = beeTypes.get(i);
			if (!selectedTypes.remove(type)) {
				selectedTypes.add(type);
			}
		}
		updateDeleteSelectedButton();
	}

	private void updateDeleteSelectedButton() {
		if (deleteSelectedButton != null) {
			deleteSelectedButton.active = !selectedTypes.isEmpty();
		}
	}

	private void deleteSelected() {
		if (selectedTypes.isEmpty()) {
			return;
		}
		beeTypes.removeIf(selectedTypes::contains);
		selectedTypes.clear();
		clampScrollOffset();
		updateDeleteSelectedButton();
		rebuildWidgets();
	}

	private void onInputChanged(String text) {
		// 验证状态由渲染时动态计算
	}

	private void toggleInputVisibility(boolean visible) {
		inputVisible = visible;
		inputField.setVisible(visible);
		confirmAddButton.visible = visible;
		cancelButton.visible = visible;
		if (visible) {
			setFocused(inputField);
			inputField.setFocused(true);
		} else {
			inputField.setValue("");
		}
	}

	private void confirmAddEntry() {
		String text = inputField.getValue();
		Pair<Boolean, Component> validation = validateInput(text);
		if (!validation.getFirst()) return;

		beeTypes.add(text.trim());
		scrollToBottom();
		toggleInputVisibility(false);
		rebuildWidgets();
	}

	private void deleteEntry(int index) {
		if (index < 0 || index >= beeTypes.size()) return;
		selectedTypes.remove(beeTypes.get(index));
		beeTypes.remove(index);
		clampScrollOffset();
		updateDeleteSelectedButton();
		rebuildWidgets();
	}

	private void saveAndClose() {
		try {
			ModConfig.SERVER.myriadCreationsFilteredBeeTypes.set(beeTypes);
			ModConfig.SERVER.myriadCreationsFilterMode.set(filterMode);
		} catch (Exception e) {
			// 配置保存失败时记录日志，不阻断关闭
		}
		onClose();
	}

	private void openBeeSelection() {
		minecraft.setScreen(new BeeSelectionScreen(this, new ArrayList<>(beeTypes), this::addBeesFromSelection));
	}

	/**
	 * 批量添加从选择屏幕返回的蜜蜂类型
	 * <p>
	 * 跳过空值、空白字符串及已存在的蜜蜂，保证不重复添加。
	 * 只要有新增条目，自动滚动到底部并重建列表控件。
	 *
	 * @param selectedBees 用户勾选的蜜蜂类型ID列表
	 */
	private void addBeesFromSelection(List<String> selectedBees) {
		if (selectedBees == null || selectedBees.isEmpty()) {
			return;
		}
		boolean addedAny = false;
		for (String beeType : selectedBees) {
			if (beeType == null || beeType.isBlank()) {
				continue;
			}
			String trimmed = beeType.trim();
			if (!beeTypes.contains(trimmed)) {
				beeTypes.add(trimmed);
				addedAny = true;
			}
		}
		if (addedAny) {
			scrollToBottom();
			rebuildWidgets();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/**
	 * 获取蜜蜂代表图标（带缓存）
	 * <p>
	 * 首次渲染时查询配方并创建 ItemStack，后续从缓存读取。
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 图标 ItemStack，无法解析或世界未加载时返回空栈
	 */
	ItemStack getBeeIcon(String beeTypeId) {
		ItemStack cached = beeIconCache.get(beeTypeId);
		if (cached != null) {
			return cached;
		}
		ResourceLocation beeType = BeeInfoHelper.parseBeeType(beeTypeId);
		if (beeType == null) {
			return ItemStack.EMPTY;
		}
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return ItemStack.EMPTY;
		}
		ItemStack icon = BeeInfoHelper.resolveBeeIcon(level, beeType);
		beeIconCache.put(beeTypeId, icon);
		return icon;
	}

	/**
	 * 获取蜜蜂显示名称（带缓存，避免每帧重复解析翻译键）
	 */
	Component getBeeDisplayName(String beeTypeId) {
		Component cached = beeDisplayNameCache.get(beeTypeId);
		if (cached != null) {
			return cached;
		}
		ResourceLocation beeType = BeeInfoHelper.parseBeeType(beeTypeId);
		if (beeType == null) {
			return Component.literal(beeTypeId);
		}
		Component displayName = BeeInfoHelper.getBeeDisplayName(beeType);
		beeDisplayNameCache.put(beeTypeId, displayName);
		return displayName;
	}

	/**
	 * 获取蜜蜂产物信息（带缓存，避免每帧遍历配方）
	 */
	Component getBeeProductInfo(String beeTypeId) {
		Component cached = beeProductInfoCache.get(beeTypeId);
		if (cached != null) {
			return cached;
		}
		ResourceLocation beeType = BeeInfoHelper.parseBeeType(beeTypeId);
		if (beeType == null) {
			return Component.empty();
		}
		Level level = Minecraft.getInstance().level;
		Component productInfo = level != null
				? BeeInfoHelper.getBeeProductInfo(level, beeType)
				: Component.empty();
		beeProductInfoCache.put(beeTypeId, productInfo);
		return productInfo;
	}

	int getVisibleEntryCount() {
		int availableHeight = height - LIST_BOTTOM_MARGIN - LIST_TOP_Y - 4;
		return Math.max(1, availableHeight / ENTRY_SPACING);
	}

	private void clampScrollOffset() {
		int maxScroll = Math.max(0, beeTypes.size() - getVisibleEntryCount());
		scrollOffset = Math.min(Math.max(0, scrollOffset), maxScroll);
	}

	private void scrollToBottom() {
		scrollOffset = Math.max(0, beeTypes.size() - getVisibleEntryCount());
	}

	private Pair<Boolean, Component> validateInput(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.empty"));
		}
		ResourceLocation beeType = BeeInfoHelper.parseBeeType(trimmed);
		if (beeType == null) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.invalid_format"));
		}
		if (beeTypes.contains(trimmed)) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.duplicate"));
		}
		if (!BeeInfoHelper.isBeeTypeExists(beeType)) {
			return Pair.of(false, Component.translatable("productivebeesgenesis.config.error.not_found"));
		}
		return Pair.of(true, Component.empty());
	}
}
