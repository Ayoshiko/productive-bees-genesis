package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Optional;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter.EntryInfo;
import com.ayoshiko.productivebeesgenesis.mek.ae2.CombFuzzyMatcher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.network.CycleAeInputFilterModePayload;
import com.ayoshiko.productivebeesgenesis.network.SetAeInputFilterEntryPayload;
import com.ayoshiko.productivebeesgenesis.network.SetAeInputFilterEntryPayload.OperationType;
import com.ayoshiko.productivebeesgenesis.network.ToggleAeInputNbtIgnorePayload;
import com.ayoshiko.productivebeesgenesis.network.ToggleAeInputPayload;
import com.ayoshiko.productivebeesgenesis.network.ToggleAeInputPreciseModePayload;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * AE2 输入拉取配置窗口 — MEK GuiWindow 子类
 * <br/>
 * <b>V13 变更</b>：
 * <ul>
 *   <li>右键取消过滤（GhostItemWidget.mouseClicked 修复）</li>
 *   <li>左键右键都可标记，位置固定不重排（slotIndex + setEntryAt）</li>
 *   <li>精确模式开关按钮（P），区分蜜脾和蜜脾块</li>
 *   <li>修复 ghost slot 下方文字渲染不可见问题（重写 renderForeground）</li>
 *   <li>翻页使用全局 slotIndex，支持跨页位置固定</li>
 * </ul>
 */
public final class GuiAeInputConfig extends GuiWindow {

	private static final int WINDOW_WIDTH = 198;
	private static final int WINDOW_HEIGHT = 170;
	private static final int GRID_COLS = 4;
	private static final int GRID_ROWS = 6;
	private static final int SLOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
	private static final int SLOT_PITCH = 20;
	private static final int GRID_WIDTH = GRID_COLS * SLOT_PITCH - 2;
	private static final int GRID_HEIGHT = GRID_ROWS * SLOT_PITCH - 2;
	private static final int GRID_X = 8;
	private static final int GRID_Y = 44;
	private static final int INFO_X = GRID_X + GRID_WIDTH + 4;
	private static final int INFO_WIDTH = WINDOW_WIDTH - INFO_X - 8;
	private static final int INFO_HEIGHT = GRID_HEIGHT;
	private static final int CTRL_Y = 22;
	private static final int CTRL_BTN_HEIGHT = 14;
	private static final int TOGGLE_BTN_WIDTH = 26;
	private static final int PAGE_BTN_WIDTH = 18;
	private static final int PIN_X_OFFSET = 16;
	private static final int PIN_Y_OFFSET = 6;

	private static final SelectedWindowData AE_INPUT_WINDOW_DATA = new SelectedWindowData(WindowType.UNSPECIFIED);

	static {
		try {
			((CustomWindowData) (Object) AE_INPUT_WINDOW_DATA)
				.productivebeesgenesis$setCustomSaveName("window_ae_input");
		} catch (ClassCastException e) {
			ProductiveBeesGenesis.LOGGER.warn("GuiAeInputConfig Mixin 应用失败，AE 输入窗口位置不持久化", e);
		}
	}

	private final IAe2InputHost host;
	private final BlockPos pos;
	private final GhostItemWidget[] ghostSlots;
	private final MekanismButton toggleBtn;
	private final MekanismButton nbtBtn;
	private final MekanismButton filterModeBtn;
	private final MekanismButton preciseBtn;
	private final MekanismButton prevPageBtn;
	private final MekanismButton nextPageBtn;
	private final MekanismButton clearBtn;
	private final GuiInnerScreen infoScreen;
	private int currentPage;
	/** 最小页数（从配置读取，默认 4），保证位置固定模式下有足够的空页供放置 */
	private int minPages = 4;

	public GuiAeInputConfig(IGuiWrapper gui, int x, int y, IAe2InputHost host, SelectedWindowData windowData) {
		super(gui, x, y, WINDOW_WIDTH, WINDOW_HEIGHT,
				windowData == null ? AE_INPUT_WINDOW_DATA : windowData);
		this.host = host;
		this.pos = host.productivebeesgenesis$getAe2BlockPos();
		this.currentPage = 0;
		this.interactionStrategy = InteractionStrategy.ALL;

		addChild(new GuiPinButton(gui(), relativeX + PIN_X_OFFSET, relativeY + PIN_Y_OFFSET, this));
		addChild(new GuiElementHolder(gui(), relativeX + GRID_X, relativeY + GRID_Y, GRID_WIDTH, GRID_HEIGHT));
		infoScreen = addChild(new GuiInnerScreen(gui(), relativeX + INFO_X, relativeY + GRID_Y, INFO_WIDTH, INFO_HEIGHT));

		// 控件按钮（I/N/F/P）— 缩小宽度避免与翻页按钮重叠
		int btnX = 8;
		toggleBtn = addChild(new CtrlButton(gui(), relativeX + btnX, relativeY + CTRL_Y, TOGGLE_BTN_WIDTH, CTRL_BTN_HEIGHT,
				"I", (e, mx, my) -> { PacketDistributor.sendToServer(new ToggleAeInputPayload(pos)); return true; }));
		toggleBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.toggle.tooltip")));
		btnX += TOGGLE_BTN_WIDTH + 2;
		nbtBtn = addChild(new CtrlButton(gui(), relativeX + btnX, relativeY + CTRL_Y, TOGGLE_BTN_WIDTH, CTRL_BTN_HEIGHT, "N",
				(e, mx, my) -> { PacketDistributor.sendToServer(new ToggleAeInputNbtIgnorePayload(pos)); return true; }));
		nbtBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.nbt_ignore.tooltip")));
		btnX += TOGGLE_BTN_WIDTH + 2;
		filterModeBtn = addChild(new CtrlButton(gui(), relativeX + btnX, relativeY + CTRL_Y, TOGGLE_BTN_WIDTH, CTRL_BTN_HEIGHT, "F",
				(e, mx, my) -> { PacketDistributor.sendToServer(new CycleAeInputFilterModePayload(pos)); return true; }));
		filterModeBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.filter_mode.tooltip")));
		btnX += TOGGLE_BTN_WIDTH + 2;
		// V13: 精确模式切换按钮（P）
		preciseBtn = addChild(new CtrlButton(gui(), relativeX + btnX, relativeY + CTRL_Y, TOGGLE_BTN_WIDTH, CTRL_BTN_HEIGHT, "P",
				(e, mx, my) -> { PacketDistributor.sendToServer(new ToggleAeInputPreciseModePayload(pos)); return true; }));
		preciseBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.precise_mode.tooltip")));

		// 翻页按钮（◀C▶）
		prevPageBtn = addChild(new CtrlButton(gui(), relativeX + WINDOW_WIDTH - 3 * (PAGE_BTN_WIDTH + 2) - 8,
				relativeY + CTRL_Y, PAGE_BTN_WIDTH, CTRL_BTN_HEIGHT, "\u25C0", (e, mx, my) -> { changePage(-1); return true; }));
		prevPageBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.prev_page.tooltip")));
		clearBtn = addChild(new CtrlButton(gui(), relativeX + WINDOW_WIDTH - 2 * (PAGE_BTN_WIDTH + 2) - 8,
				relativeY + CTRL_Y, PAGE_BTN_WIDTH, CTRL_BTN_HEIGHT, "C", (e, mx, my) -> { sendClear(); return true; }));
		clearBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.clear.tooltip")));
		nextPageBtn = addChild(new CtrlButton(gui(), relativeX + WINDOW_WIDTH - (PAGE_BTN_WIDTH + 2) - 8,
				relativeY + CTRL_Y, PAGE_BTN_WIDTH, CTRL_BTN_HEIGHT, "\u25B6", (e, mx, my) -> { changePage(1); return true; }));
		nextPageBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.next_page.tooltip")));

		// 创建 ghost slot 网格 — V13: 使用新构造函数（slotIndex + 回调）
		ghostSlots = new GhostItemWidget[SLOTS_PER_PAGE];
		for (int i = 0; i < SLOTS_PER_PAGE; i++) {
			int row = i / GRID_COLS;
			int col = i % GRID_COLS;
			int slotX = GRID_X + 1 + col * SLOT_PITCH;
			int slotY = GRID_Y + 1 + row * SLOT_PITCH;
			// slotIndex 为页面内索引，回调中转换为全局索引
			ghostSlots[i] = addChild(new GhostItemWidget(gui(), relativeX + slotX, relativeY + slotY, i,
					null, false, this::onSlotPlaced, this::onSlotRemoved));
		}
	}

	@Override
	protected int getTitlePadStart() {
		return 14 + GuiPinButton.WIDTH;
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawTitleText(guiGraphics, Component.translatable("productivebeesgenesis.gui.ae_input_config.title"), 5);

		// 修复 v14 渲染阶段不修改状态：renderForeground 仅执行只读渲染
		// 状态修改（clampCurrentPage/refreshGhostSlots/updateButtonStates）已迁移至 tick()
		Ae2InputFilter filter = getFilter();
		// V15: 显示用非空条目数，分页用容量（位置固定模式下条目可在任意槽位）
		int entryCount = filter == null ? 0 : filter.getNonEmptyEntries().size();
		renderInfoPanel(guiGraphics, filter, entryCount);
	}

	/**
	 * 修复 v14 渲染阶段不修改状态：状态更新统一在 tick 中执行
	 * <br/>
	 * 原 renderForeground 中调用 clampCurrentPage/refreshGhostSlots/updateButtonStates
	 * 会修改 currentPage、ghost slot 内容、按钮文案，存在递归渲染与状态不一致风险。
	 * 迁移至 tick() 后，状态变更与渲染解耦，每 tick 更新一次，渲染阶段只读。
	 */
	@Override
	public void tick() {
		super.tick();
		Ae2InputFilter filter = getFilter();
		int slotCount = filter == null ? 0 : filter.getCapacity();
		clampCurrentPage(slotCount);
		refreshGhostSlots(filter);
		updateButtonStates(filter);
	}

	private void renderInfoPanel(GuiGraphics guiGraphics, Ae2InputFilter filter, int entryCount) {
		int startX = INFO_X + 4;
		int startY = GRID_Y + 4;
		int panelWidth = INFO_WIDTH - 8;

		Component modeLabel = Component.translatable("productivebeesgenesis.gui.ae_input_config.mode");
		drawScaledScrollingString(guiGraphics, modeLabel, startX, startY, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		Component modeText = filter == null
				? Component.literal("--")
				: switch (filter.getFilterMode()) {
					case DISABLED -> Component.translatable("productivebeesgenesis.gui.ae_input_config.filter_mode.disabled");
					case WHITELIST -> Component.translatable("productivebeesgenesis.gui.ae_input_config.filter_mode.whitelist");
					case BLACKLIST -> Component.translatable("productivebeesgenesis.gui.ae_input_config.filter_mode.blacklist");
				};
		drawScaledScrollingString(guiGraphics, modeText, startX, startY + 8, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		boolean inputEnabled = host.productivebeesgenesis$isAeItemInputEnabled();
		Component inputText = Component.translatable(inputEnabled
				? "productivebeesgenesis.gui.ae_input_config.info.input_on"
				: "productivebeesgenesis.gui.ae_input_config.info.input_off");
		drawScaledScrollingString(guiGraphics, inputText, startX, startY + 20, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		boolean nbtIgnore = host.productivebeesgenesis$isAeInputNbtIgnore();
		Component nbtText = Component.translatable(nbtIgnore
				? "productivebeesgenesis.gui.ae_input_config.info.nbt_ignore"
				: "productivebeesgenesis.gui.ae_input_config.info.nbt_match");
		drawScaledScrollingString(guiGraphics, nbtText, startX, startY + 30, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		// V13: 精确模式状态
		boolean precise = filter != null && filter.isPreciseMode();
		Component preciseText = Component.translatable(precise
				? "productivebeesgenesis.gui.ae_input_config.info.precise_on"
				: "productivebeesgenesis.gui.ae_input_config.info.precise_off");
		drawScaledScrollingString(guiGraphics, preciseText, startX, startY + 40, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		// 页码显示（移入信息栏，原顶部中央渲染已移除）
		int slotCount = filter == null ? 0 : filter.getCapacity();
		int total = computeTotalPages(slotCount);
		Component pageLabel = Component.translatable("productivebeesgenesis.gui.ae_input_config.page");
		drawScaledScrollingString(guiGraphics, pageLabel, startX, startY + 52, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);
		Component pageText = Component.literal((currentPage + 1) + "/" + total);
		drawScaledScrollingString(guiGraphics, pageText, startX, startY + 60, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		Component countLabel = Component.translatable("productivebeesgenesis.gui.ae_input_config.entries");
		drawScaledScrollingString(guiGraphics, countLabel, startX, startY + 72, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);
		Component countText = Component.literal(String.valueOf(entryCount));
		drawScaledScrollingString(guiGraphics, countText, startX, startY + 80, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		Component hintLabel = Component.translatable("productivebeesgenesis.gui.ae_input_config.hint");
		drawScaledScrollingString(guiGraphics, hintLabel, startX, startY + 94, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.6F);
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.setColor(1, 1, 1, 1);
	}

	private Ae2InputFilter getFilter() {
		return host.productivebeesgenesis$getAeInputFilter();
	}

	/**
	 * 按当前页填充 24 个 ghost slot — V13: 使用 getEntryAt 获取 beeType + isBlock
	 * <br/>
	 * 位置固定模式：条目在 filter 中的位置 = 全局 slotIndex = currentPage * SLOTS_PER_PAGE + 页内索引。
	 * 空 slot 显示为空，用户可在任意空 slot 放置物品。
	 */
	private void refreshGhostSlots(Ae2InputFilter filter) {
		int start = currentPage * SLOTS_PER_PAGE;
		for (int i = 0; i < SLOTS_PER_PAGE; i++) {
			int globalIdx = start + i;
			if (filter != null) {
				EntryInfo info = filter.getEntryAt(globalIdx);
				if (info != null && info.beeType != null) {
					ghostSlots[i].setEntry(info.beeType, info.isBlock);
					// 设置 tooltip 显示物品名称（按 isBlock 解析对应形态的图标）
					ItemStack icon = BeeInfoHelper.resolveBeeIcon(
							Minecraft.getInstance().level, info.beeType, info.isBlock);
					if (!icon.isEmpty()) {
						ghostSlots[i].setTooltip(Tooltip.create(icon.getHoverName()));
					}
					continue;
				}
			}
			ghostSlots[i].clear();
			ghostSlots[i].setTooltip((Tooltip) null);
		}
	}

	private void updateButtonStates(Ae2InputFilter filter) {
		toggleBtn.setMessage(Component.translatable(host.productivebeesgenesis$isAeItemInputEnabled()
				? "productivebeesgenesis.gui.ae_input_config.status.input_on"
				: "productivebeesgenesis.gui.ae_input_config.status.input_off"));
		nbtBtn.setMessage(Component.translatable(host.productivebeesgenesis$isAeInputNbtIgnore()
				? "productivebeesgenesis.gui.ae_input_config.status.nbt_on"
				: "productivebeesgenesis.gui.ae_input_config.status.nbt_off"));
		String modeKey = filter == null ? "productivebeesgenesis.gui.ae_input_config.status.filter_none"
				: switch (filter.getFilterMode()) {
					case DISABLED -> "productivebeesgenesis.gui.ae_input_config.status.filter_off";
					case WHITELIST -> "productivebeesgenesis.gui.ae_input_config.status.filter_wht";
					case BLACKLIST -> "productivebeesgenesis.gui.ae_input_config.status.filter_blk";
				};
		filterModeBtn.setMessage(Component.translatable(modeKey));
		// V13: 精确模式按钮标签
		boolean precise = filter != null && filter.isPreciseMode();
		preciseBtn.setMessage(Component.translatable(precise
				? "productivebeesgenesis.gui.ae_input_config.status.precise_on"
				: "productivebeesgenesis.gui.ae_input_config.status.precise_off"));
	}

	private void changePage(int delta) {
		Ae2InputFilter filter = getFilter();
		int slotCount = filter == null ? 0 : filter.getCapacity();
		int total = computeTotalPages(slotCount);
		currentPage = (currentPage + delta + total) % total;
	}

	private void clampCurrentPage(int slotCount) {
		int total = computeTotalPages(slotCount);
		if (currentPage >= total) currentPage = total - 1;
		if (currentPage < 0) currentPage = 0;
	}

	/**
	 * 计算总页数 — 取容量所需页数与配置最小页数的较大值
	 * <p>
	 * V15：位置固定模式下分页基于数组容量（非条目数），
	 * 保证用户可在任意槽位放置条目，即使条目很少也至少保留 minPages 页。
	 * 下限保护为 1，避免 minPages 被错误配置为 0 时触发除零异常。
	 *
	 * @param slotCount 当前数组容量
	 * @return 总页数（至少为 1）
	 */
	private int computeTotalPages(int slotCount) {
		int capPages = (int) Math.ceil((double) slotCount / SLOTS_PER_PAGE);
		return Math.max(1, Math.max(minPages, capPages));
	}

	/**
	 * 设置最小页数（从配置读取后调用）
	 *
	 * @param minPages 最小页数（小于 1 会被修正为 1）
	 */
	public void setMinPages(int minPages) {
		this.minPages = Math.max(1, minPages);
	}

	/**
	 * V13: 放置物品到指定 slot — 发送 ADD 操作（含 isBlock 和全局 slotIndex）
	 */
	private void onSlotPlaced(int pageSlotIndex, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(stack);
		if (beeType == null) return;
		boolean isBlock = CombFuzzyMatcher.isCombBlock(stack);
		int globalSlotIndex = currentPage * SLOTS_PER_PAGE + pageSlotIndex;
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.of(beeType), isBlock, globalSlotIndex, OperationType.ADD));
	}

	/**
	 * V13: 移除指定 slot 的条目 — 发送 REMOVE 操作（含全局 slotIndex）
	 */
	private void onSlotRemoved(int pageSlotIndex) {
		int globalSlotIndex = currentPage * SLOTS_PER_PAGE + pageSlotIndex;
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.empty(), false, globalSlotIndex, OperationType.REMOVE));
	}

	private void sendClear() {
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.empty(), false, 0, OperationType.CLEAR));
		currentPage = 0;
	}

	/**
	 * 接收幽灵物品并路由到鼠标下方的 ghost slot — 编程式集成入口
	 */
	public boolean acceptGhostIngredient(ItemStack stack, double mouseX, double mouseY) {
		for (GhostItemWidget slot : ghostSlots) {
			if (slot.contains(mouseX, mouseY)) {
				slot.acceptGhostIngredient(stack);
				return true;
			}
		}
		return false;
	}

	/**
	 * AE2 输入配置窗口内的控制按钮
	 */
	private static final class CtrlButton extends MekanismButton {
		CtrlButton(IGuiWrapper gui, int x, int y, int width, int height, String initialText,
				IClickable onClick) {
			super(gui, x, y, width, height, Component.literal(initialText), onClick);
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
