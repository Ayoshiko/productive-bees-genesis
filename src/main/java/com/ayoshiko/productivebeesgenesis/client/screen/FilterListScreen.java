package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.network.FilterConfigSyncPayload;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.mojang.datafixers.util.Pair;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.network.PacketDistributor;

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
	/** 顶部小工具按钮尺寸（重置/导入/导出图标按钮） */
	private static final int UTILITY_BUTTON_SIZE = 20;
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
	private ModConfig.FilterMode filterMode;
	/** 滚动偏移（以条目为单位） */
	int scrollOffset = 0;
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
	/** 蜜蜂信息缓存（图标/名称/产物），委托给独立缓存类（SRP） */
	final FilterListBeeInfoCache beeInfoCache = new FilterListBeeInfoCache();
	/**
	 * Task 16.2: 导入操作结果提示（限时显示）。
	 * <p>
	 * 区分"重复项"和"无效项"两种跳过原因，显示不同颜色和文案的提示。
	 * {@code null} 表示无提示；通过 {@link #showImportResult} 设置，
	 * 在 {@link #renderImportResult} 中渲染，超时后自动隐藏。
	 */
	private Component importResultMessage;
	/** 导入提示文字颜色（ARGB） */
	private int importResultColor;
	/** 导入提示显示截止时刻（系统毫秒），超过则停止渲染 */
	private long importResultShowUntil;
	/** 列表渲染辅助类 */
	final FilterListRenderer renderer = new FilterListRenderer(this);
	/** 拖拽与滚动条交互处理器（组合模式） */
	private final FilterListDragHandler dragHandler = new FilterListDragHandler(this);

	/**
	 * 当前可见条目的删除按钮列表
	 * <p>
	 * 滚动时仅需重建这些按钮（因为回调捕获了条目索引），而非全量 {@link #rebuildWidgets()}，
	 * 避免每帧销毁并重建搜索框、模式按钮、输入框等与滚动无关的组件。
	 */
	final List<Button> entryButtons = new ArrayList<>();

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

		// 顶部左侧：重置 / 导入 / 导出小工具按钮（图标按钮，带 tooltip）
		createUtilityButtons(topButtonY, SCREEN_MARGIN + 2 * (topButtonW + 4));

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
		deleteSelectedButton.active = selectionManager.hasSelection();
		addRenderableWidget(deleteSelectedButton);

		// 输入框 — 默认隐藏
		int inputX = deleteSelectedX + deleteSelectedW + CONTROL_SPACING;
		inputField = new EditBox(font, inputX, bottomY, inputW, 20,
				Component.translatable("productivebeesgenesis.config.input_bee_type"));
		inputField.setMaxLength(128);
		inputField.setHint(Component.translatable("productivebeesgenesis.config.input_bee_type"));
		inputField.setVisible(false);
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

	private void createUtilityButtons(int y, int startX) {
		int gap = 2;
		int x = startX;

		addRenderableWidget(Button.builder(Component.literal("\u21BA"), button -> resetToDefault())
				.bounds(x, y, UTILITY_BUTTON_SIZE, UTILITY_BUTTON_SIZE)
				.tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.config.reset.tooltip")))
				.build());
		x += UTILITY_BUTTON_SIZE + gap;

		addRenderableWidget(Button.builder(Component.literal("\u2191"), button -> exportToClipboard())
				.bounds(x, y, UTILITY_BUTTON_SIZE, UTILITY_BUTTON_SIZE)
				.tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.config.export.tooltip")))
				.build());
		x += UTILITY_BUTTON_SIZE + gap;

		addRenderableWidget(Button.builder(Component.literal("\u2193"), button -> importFromClipboard())
				.bounds(x, y, UTILITY_BUTTON_SIZE, UTILITY_BUTTON_SIZE)
				.tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.config.import.tooltip")))
				.build());
	}

	/**
	 * 重置过滤列表 — 弹出 ConfirmScreen 二次确认，避免误操作直接清空。
	 * <p>
	 * 原理：Minecraft 的 ConfirmScreen 提供标准化的"是/否"确认对话框，
	 * 回调接收 boolean（true=确认，false=取消）。确认后执行实际重置，
	 * 取消则直接返回当前屏幕。
	 */
	private void resetToDefault() {
		if (minecraft == null) return;
		minecraft.setScreen(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						performResetToDefault();
					}
					// 无论确认或取消，都返回当前过滤列表屏幕；
					// setScreen(this) 会触发 init() 重建组件以反映最新状态
					minecraft.setScreen(this);
				},
				Component.translatable("productivebeesgenesis.config.reset.confirm.title"),
				Component.translatable("productivebeesgenesis.config.reset.confirm.message"),
				Component.translatable("productivebeesgenesis.config.reset.confirm.yes"),
				Component.translatable("productivebeesgenesis.config.reset.confirm.no")
		));
	}

	/**
	 * 执行实际的重置操作（清空列表、重置模式）。
	 * <p>
	 * 仅修改本地状态数据，不调用 rebuildWidgets() — 由 {@link #resetToDefault()}
	 * 中的 setScreen(this) 触发 init() 完成组件重建。
	 */
	private void performResetToDefault() {
		beeTypes.clear();
		selectionManager.clear();
		filterMode = ModConfig.FilterMode.DISABLED;
		clampScrollOffset();
	}

	private void exportToClipboard() {
		if (minecraft == null) return;
		String json = FilterListClipboardHelper.exportToJson(beeTypes);
		minecraft.keyboardHandler.setClipboard(json);
		ProductiveBeesGenesis.LOGGER.info("已将 {} 个蜜蜂类型导出到剪贴板", beeTypes.size());
	}

	private void importFromClipboard() {
		if (minecraft == null) return;
		String clipboard = minecraft.keyboardHandler.getClipboard();
		// 解析剪贴板文本为 token 列表（无状态操作委托给工具类）
		List<String> tokens = FilterListClipboardHelper.parseTokens(clipboard);
		if (tokens.isEmpty()) {
			return;
		}

		// Task 16.2: 校验并区分重复项与无效项，分别计数；屏幕端负责应用结果与展示提示
		FilterListClipboardHelper.ImportResult result = FilterListClipboardHelper.validateImport(tokens, beeTypes);
		beeTypes.addAll(result.getAdded());

		if (result.hasAdded()) {
			scrollToBottom();
			rebuildWidgets();
		}

		// 根据导入结果显示不同颜色和文案的限时提示
		Component message = result.buildMessage();
		if (message != null) {
			showImportResult(message, result.buildColor());
		}

		ProductiveBeesGenesis.LOGGER.info("从剪贴板导入过滤列表：新增 {} 个，重复 {} 个，无效 {} 个",
				result.getAdded().size(), result.getDuplicates(), result.getInvalid());
	}

	/**
	 * 设置导入结果提示（Task 16.2），在屏幕底部限时显示 5 秒。
	 *
	 * @param message 提示文本
	 * @param color   ARGB 颜色
	 */
	private void showImportResult(Component message, int color) {
		this.importResultMessage = message;
		this.importResultColor = color;
		this.importResultShowUntil = System.currentTimeMillis() + 5000L;
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

		// 高亮当前过滤模式按钮
		renderModeButtonHighlight(graphics);

		// 在裁剪区域外渲染拖放指示线与幽灵
		renderer.renderDragOverlay(graphics, beeTypes, scrollOffset,
				dragHandler.getDragSourceIndex(), dragHandler.getDragInsertIndex(), mouseX, mouseY);

		// 渲染输入验证提示
		renderInputHint(graphics, mouseX, mouseY);

		// Task 16.2: 渲染导入结果限时提示
		renderImportResult(graphics);
	}

	/**
	 * 渲染导入结果提示（Task 16.2）。
	 * <p>
	 * 在列表区域底部上方居中显示，超时后自动停止渲染。
	 */
	private void renderImportResult(GuiGraphics graphics) {
		if (importResultMessage == null) return;
		if (System.currentTimeMillis() > importResultShowUntil) {
			importResultMessage = null;
			return;
		}
		// 显示在列表底边框上方，避免与底部控制栏重叠
		int y = height - LIST_BOTTOM_MARGIN - 12;
		graphics.drawCenteredString(font, importResultMessage, width / 2, y, importResultColor);
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

	private void renderInputHint(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!inputVisible) return;
		String text = inputField.getValue();
		if (text.isEmpty()) return;

		Pair<Boolean, Component> validation = validateInput(text);
		if (!validation.getFirst()) {
			int bottomY = height - LIST_BOTTOM_MARGIN + 10;
			graphics.drawString(font, validation.getSecond(),
					inputField.getX(), bottomY + 22, 0xFFFF6060);
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
		selectionManager.onEntryDeleted(beeTypes.get(index));
		beeTypes.remove(index);
		clampScrollOffset();
		selectionManager.updateButtonState(deleteSelectedButton);
		rebuildWidgets();
	}

	private void saveAndClose() {
		try {
			// Task 12: 多人游戏下客户端无法直接修改 SERVER 配置 — 客户端的 SERVER 配置只是
			// NeoForge 在配置阶段下发的只读同步副本，直接 ConfigValue.set() 仅修改本地副本，
			// 不会同步到服务端，下次同步还会被覆盖。
			// 通过自定义数据包将编辑结果发送到服务端，由服务端校验权限与数据后写入配置并持久化，
			// 再由 NeoForge 原生 ConfigSync 同步到所有客户端（包括发起者）。
			// 单机模式同样走此流程（集成服务器的内存连接，无额外网络开销）。
			PacketDistributor.sendToServer(new FilterConfigSyncPayload(
					filterMode.name(),
					beeTypes
			));
			ProductiveBeesGenesis.LOGGER.info("已发送万象创世过滤配置同步包：模式={}, 条目数={}",
					filterMode, beeTypes.size());
		} catch (Exception e) {
			// 数据包发送失败时记录日志，不阻断关闭
			ProductiveBeesGenesis.LOGGER.error("发送过滤配置同步包失败", e);
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
