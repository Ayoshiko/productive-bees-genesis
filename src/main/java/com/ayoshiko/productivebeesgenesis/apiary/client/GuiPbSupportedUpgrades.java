package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.tooltip.TooltipUtils;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.client.render.IFancyFontRenderer;
import mekanism.common.lib.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
	 * PB支持的升级显示组件
	 * <br/>
	 * 1:1复刻MEK GuiSupportedUpgrades的布局：
	 * <ul>
	 *   <li>"Supported:"文字标签在左侧</li>
	 *   <li>12x12图标在0.75F缩放下排列</li>
	 *   <li>不支持的升级半透明灰显</li>
	 *   <li>鼠标悬停显示tooltip</li>
	 * </ul>
	 * <p>
	 * 重构：将 UPGRADE_TYPES 与 SUPPORTED_TYPES 改为实例字段，由构造函数注入。
	 * 蜂箱版传入 8 种升级类型，离心机版仅传入 6 种（无 GENE_SAMPLER/BLOCK）。
	 */
public class GuiPbSupportedUpgrades extends GuiElement {

	private static final Component SUPPORTED =
		Component.translatable("gui.productivebeesgenesis.pb_upgrade_window.supported");
	private static final int ELEMENT_WIDTH = 167;
	private static final int PADDED_ELEMENT_WIDTH = ELEMENT_WIDTH - 2;
	private static final int ELEMENT_SIZE = 12;
	private static final int ROW_ROOM = PADDED_ELEMENT_WIDTH / ELEMENT_SIZE;

	/**
	 * 蜂箱支持的升级类型（排除内置 SIMULATION）。
	 * Bug 5：包含 α/β/γ/Ω 四级产量升级。
	 * Bug 3：包含 TIME_2（双倍时间升级）。
	 * 包含 BLOCK（蜜脾块升级，独立于 Ω）。
	 */
	private static final PbUpgradeType[] APIARY_UPGRADE_TYPES = {
			PbUpgradeType.PRODUCTIVITY,
			PbUpgradeType.PRODUCTIVITY_2,
			PbUpgradeType.PRODUCTIVITY_3,
			PbUpgradeType.PRODUCTIVITY_4,
			PbUpgradeType.TIME,
			PbUpgradeType.TIME_2,
			PbUpgradeType.GENE_SAMPLER,
			PbUpgradeType.BLOCK
	};

	/** 本实例显示的升级类型（构造时注入） */
	private final PbUpgradeType[] upgradeTypes;

	/** 本实例支持安装的升级类型集合（与 upgradeTypes 一致） */
	private final List<PbUpgradeType> supportedTypes;

	private static int getFirstRowStart(IFancyFontRenderer fontRenderer) {
		return Math.min(fontRenderer.font().width(SUPPORTED) + 1, PADDED_ELEMENT_WIDTH);
	}

	private static int getFirstRowRoom(int firstRowStart) {
		return (PADDED_ELEMENT_WIDTH - firstRowStart) / ELEMENT_SIZE;
	}

	/**
	 * 计算蜂箱升级窗口所需的行数
	 * <br/>
	 * 保留静态方法供 {@link GuiPbUpgradeWindow} 计算窗口高度时调用，
	 * 内部使用蜂箱默认的升级类型集合。
	 */
	public static int calculateNeededRows(IFancyFontRenderer fontRenderer) {
		return calculateNeededRows(fontRenderer, APIARY_UPGRADE_TYPES);
	}

	/**
	 * 计算指定升级类型集合所需的行数
	 *
	 * @param fontRenderer 字体渲染器
	 * @param types        升级类型数组
	 * @return 所需行数
	 */
	public static int calculateNeededRows(IFancyFontRenderer fontRenderer, PbUpgradeType[] types) {
		int count = types.length;
		int firstRowRoom = getFirstRowRoom(getFirstRowStart(fontRenderer));
		if (count <= firstRowRoom) {
			return 1;
		}
		count -= firstRowRoom;
		return 2 + count / ROW_ROOM;
	}

	private final int firstRowRoom;
	private final int firstRowStart;

	private List<Component> lastInfo = Collections.emptyList();
	@Nullable
	private Tooltip lastTooltip;
	@Nullable
	private ScreenRectangle cachedTooltipRect;

	/**
	 * 蜂箱版构造函数 — 使用默认的蜂箱升级类型集合
	 */
	public GuiPbSupportedUpgrades(IGuiWrapper gui, int x, int y) {
		this(gui, x, y, APIARY_UPGRADE_TYPES);
	}

	/**
	 * 通用构造函数 — 由调用方提供升级类型集合
	 *
	 * @param gui         GUI 包装器
	 * @param x           相对 X 坐标
	 * @param y           相对 Y 坐标
	 * @param upgradeTypes 显示的升级类型数组
	 */
	public GuiPbSupportedUpgrades(IGuiWrapper gui, int x, int y, PbUpgradeType[] upgradeTypes) {
		super(gui, x, y, ELEMENT_WIDTH, ELEMENT_SIZE * calculateNeededRows(gui, upgradeTypes) + 2);
		this.upgradeTypes = upgradeTypes;
		this.supportedTypes = Arrays.asList(upgradeTypes);
		this.firstRowStart = getFirstRowStart(this);
		this.firstRowRoom = getFirstRowRoom(this.firstRowStart);
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		renderBackgroundTexture(
			guiGraphics,
			GuiElementHolder.HOLDER,
			GuiElementHolder.HOLDER_SIZE,
			GuiElementHolder.HOLDER_SIZE
		);
		int backgroundColor = Color.argb(GuiElementHolder.getBackgroundColor()).alpha(0.5).argb();
		for (int i = 0; i < upgradeTypes.length; i++) {
			PbUpgradeType upgrade = upgradeTypes[i];
			UpgradePos pos = getUpgradePos(i);
			int xPos = relativeX + 1 + pos.x;
			int yPos = relativeY + 1 + pos.y;
			var stack = PbUpgradeInventorySlot.getRepresentativeStack(upgrade);
			if (!stack.isEmpty()) {
				gui().renderItem(guiGraphics, stack, xPos, yPos, 0.75F);
			}
			if (!supportedTypes.contains(upgrade) || stack.isEmpty()) {
				guiGraphics.fill(RenderType.guiGhostRecipeOverlay(), xPos, yPos, xPos + ELEMENT_SIZE, yPos + ELEMENT_SIZE,
					backgroundColor);
			}
		}
	}

	@Override
	public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawScrollingString(guiGraphics, SUPPORTED, 0, 3, TextAlignment.LEFT, titleTextColor(), 2, false);
	}

	@NotNull
	@Override
	protected ScreenRectangle getTooltipRectangle(int mouseX, int mouseY) {
		return cachedTooltipRect == null ? super.getTooltipRectangle(mouseX, mouseY) : cachedTooltipRect;
	}

	@Override
	public void updateTooltip(int mouseX, int mouseY) {
		for (int i = 0; i < upgradeTypes.length; i++) {
			UpgradePos pos = getUpgradePos(i);
			if (mouseX >= getX() + 1 + pos.x && mouseX < getX() + 1 + pos.x + ELEMENT_SIZE &&
				mouseY >= getY() + 1 + pos.y && mouseY < getY() + 1 + pos.y + ELEMENT_SIZE) {
				PbUpgradeType upgrade = upgradeTypes[i];
				Component upgradeName = Component.translatable(upgrade.getNameKey()).withColor(upgrade.getColor());
				List<Component> info;
				if (supportedTypes.contains(upgrade)) {
					info = List.of(upgradeName, Component.translatable(upgrade.getDescriptionKey()));
				} else {
					info = List.of(
							Component.translatable("gui.productivebeesgenesis.pb_upgrade_window.not_supported",
									upgradeName).withColor(0xFFFF0000),
							Component.translatable(upgrade.getDescriptionKey())
					);
				}
				if (!info.equals(lastInfo)) {
					lastInfo = info;
					lastTooltip = TooltipUtils.create(info);
					cachedTooltipRect = new ScreenRectangle(getX() + 1 + pos.x, getY() + 1 + pos.y, ELEMENT_SIZE, ELEMENT_SIZE);
				}
				setTooltip(lastTooltip);
				return;
			}
		}
		lastInfo = Collections.emptyList();
		cachedTooltipRect = null;
		setTooltip(lastTooltip = null);
	}

	private UpgradePos getUpgradePos(int index) {
		int row = index < firstRowRoom ? 0 : 1 + (index - firstRowRoom) / ROW_ROOM;
		if (row == 0) {
			return new UpgradePos(firstRowStart + (index % firstRowRoom) * ELEMENT_SIZE, 0);
		}
		index -= firstRowRoom;
		return new UpgradePos((index % ROW_ROOM) * ELEMENT_SIZE, row * ELEMENT_SIZE);
	}

	private record UpgradePos(int x, int y) {
	}
}
