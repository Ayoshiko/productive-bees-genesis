package com.ayoshiko.productivebeesgenesis.client.screen;

import appeng.api.stacks.AEItemKey;
import appeng.client.gui.Icon;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter.EntryInfo;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.CombFuzzyMatcher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.network.CycleAeInputFilterModePayload;
import com.ayoshiko.productivebeesgenesis.network.OpenAeInputConfigPayload;
import com.ayoshiko.productivebeesgenesis.network.SetAeInputFilterEntryPayload.OperationType;
import com.ayoshiko.productivebeesgenesis.network.SetAeInputFilterEntryPayload;
import com.ayoshiko.productivebeesgenesis.network.ToggleAllAeInputFilterUnlimitedPayload;
import com.ayoshiko.productivebeesgenesis.network.ToggleAeInputNbtIgnorePayload;
import com.ayoshiko.productivebeesgenesis.network.ToggleAeInputPayload;
import com.ayoshiko.productivebeesgenesis.network.ToggleAeInputPreciseModePayload;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.IntConsumer;

/**
	 * AE2 input pull configuration window.
	 * <p>
	 * Layout mirrors the AE2LT overloaded ME interface (OverloadedInterfaceScreen):
	 * a 9 x 2 grid where each cell stacks the amount/unlimited gear button on top,
	 * the marker (ghost) slot 18px below and a network output slot 18px further
	 * down. The output slot shows the pullable network stock and lets the player
	 * take items from / put items back into the ME network directly, matching the
	 * ProxiedStorageInv behaviour of AE2LT (gear on top, marker row, output row).
	 */
public final class GuiAeInputConfig extends GuiWindow {

	private static final int WINDOW_WIDTH = 260;
	private static final int WINDOW_HEIGHT = 200;
	private static final int GRID_COLS = 9;
	private static final int GRID_ROWS = 2;
	private static final int SLOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
	private static final int CELL_PITCH_X = 18;
	private static final int CELL_PITCH_Y = 60;
	private static final int GEAR_SIZE = 16;
	private static final int GRID_WIDTH = GRID_COLS * CELL_PITCH_X;
	private static final int GRID_HEIGHT = GRID_ROWS * CELL_PITCH_Y;
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
			ProductiveBeesGenesis.LOGGER.warn("GuiAeInputConfig window position persistence mixin unavailable", e);
		}
	}

	private final IAe2InputHost host;
	private final BlockPos pos;
	private final GhostItemWidget[] ghostSlots;
	private final StockGearButton[] stockButtons;
	private final OutputSlotWidget[] outputSlots;
	private final MekanismButton toggleBtn;
	private final MekanismButton nbtBtn;
	private final MekanismButton filterModeBtn;
	private final MekanismButton preciseBtn;
	private final MekanismButton globalGearBtn;
	private final MekanismButton prevPageBtn;
	private final MekanismButton nextPageBtn;
	private final MekanismButton clearBtn;
	private final GuiInnerScreen infoScreen;
	private int currentPage;
	private int stockSyncTicks;
	/** Minimum page count (from config), guarantees spare pages in fixed-position mode. */
	private int minPages = 2;

	public GuiAeInputConfig(IGuiWrapper gui, int x, int y, IAe2InputHost host, SelectedWindowData windowData) {
		super(gui, x, y, WINDOW_WIDTH, WINDOW_HEIGHT,
				windowData == null ? AE_INPUT_WINDOW_DATA : windowData);
		this.host = host;
		this.pos = host.productivebeesgenesis$getAe2BlockPos();
		this.currentPage = 0;
		this.stockSyncTicks = 0;
		this.interactionStrategy = InteractionStrategy.ALL;

		addChild(new GuiPinButton(gui(), relativeX + PIN_X_OFFSET, relativeY + PIN_Y_OFFSET, this));
		addChild(new GuiElementHolder(gui(), relativeX + GRID_X, relativeY + GRID_Y, GRID_WIDTH, GRID_HEIGHT));
		infoScreen = addChild(new GuiInnerScreen(gui(), relativeX + INFO_X, relativeY + GRID_Y, INFO_WIDTH, INFO_HEIGHT));

		// Control buttons (I/N/F/P)
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
		preciseBtn = addChild(new CtrlButton(gui(), relativeX + btnX, relativeY + CTRL_Y, TOGGLE_BTN_WIDTH, CTRL_BTN_HEIGHT, "P",
				(e, mx, my) -> { PacketDistributor.sendToServer(new ToggleAeInputPreciseModePayload(pos)); return true; }));
		preciseBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.precise_mode.tooltip")));
		btnX += TOGGLE_BTN_WIDTH + 2;
		// 与下方逐槽齿轮一致：16×16 方形按钮，垂直方向相对 14px 控制行居中
		globalGearBtn = addChild(new GlobalGearButton(gui(), relativeX + btnX, relativeY + CTRL_Y - 1,
				this::onOpenGlobalAmount,
				() -> PacketDistributor.sendToServer(new ToggleAllAeInputFilterUnlimitedPayload(pos))));
		globalGearBtn.setTooltip(Tooltip.create(Component.translatable(
				"productivebeesgenesis.gui.ae_input_config.global_gear.tooltip")));

		// Page buttons (prev / clear / next)
		prevPageBtn = addChild(new CtrlButton(gui(), relativeX + WINDOW_WIDTH - 3 * (PAGE_BTN_WIDTH + 2) - 8,
				relativeY + CTRL_Y, PAGE_BTN_WIDTH, CTRL_BTN_HEIGHT, "\u25C0", (e, mx, my) -> { changePage(-1); return true; }));
		prevPageBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.prev_page.tooltip")));
		clearBtn = addChild(new CtrlButton(gui(), relativeX + WINDOW_WIDTH - 2 * (PAGE_BTN_WIDTH + 2) - 8,
				relativeY + CTRL_Y, PAGE_BTN_WIDTH, CTRL_BTN_HEIGHT, "C", (e, mx, my) -> { sendClear(); return true; }));
		clearBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.clear.tooltip")));
		nextPageBtn = addChild(new CtrlButton(gui(), relativeX + WINDOW_WIDTH - (PAGE_BTN_WIDTH + 2) - 8,
				relativeY + CTRL_Y, PAGE_BTN_WIDTH, CTRL_BTN_HEIGHT, "\u25B6", (e, mx, my) -> { changePage(1); return true; }));
		nextPageBtn.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.next_page.tooltip")));

		// AE2LT overloaded-interface cells: gear (top) / marker (middle) / output (bottom)
		ghostSlots = new GhostItemWidget[SLOTS_PER_PAGE];
		stockButtons = new StockGearButton[SLOTS_PER_PAGE];
		outputSlots = new OutputSlotWidget[SLOTS_PER_PAGE];
		for (int i = 0; i < SLOTS_PER_PAGE; i++) {
			int col = i % GRID_COLS;
			int row = i / GRID_COLS;
			int cellX = GRID_X + col * CELL_PITCH_X;
			int cellY = GRID_Y + row * CELL_PITCH_Y;
			ghostSlots[i] = addChild(new GhostItemWidget(gui(), relativeX + cellX,
					relativeY + cellY + 18, i,
					null, false, this::onSlotPlaced, this::onSlotRemoved));
			stockButtons[i] = addChild(new StockGearButton(gui(), relativeX + cellX,
					relativeY + cellY, GEAR_SIZE, i,
					this::onSlotConfigureAmount, this::onSlotToggleUnlimited));
			outputSlots[i] = addChild(new OutputSlotWidget(gui(), relativeX + cellX,
					relativeY + cellY + 36, pos, i));
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

		// Read-only rendering: state updates (clampCurrentPage/refreshGhostSlots/
		// updateButtonStates) happen in tick() to avoid recursive rendering.
		Ae2InputFilter filter = getFilter();
		int entryCount = filter == null ? 0 : filter.getNonEmptyEntries().size();
		renderInfoPanel(guiGraphics, filter, entryCount);
	}

	/**
	 * State refresh moved to tick(): avoids recursive rendering and keeps the
	 * ghost/output slots and buttons in sync with the server filter snapshot.
	 */
	@Override
	public void tick() {
		super.tick();
		Ae2InputFilter filter = getFilter();
		if (filter != null && filter.hasNetworkStockEntries()) {
			if (++stockSyncTicks >= 10) {
				stockSyncTicks = 0;
				PacketDistributor.sendToServer(new OpenAeInputConfigPayload(pos));
			}
		} else {
			stockSyncTicks = 0;
		}
		int slotCount = filter == null ? 0 : filter.getCapacity();
		clampCurrentPage(slotCount);
		refreshGhostSlots(filter);
		updateButtonStates(filter);
	}

	private void renderInfoPanel(GuiGraphics guiGraphics, Ae2InputFilter filter, int entryCount) {
		int startX = INFO_X + 4;
		int startY = GRID_Y + 4;
		int panelWidth = INFO_WIDTH - 8;

		// Right info panel mirrors the two-row grid height (INFO_HEIGHT = GRID_HEIGHT).
		Component modeText = filter == null
				? Component.translatable("productivebeesgenesis.gui.ae_input_config.mode")
						.copy().append(" --")
				: switch (filter.getFilterMode()) {
					case DISABLED -> Component.translatable("productivebeesgenesis.gui.ae_input_config.mode")
							.copy().append(" ").append(Component.translatable(
									"productivebeesgenesis.gui.ae_input_config.filter_mode.disabled"));
					case WHITELIST -> Component.translatable("productivebeesgenesis.gui.ae_input_config.mode")
							.copy().append(" ").append(Component.translatable(
									"productivebeesgenesis.gui.ae_input_config.filter_mode.whitelist"));
					case BLACKLIST -> Component.translatable("productivebeesgenesis.gui.ae_input_config.mode")
							.copy().append(" ").append(Component.translatable(
									"productivebeesgenesis.gui.ae_input_config.filter_mode.blacklist"));
				};
		drawScaledScrollingString(guiGraphics, modeText, startX, startY, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		boolean inputEnabled = host.productivebeesgenesis$isAeItemInputEnabled();
		Component inputText = Component.translatable(inputEnabled
				? "productivebeesgenesis.gui.ae_input_config.info.input_on"
				: "productivebeesgenesis.gui.ae_input_config.info.input_off");
		drawScaledScrollingString(guiGraphics, inputText, startX, startY + 9, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		boolean nbtIgnore = host.productivebeesgenesis$isAeInputNbtIgnore();
		Component nbtText = Component.translatable(nbtIgnore
				? "productivebeesgenesis.gui.ae_input_config.info.nbt_ignore"
				: "productivebeesgenesis.gui.ae_input_config.info.nbt_match");
		drawScaledScrollingString(guiGraphics, nbtText, startX, startY + 18, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		boolean precise = filter != null && filter.isPreciseMode();
		Component preciseText = Component.translatable(precise
				? "productivebeesgenesis.gui.ae_input_config.info.precise_on"
				: "productivebeesgenesis.gui.ae_input_config.info.precise_off");
		drawScaledScrollingString(guiGraphics, preciseText, startX, startY + 27, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		int slotCount = filter == null ? 0 : filter.getCapacity();
		int total = computeTotalPages(slotCount);
		Component pageText = Component.translatable("productivebeesgenesis.gui.ae_input_config.page")
				.copy().append(" ").append((currentPage + 1) + "/" + total);
		drawScaledScrollingString(guiGraphics, pageText, startX, startY + 36, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		Component countText = Component.translatable("productivebeesgenesis.gui.ae_input_config.entries")
				.copy().append(" ").append(Integer.toString(entryCount));
		drawScaledScrollingString(guiGraphics, countText, startX, startY + 45, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);

		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder != null) {
			Component rateText = Component.translatable("productivebeesgenesis.gui.ae_input_config.info.rate",
					Integer.toString(holder.getCachedInputRatePerTick()));
			drawScaledScrollingString(guiGraphics, rateText, startX, startY + 54, TextAlignment.LEFT,
					screenTextColor(), panelWidth, 3, false, 0.7F);

			Component intervalText = Component.translatable("productivebeesgenesis.gui.ae_input_config.info.interval",
					Integer.toString(holder.getCachedInputIntervalTicks()));
			drawScaledScrollingString(guiGraphics, intervalText, startX, startY + 63, TextAlignment.LEFT,
					screenTextColor(), panelWidth, 3, false, 0.7F);

			Component cooldownText = Component.translatable(
					"productivebeesgenesis.gui.ae_input_config.info.cooldown",
					Integer.toString(holder.getInputPullCooldownTicks()));
			drawScaledScrollingString(guiGraphics, cooldownText, startX, startY + 72, TextAlignment.LEFT,
					screenTextColor(), panelWidth, 3, false, 0.7F);
		}

		int unlimitedCount = 0;
		if (filter != null) {
			for (Ae2InputFilter.IndexedEntry ie : filter.getNonEmptyEntries()) {
				if (filter.isDirectUnlimitedAt(ie.index())) unlimitedCount++;
			}
		}
		Component unlimitedText = Component.translatable(
				"productivebeesgenesis.gui.ae_input_config.info.unlimited_count",
				Integer.toString(unlimitedCount));
		drawScaledScrollingString(guiGraphics, unlimitedText, startX, startY + 81, TextAlignment.LEFT,
				screenTextColor(), panelWidth, 3, false, 0.7F);
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
	 * Fills the current page's ghost slots from the fixed-position filter array.
	 * <br/>
	 * Position-fixed mode: entry index = currentPage * SLOTS_PER_PAGE + page index.
	 */
	private void refreshGhostSlots(Ae2InputFilter filter) {
		int start = currentPage * SLOTS_PER_PAGE;
		for (int i = 0; i < SLOTS_PER_PAGE; i++) {
			int globalIdx = start + i;
			outputSlots[i].clear();
			outputSlots[i].setTooltip((Tooltip) null);
			stockButtons[i].visible = false;
			stockButtons[i].setTooltip((Tooltip) null);
			if (filter != null) {
				EntryInfo info = filter.getEntryAt(globalIdx);
				if (info != null && info.directFingerprint != null) {
					AEItemKey key = filter.getResolvedDirectKey(globalIdx);
					if (key == null && Minecraft.getInstance().level != null) {
						key = Ae2ItemFingerprint.decode(info.directFingerprint,
								Minecraft.getInstance().level.registryAccess());
					}
					if (key != null) filter.resolveDirectKey(globalIdx, key);
					long amount = filter.getDirectAmountAt(globalIdx);
					long visibleAmount = filter.getDirectVisibleAmountAt(globalIdx);
					boolean networkStock = filter.isDirectUnlimitedAt(globalIdx);
					if (key != null) {
						ItemStack icon = key.toStack(1);
						ghostSlots[i].setDirectEntry(icon, info.directFingerprint);
						ghostSlots[i].setTooltip(Tooltip.create(icon.getHoverName()));
						outputSlots[i].setDirectEntry(icon,
								networkStock ? visibleAmount : amount, networkStock, globalIdx);
						outputSlots[i].setTooltip(Tooltip.create(Component.translatable(
								"productivebeesgenesis.gui.ae_input_config.output_slot.tooltip",
								formatCompactAmount(networkStock ? visibleAmount : amount))));
					} else {
						ghostSlots[i].setDirectFingerprint(info.directFingerprint);
						ghostSlots[i].setTooltip(Tooltip.create(Component.literal(info.directFingerprint)));
					}
					stockButtons[i].visible = true;
					stockButtons[i].active = true;
					stockButtons[i].setNetworkStock(networkStock);
					String tooltipKey = networkStock
							? "productivebeesgenesis.gui.ae_input_config.stock_button.on"
							: "productivebeesgenesis.gui.ae_input_config.stock_button.off";
					stockButtons[i].setTooltip(Tooltip.create(Component.translatable(
							tooltipKey, networkStock ? visibleAmount : amount)));
					continue;
				}
				if (info != null && info.beeType != null) {
					ghostSlots[i].setEntry(info.beeType, info.isBlock);
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
	 * Total pages = max(config minimum pages, capacity pages).
	 * Fixed-position mode pages are based on the array capacity so entries can
	 * be placed in any slot; at least minPages pages are kept.
	 */
	private int computeTotalPages(int slotCount) {
		int capPages = (int) Math.ceil((double) slotCount / SLOTS_PER_PAGE);
		return Math.max(1, Math.max(minPages, capPages));
	}

	public void setMinPages(int minPages) {
		this.minPages = Math.max(1, minPages);
	}

	/** Sends an ADD operation (with isBlock and global slot index) for a placed item. */
	private void onSlotPlaced(int pageSlotIndex, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(stack);
		if (beeType == null) return;
		boolean isBlock = CombFuzzyMatcher.isCombBlock(stack);
		int globalSlotIndex = currentPage * SLOTS_PER_PAGE + pageSlotIndex;
		Optional<String> directKey = Optional.empty();
		try {
			if (Minecraft.getInstance().level != null) {
				String fingerprint = Ae2ItemFingerprint.encode(AEItemKey.of(stack),
						Minecraft.getInstance().level.registryAccess());
				if (!fingerprint.isBlank()) directKey = Optional.of(fingerprint);
			}
		} catch (RuntimeException ignored) {
			// Keep the legacy bee-type entry when an AE key cannot be created.
		}
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.of(beeType), directKey, isBlock, globalSlotIndex, OperationType.ADD));
		if (directKey.isPresent()) {
			ghostSlots[pageSlotIndex].setDirectEntry(stack, directKey.get());
		}
	}

	/** Sends a REMOVE operation for the given page-local slot. */
	private void onSlotRemoved(int pageSlotIndex) {
		int globalSlotIndex = currentPage * SLOTS_PER_PAGE + pageSlotIndex;
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.empty(), false, globalSlotIndex, OperationType.REMOVE));
	}

	/** Opens the MEK amount editor for one exact AE entry. */
	private void onSlotConfigureAmount(int pageSlotIndex) {
		Ae2InputFilter filter = getFilter();
		int globalSlotIndex = currentPage * SLOTS_PER_PAGE + pageSlotIndex;
		if (filter == null || !filter.isDirectEntry(globalSlotIndex)) return;

		AEItemKey key = filter.getResolvedDirectKey(globalSlotIndex);
		EntryInfo info = filter.getEntryAt(globalSlotIndex);
		if (key == null && info != null && info.directFingerprint != null
				&& Minecraft.getInstance().level != null) {
			key = Ae2ItemFingerprint.decode(info.directFingerprint,
					Minecraft.getInstance().level.registryAccess());
			if (key != null) filter.resolveDirectKey(globalSlotIndex, key);
		}
		ItemStack icon = key == null ? ItemStack.EMPTY : key.toStack(1);
		gui().addWindow(new GuiAeInputAmountConfig(gui(), relativeX + 38, relativeY + 18,
				pos, globalSlotIndex, icon, filter.getDirectAmountAt(globalSlotIndex)));
	}

	/** Opens the global amount editor applied to all direct entries (no marker required). */
	private void onOpenGlobalAmount() {
		Ae2InputFilter filter = getFilter();
		long initial = 0L;
		if (filter != null) {
			for (int i = 0; i < filter.getCapacity(); i++) {
				if (filter.isDirectEntry(i)) {
					initial = filter.getDirectAmountAt(i);
					break;
				}
			}
		}
		gui().addWindow(new GuiAeInputAmountConfig(gui(), relativeX + 38, relativeY + 18,
				pos, ItemStack.EMPTY, initial));
	}

	/** Toggle the unlimited-provide marker for a direct AE2 entry. */
	private void onSlotToggleUnlimited(int pageSlotIndex) {
		int globalSlotIndex = currentPage * SLOTS_PER_PAGE + pageSlotIndex;
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.empty(), false, globalSlotIndex, OperationType.TOGGLE_UNLIMITED));
	}

	private void sendClear() {
		PacketDistributor.sendToServer(new SetAeInputFilterEntryPayload(
				pos, Optional.empty(), false, 0, OperationType.CLEAR));
		currentPage = 0;
	}

	/** Routes ghost ingredients (e.g. JEI) to the marker slot under the cursor. */
	public boolean acceptGhostIngredient(ItemStack stack, double mouseX, double mouseY) {
		for (GhostItemWidget slot : ghostSlots) {
			if (slot.contains(mouseX, mouseY)) {
				slot.acceptGhostIngredient(stack);
				return true;
			}
		}
		return false;
	}

	private static String formatCompactAmount(long amount) {
		long safeAmount = Math.max(0L, amount);
		if (safeAmount < 1_000L) return Long.toString(safeAmount);
		long divisor = 1_000L;
		char[] suffixes = {'K', 'M', 'G', 'T', 'P', 'E'};
		for (char suffix : suffixes) {
			if (divisor > Long.MAX_VALUE / 1_000L || safeAmount < divisor * 1_000L) {
				long whole = safeAmount / divisor;
				if (whole >= 100L) return whole + Character.toString(suffix);
				long tenth = (safeAmount % divisor) / Math.max(1L, divisor / 10L);
				return whole + "." + tenth + suffix;
			}
			divisor *= 1_000L;
		}
		return Long.toString(safeAmount);
	}

	/** Compact MEK-style gear button using AE2's familiar stock-configuration icon. */
	private static final class StockGearButton extends MekanismButton {
		private boolean networkStock;

		StockGearButton(IGuiWrapper gui, int x, int y, int size, int slotIndex,
				IntConsumer configureCallback, IntConsumer toggleCallback) {
			super(gui, x, y, size, size, Component.empty(), (element, mouseX, mouseY) -> {
				if (Screen.hasShiftDown()) {
					toggleCallback.accept(slotIndex);
				} else {
					configureCallback.accept(slotIndex);
				}
				return true;
			});
			setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
			visible = false;
		}

		void setNetworkStock(boolean networkStock) {
			this.networkStock = networkStock;
		}

		@Override
		public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
			if (!visible) return;
			super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
			Icon icon = networkStock || isMouseOver(mouseX, mouseY) ? Icon.COG : Icon.COG_DISABLED;
			icon.getBlitter()
					.dest(relativeX + 1, relativeY + 1, width - 2, height - 2)
					.zOffset(4)
					.blit(guiGraphics);
			if (networkStock) {
				guiGraphics.drawString(Minecraft.getInstance().font, "\u221E",
						relativeX + 9, relativeY + 7, 0x00FF00, true);
			}
		}
	}

	/**
	 * 全配置页全局齿轮按钮（无需标记物品即可使用）
	 * <br/>
	 * 普通点击：打开应用到全部直连条目的数量编辑器；
	 * Shift+点击：一键切换全部直连条目的无限拉取状态。
	 */
	private static final class GlobalGearButton extends MekanismButton {

		GlobalGearButton(IGuiWrapper gui, int x, int y, Runnable configureCallback, Runnable toggleCallback) {
			super(gui, x, y, GEAR_SIZE, GEAR_SIZE, Component.empty(), (element, mouseX, mouseY) -> {
				if (Screen.hasShiftDown()) {
					toggleCallback.run();
				} else {
					configureCallback.run();
				}
				return true;
			});
			setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
			visible = true;
			active = true;
		}

		@Override
		public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
			if (!visible) return;
			super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
			Icon icon = isMouseOver(mouseX, mouseY) ? Icon.COG : Icon.COG_DISABLED;
			icon.getBlitter()
					.dest(relativeX + 1, relativeY + 1, width - 2, height - 2)
					.zOffset(4)
					.blit(guiGraphics);
		}
	}

	/** Compact text button used for the AE2 input control row. */
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
