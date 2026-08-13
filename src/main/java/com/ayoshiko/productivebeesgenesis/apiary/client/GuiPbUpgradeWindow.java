package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.network.PbUpgradeExtractPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.DigitalButton;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.client.render.IFancyFontRenderer;
import mekanism.common.MekanismLang;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
	 * PB 升级窗口
	 * <br/>
	 * 1:1 复刻 MEK {@code GuiUpgradeWindow} 的布局：左侧已安装列表 + 右侧信息屏 +
	 * 输入/输出虚拟槽 + 卸载按钮。
	 * <p>
	 * Bug 7：未选择时使用 {@link IFancyFontRenderer.WrappedTextRenderer} 自动换行渲染
	 * "未选择" 文字，与 MEK 原版行为一致，避免长文字溢出右屏。
	 * <p>
	 * 重构：将 {@code TileEntityMekApiary} 替换为 {@link IPbUpgradeProvider}，
	 * 使本组件可被蜂箱与离心机共用。{@link GuiPbSupportedUpgrades} 的升级类型集合
	 * 由 provider 的支持范围动态生成。
	 */
public class GuiPbUpgradeWindow extends GuiWindow {

	public static final int WINDOW_WIDTH = 198;

	private static final int LEFT_PANEL_X = 6;
	private static final int PANEL_Y = 18;
	private static final int LEFT_PANEL_WIDTH = 108;
	private static final int PANEL_HEIGHT = 50;
	private static final int RIGHT_PANEL_WIDTH = 59;
	private static final int BUTTON_Y = 54;
	private static final int BUTTON_HEIGHT = 12;

	private final IPbUpgradeProvider provider;
	private final PbUpgradeType[] supportedTypes;
	private final GuiPbUpgradeList scrollList;
	private final GuiInnerScreen rightScreen;
	private final DigitalButton removeButton;
	private long msSelected;

	/**
	 * 已选择升级的标题文本渲染器缓存 — Bug 7
	 * <br/>
	 * 按 {@link PbUpgradeType} 缓存 WrappedTextRenderer，避免每帧重建。
	 */
	private final Map<PbUpgradeType, IFancyFontRenderer.WrappedTextRenderer> titleRenderers =
			new EnumMap<>(PbUpgradeType.class);

	/** 未选择时显示的文字渲染器（自动换行） */
	private final IFancyFontRenderer.WrappedTextRenderer noSelectionRenderer;

	public GuiPbUpgradeWindow(IGuiWrapper gui, int x, int y, IPbUpgradeProvider provider, SelectedWindowData windowData) {
		super(gui, x, y, WINDOW_WIDTH, calculateHeight(gui, provider), windowData);
		this.provider = provider;
		this.supportedTypes = collectSupportedTypes(provider);
		interactionStrategy = InteractionStrategy.ALL;

		scrollList = addChild(new GuiPbUpgradeList(gui, relativeX + LEFT_PANEL_X, relativeY + PANEL_Y,
				LEFT_PANEL_WIDTH, PANEL_HEIGHT, provider, () -> {
			updateEnabledButtons();
			msSelected = Util.getMillis();
		}));

		addChild(new GuiPbSupportedUpgrades(gui, relativeX + LEFT_PANEL_X, relativeY + 68, supportedTypes));

		rightScreen = addChild(new GuiInnerScreen(gui, scrollList.getRelativeRight(), relativeY + PANEL_Y,
				RIGHT_PANEL_WIDTH, PANEL_HEIGHT));

		addChild(new GuiProgress(provider::getClientInstallingProgress, ProgressType.INSTALLING, gui,
				rightScreen.getRelativeRight() + 3, relativeY + 37));
		// Bug 3：移除卸载进度条，卸载为瞬时操作无动画（与MEK原版一致）

		removeButton = addChild(new DigitalButton(gui, scrollList.getRelativeRight() + 1, relativeY + BUTTON_Y,
				56, BUTTON_HEIGHT, MekanismLang.UPGRADE_UNINSTALL, (element, mouseX, mouseY) -> {
					if (scrollList.hasSelection()) {
						PbUpgradeType selected = scrollList.getSelection();
						if (selected != null) {
							boolean removeAll = Screen.hasShiftDown();
							PacketDistributor.sendToServer(new PbUpgradeExtractPayload(
									provider.getBlockPos(), selected.getId(), removeAll));
						}
						return true;
					}
					return false;
				}));
		removeButton.setTooltip(MekanismLang.UPGRADE_UNINSTALL_TOOLTIP);

		if (gui() instanceof AbstractContainerScreen<?> containerScreen) {
			if (containerScreen.getMenu() instanceof IPbUpgradeSlotContainer slotContainer) {
				var inputSlot = slotContainer.getPbUpgradeInputSlot();
				var outputSlot = slotContainer.getPbUpgradeOutputSlot();
				if (inputSlot != null) {
					addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui,
							rightScreen.getRelativeRight() + 2, relativeY + 18, inputSlot));
				}
				if (outputSlot != null) {
					addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui,
							rightScreen.getRelativeRight() + 2, relativeY + 72, outputSlot));
				}
			}
		}

		// Bug 7：未选择文字使用 WrappedTextRenderer 自动换行
		noSelectionRenderer = new IFancyFontRenderer.WrappedTextRenderer(this,
				Component.translatable("gui.productivebeesgenesis.pb_upgrade_window.no_selection"));

		updateEnabledButtons();
	}

	/**
	 * 收集 provider 支持的升级类型（用于 {@link GuiPbSupportedUpgrades} 显示）
	 */
	private static PbUpgradeType[] collectSupportedTypes(IPbUpgradeProvider provider) {
		List<PbUpgradeType> types = new ArrayList<>();
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (type.isBuiltin()) continue;
			if (provider.isPbUpgradeSupported(type)) {
				types.add(type);
			}
		}
		return types.toArray(new PbUpgradeType[0]);
	}

	/**
	 * 计算窗口高度 — 根据 provider 支持的升级类型数量动态计算
	 */
	private static int calculateHeight(IGuiWrapper gui, IPbUpgradeProvider provider) {
		PbUpgradeType[] types = collectSupportedTypes(provider);
		return 76 + Math.max(18, 12 * GuiPbSupportedUpgrades.calculateNeededRows(gui, types));
	}

	private void updateEnabledButtons() {
		removeButton.active = scrollList.hasSelection();
	}

	@Override
	public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawTitleText(guiGraphics, Component.translatable("gui.productivebeesgenesis.pb_upgrade_window.title"), 5);

		int screenWidth = rightScreen.getWidth() - 2;

		if (scrollList.hasSelection()) {
			PbUpgradeType selectedType = scrollList.getSelection();
			if (selectedType != null) {
				int amount = provider.getPbUpgradeInstalledCount(selectedType);
				int max = provider.getPbUpgradeLimit(selectedType);

				// Bug 7：标题使用 WrappedTextRenderer 自动换行（长名称如 "Productivity Ω" 可正常显示）
				IFancyFontRenderer.WrappedTextRenderer titleRenderer = titleRenderers.get(selectedType);
				if (titleRenderer == null) {
					Component name = Component.translatable(selectedType.getNameKey());
					titleRenderer = new IFancyFontRenderer.WrappedTextRenderer(this, name);
					titleRenderers.put(selectedType, titleRenderer);
				}
				int lines = titleRenderer.renderWithScale(guiGraphics,
						rightScreen.getRelativeX() + 2, rightScreen.getRelativeY() + 2,
						TextAlignment.LEFT, selectedType.getColor(), screenWidth - 2, 0.6F);

				int textY = rightScreen.getRelativeY() + 2 + 6 * lines;
				Component countText = Component.translatable("gui.productivebeesgenesis.pb_upgrade_window.count",
						amount, max);
				rightScreen.drawScaledScrollingString(guiGraphics, countText, 0, textY - rightScreen.getRelativeY(),
						TextAlignment.LEFT, screenTextColor(), screenWidth, 2, true, 0.6F, msSelected);

				textY += 8;
				Component desc = Component.translatable(selectedType.getDescriptionKey());
				rightScreen.drawScaledScrollingString(guiGraphics, desc, 0, textY - rightScreen.getRelativeY() + 2,
						TextAlignment.LEFT, screenTextColor(), screenWidth, 2, true, 0.6F, msSelected);
			}
		} else {
			// Bug 7：未选择时用 WrappedTextRenderer 自动换行渲染
			noSelectionRenderer.renderWithScale(guiGraphics,
					rightScreen.getRelativeX() + 2, rightScreen.getRelativeY() + 2,
					TextAlignment.LEFT, screenTextColor(), screenWidth - 2, 0.8F);
		}
	}

	@Override
	public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.setColor(1, 1, 1, 1);
	}
}
