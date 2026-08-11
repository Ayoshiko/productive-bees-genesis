package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.network.FilterConfigSyncPayload;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
	 * FilterListScreen 的操作栏辅助类
	 * <p>
	 * 将保存/返回/从列表选择、重置/导入/导出等操作按钮及其点击处理逻辑从屏幕类中剥离，
	 * 降低 FilterListScreen 的复杂度（SRP）。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP — 仅负责操作按钮的创建与点击动作，不涉及列表渲染或输入校验</li>
	 *   <li>组合模式 — 持有 FilterListScreen 引用，通过包级访问共享必要状态</li>
	 * </ul>
	 * <br/>
	 * 线程安全：客户端 GUI 单线程访问，无需同步。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListActionBar {

	/** 顶部小工具按钮尺寸（重置/导入/导出图标按钮） */
	private static final int UTILITY_BUTTON_SIZE = 20;

	private final FilterListScreen screen;

	/**
	 * 导入操作结果提示（限时显示）。
	 * <p>
	 * 区分"重复项"和"无效项"两种跳过原因，显示不同颜色和文案的提示。
	 * null 表示无提示；超时后在 renderImportResult 中自动清空并停止渲染。
	 */
	private Component importResultMessage;
	/** 导入提示文字颜色（ARGB） */
	private int importResultColor;
	/** 导入提示显示截止时刻（系统毫秒），超过则停止渲染 */
	private long importResultShowUntil;

	FilterListActionBar(FilterListScreen screen) {
		this.screen = screen;
	}

	// ========== 顶部工具按钮（重置/导入/导出）==========

	/**
	 * 创建重置/导入/导出工具按钮（图标按钮，带 tooltip）。
	 *
	 * @param y       按钮 Y 坐标
	 * @param startX  起始 X 坐标
	 */
	void initUtilityButtons(int y, int startX) {
		int gap = 2;
		int x = startX;

		screen.addRenderableWidgetBridge(Button.builder(Component.literal("\u21BA"), button -> resetToDefault())
				.bounds(x, y, UTILITY_BUTTON_SIZE, UTILITY_BUTTON_SIZE)
				.tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.config.reset.tooltip")))
				.build());
		x += UTILITY_BUTTON_SIZE + gap;

		screen.addRenderableWidgetBridge(Button.builder(Component.literal("\u2191"), button -> exportToClipboard())
				.bounds(x, y, UTILITY_BUTTON_SIZE, UTILITY_BUTTON_SIZE)
				.tooltip(Tooltip.create(Component.translatable("productivebeesgenesis.config.export.tooltip")))
				.build());
		x += UTILITY_BUTTON_SIZE + gap;

		screen.addRenderableWidgetBridge(Button.builder(Component.literal("\u2193"), button -> importFromClipboard())
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
		if (screen.getMinecraft() == null) return;
		screen.getMinecraft().setScreen(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						screen.performResetToDefault();
					}
					// 无论确认或取消，都返回当前过滤列表屏幕；
					// setScreen(this) 会触发 init() 重建组件以反映最新状态
					screen.getMinecraft().setScreen(screen);
				},
				Component.translatable("productivebeesgenesis.config.reset.confirm.title"),
				Component.translatable("productivebeesgenesis.config.reset.confirm.message"),
				Component.translatable("productivebeesgenesis.config.reset.confirm.yes"),
				Component.translatable("productivebeesgenesis.config.reset.confirm.no")
		));
	}

	/**
	 * 导出过滤列表到剪贴板（JSON 数组格式）。
	 */
	private void exportToClipboard() {
		if (screen.getMinecraft() == null) return;
		String json = FilterListClipboardHelper.exportToJson(screen.beeTypes);
		screen.getMinecraft().keyboardHandler.setClipboard(json);
	}

	/**
	 * 从剪贴板导入过滤列表，区分重复项与无效项并显示限时提示。
	 * <p>
	 * 校验并区分重复项与无效项，分别计数；屏幕端负责应用结果与展示提示。
	 */
	private void importFromClipboard() {
		if (screen.getMinecraft() == null) return;
		String clipboard = screen.getMinecraft().keyboardHandler.getClipboard();
		// 解析剪贴板文本为 token 列表（无状态操作委托给工具类）
		List<String> tokens = FilterListClipboardHelper.parseTokens(clipboard);
		if (tokens.isEmpty()) {
			return;
		}

		// 校验并区分重复项与无效项，分别计数；屏幕端负责应用结果与展示提示
		FilterListClipboardHelper.ImportResult result = FilterListClipboardHelper.validateImport(tokens, screen.beeTypes);
		screen.beeTypes.addAll(result.getAdded());

		if (result.hasAdded()) {
			screen.scrollToBottom();
			screen.rebuildWidgetsBridge();
		}

		// 根据导入结果显示不同颜色和文案的限时提示
		Component message = result.buildMessage();
		if (message != null) {
			showImportResult(message, result.buildColor());
		}
	}

	/**
	 * 设置导入结果提示，在屏幕底部限时显示 5 秒。
	 *
	 * @param message 提示文本
	 * @param color   ARGB 颜色
	 */
	private void showImportResult(Component message, int color) {
		this.importResultMessage = message;
		this.importResultColor = color;
		this.importResultShowUntil = System.currentTimeMillis() + 5000L;
	}

	/**
	 * 渲染导入结果提示。
	 * <p>
	 * 在列表区域底部上方居中显示，超时后自动停止渲染。
	 *
	 * @param graphics 绘图上下文
	 */
	void renderImportResult(GuiGraphics graphics) {
		if (importResultMessage == null) return;
		if (System.currentTimeMillis() > importResultShowUntil) {
			importResultMessage = null;
			return;
		}
		// 显示在列表底边框上方，避免与底部控制栏重叠
		int y = screen.height - FilterListScreen.LIST_BOTTOM_MARGIN - 12;
		graphics.drawCenteredString(screen.getMinecraft().font, importResultMessage, screen.width / 2, y, importResultColor);
	}

	// ========== 底部操作按钮（保存/选择/返回）==========

	/**
	 * 创建底部操作按钮（保存/从列表选择/返回），均匀居中分布。
	 *
	 * @param bottomBtnY 按钮 Y 坐标
	 */
	void initBottomBar(int bottomBtnY) {
		int saveW = 90;
		int selectW = 120;
		int backW = 90;
		int totalBtnWidth = saveW + FilterListScreen.CONTROL_SPACING + selectW
				+ FilterListScreen.CONTROL_SPACING + backW;
		int btnStartX = screen.width / 2 - totalBtnWidth / 2;

		screen.addRenderableWidgetBridge(Button.builder(
				Component.translatable("productivebeesgenesis.config.save"),
				button -> saveAndClose()
		).bounds(btnStartX, bottomBtnY, saveW, 20).build());

		screen.addRenderableWidgetBridge(Button.builder(
				Component.translatable("productivebeesgenesis.config.select_from_list"),
				button -> openBeeSelection()
		).bounds(btnStartX + saveW + FilterListScreen.CONTROL_SPACING, bottomBtnY, selectW, 20).build());

		screen.addRenderableWidgetBridge(Button.builder(
				Component.translatable("gui.back"),
				button -> screen.onClose()
		).bounds(btnStartX + saveW + FilterListScreen.CONTROL_SPACING + selectW + FilterListScreen.CONTROL_SPACING,
				bottomBtnY, backW, 20).build());
	}

	/**
	 * 保存配置并关闭 — 通过自定义数据包发送到服务端写入配置。
	 * <p>
	 * 多人游戏下客户端无法直接修改 SERVER 配置（客户端的 SERVER 配置只是
	 * NeoForge 在配置阶段下发的只读同步副本）。通过自定义数据包将编辑结果
	 * 发送到服务端，由服务端校验权限与数据后写入配置并持久化，
	 * 再由 NeoForge 原生 ConfigSync 同步到所有客户端（包括发起者）。
	 * 单机模式同样走此流程（集成服务器的内存连接，无额外网络开销）。
	 */
	private void saveAndClose() {
		try {
			PacketDistributor.sendToServer(new FilterConfigSyncPayload(
					screen.filterMode.name(),
					screen.beeTypes
			));
		} catch (Exception e) {
			// 数据包发送失败时记录日志，不阻断关闭
			ProductiveBeesGenesis.LOGGER.error("发送过滤配置同步包失败", e);
		}
		screen.onClose();
	}

	/**
	 * 打开蜜蜂选择屏幕，将当前列表副本传入，选中结果通过回调批量添加。
	 */
	private void openBeeSelection() {
		screen.getMinecraft().setScreen(new BeeSelectionScreen(screen, new ArrayList<>(screen.beeTypes), this::addBeesFromSelection));
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
			if (!screen.beeTypes.contains(trimmed)) {
				screen.beeTypes.add(trimmed);
				addedAny = true;
			}
		}
		if (addedAny) {
			screen.scrollToBottom();
			screen.rebuildWidgetsBridge();
		}
	}
}