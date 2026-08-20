package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.GuiTexturedElement;
import mekanism.client.gui.tooltip.TooltipUtils;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.util.MekanismUtils.ResourceType;
import mekanism.common.util.MekanismUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
	 * PB升级类型点击列表
	 * <br/>
	 * 1:1复刻MEK原版 {@link mekanism.client.gui.element.scroll.GuiUpgradeScrollList} 的滚动条和元素渲染风格，
	 * 显示已安装的PB升级类型，支持点击选择和高亮。
	 * <p>
	 * <b>滚动条贴图</b>：使用MEK原版 {@code scroll_list.png}（6×6像素），1:1复刻
	 * {@link mekanism.client.gui.element.scroll.GuiScrollableElement#drawScrollBar} 的blit渲染：
	 * <ul>
	 *   <li>顶部边框：6×1像素（barX-1, barY-1）</li>
	 *   <li>中间背景：1×maxBarHeight像素纵向平铺（barX-1, barY）</li>
	 *   <li>底部边框：6×1像素（barX-1, barY+maxBarHeight）</li>
	 *   <li>滑块：4×4像素（barX, barY+getScroll()）</li>
	 * </ul>
	 * <p>
	 * <b>滚动条位置</b>（1:1对齐MEK原版）：
	 * <ul>
	 *   <li>barXShift = width - 6（滚动条在列表最右侧，留2px右边距）</li>
	 *   <li>barYShift = 2</li>
	 *   <li>barWidth = 4, barHeight = 4（滑块固定高度）</li>
	 *   <li>maxBarHeight = height - 4</li>
	 * </ul>
	 * <p>
	 * <b>元素渲染</b>（1:1对齐MEK原版GuiInstallableScrollList）：
	 * <ul>
	 *   <li>物品图标X偏移=3，Y偏移=3，缩放=0.5F</li>
	 *   <li>文字X偏移=13，Y偏移=3，缩放=1.0F，颜色=titleTextColor</li>
	 *   <li>文字最大宽度 = barXShift - 16 = width - 22</li>
	 * </ul>
	 * <p>
	 * <b>滚动交互</b>（1:1复刻MEK原版GuiScrollableElement）：
	 * <ul>
	 *   <li>使用 {@code scroll}（double 0-1）表示滚动比例</li>
	 *   <li>使用 {@code dragOffset} 保留鼠标在滑块上的初始偏移</li>
	 *   <li>{@code syncFrom} 仅在双方都需要滚动条时复制scroll值</li>
	 * </ul>
	 * <p>
	 * 重构：将 {@code TileEntityMekApiary} 替换为 {@link IPbUpgradeProvider}，
	 * 使本组件可被蜂箱与离心机共用，遵循依赖倒置原则。
	 */
public class GuiPbUpgradeList extends GuiTexturedElement {

	private static final int ELEMENT_HEIGHT = 12;
	private static final int ITEM_SIZE = 16;
	/** 元素图标X偏移（1:1对齐MEK原版GuiInstallableScrollList.renderElements的 relativeX + 3） */
	private static final int ITEM_X_OFFSET = 3;
	/** 元素图标Y偏移（1:1对齐MEK原版GuiInstallableScrollList.renderElements的 relativeY + 3） */
	private static final int ITEM_Y_OFFSET = 3;
	/** 文字X偏移（1:1对齐MEK原版GuiInstallableScrollList.drawNameText的 x=13） */
	private static final int TEXT_X_OFFSET = 13;
	/** 文字Y偏移（1:1对齐MEK原版GuiInstallableScrollList.renderForeground的 3 + i*elementHeight） */
	private static final int TEXT_Y_OFFSET = 3;

	/** MEK原版滚动条贴图（6×6像素），1:1复刻GuiScrollList.SCROLL_LIST */
	private static final ResourceLocation SCROLL_LIST =
			MekanismUtils.getResource(ResourceType.GUI, "scroll_list.png");
	private static final int SCROLL_TEXTURE_WIDTH = 6;
	private static final int SCROLL_TEXTURE_HEIGHT = 6;

	/** MEK原版升级选择贴图（100×36像素，3行12像素高），1:1复刻GuiUpgradeScrollList.UPGRADE_SELECTION */
	private static final ResourceLocation UPGRADE_SELECTION =
			MekanismUtils.getResource(ResourceType.GUI, "upgrade_selection.png");
	private static final int SELECTION_TEXTURE_WIDTH = 100;
	private static final int SELECTION_TEXTURE_HEIGHT = 36;

	/** 滚动条X位置偏移（1:1对齐MEK原版 barXShift = width - 6） */
	private static final int BAR_X_SHIFT = 6;
	/** 滚动条Y位置偏移（1:1对齐MEK原版 barYShift = 2） */
	private static final int BAR_Y_SHIFT = 2;
	/** 滚动条宽度（1:1对齐MEK原版 barWidth = 4） */
	private static final int BAR_WIDTH = 4;
	/** 滑块固定高度（1:1对齐MEK原版 barHeight = 4） */
	private static final int BAR_HEIGHT = 4;
	/** 轨道最大高度偏移（1:1对齐MEK原版 maxBarHeight = height - 4） */
	private static final int MAX_BAR_HEIGHT_OFFSET = 4;

	private final IPbUpgradeProvider provider;
	private final Runnable onSelectionChange;
	@Nullable
	private PbUpgradeType selectedType;
	/** 滚动比例（0.0~1.0），1:1对齐MEK原版 GuiScrollableElement.scroll */
	private double scroll;
	/** 拖拽时鼠标相对于滑块顶部的偏移（像素），1:1对齐MEK原版 GuiScrollableElement.dragOffset */
	private int dragOffset;
	/** 轨道最大高度（height - 4），1:1对齐MEK原版 maxBarHeight */
	private final int maxBarHeight;

	private List<Component> lastInfo = new ArrayList<>();
	@Nullable
	private Tooltip lastTooltip;
	@Nullable
	private ScreenRectangle cachedTooltipRect;

	public GuiPbUpgradeList(IGuiWrapper gui, int x, int y, int width, int height,
							IPbUpgradeProvider provider, Runnable onSelectionChange) {
		super(SCROLL_LIST, gui, x, y, width, height);
		this.provider = provider;
		this.onSelectionChange = onSelectionChange;
		this.maxBarHeight = height - MAX_BAR_HEIGHT_OFFSET;
	}

	/**
	 * 滚动条X坐标（动态计算） — 窗口拖动时 relativeX 会更新，final 字段无法跟随
	 */
	private int getBarX() {
		return relativeX + width - BAR_X_SHIFT;
	}

	/**
	 * 滚动条Y坐标（动态计算） — 窗口拖动时 relativeY 会更新，final 字段无法跟随
	 */
	private int getBarY() {
		return relativeY + BAR_Y_SHIFT;
	}

	@Nullable
	public PbUpgradeType getSelection() {
		return selectedType;
	}

	public boolean hasSelection() {
		return selectedType != null;
	}

	public void clearSelection() {
		if (selectedType != null) {
			selectedType = null;
			onSelectionChange.run();
		}
	}

	/**
	 * 周期性校验选中类型是否仍有效 — 修复 v14 渲染阶段不修改状态
	 * <br/>
	 * 原在 {@link #renderForeground} 中调用 {@link #clearSelection()} 会破坏渲染纯读语义
	 * （每帧触发，可能导致递归重绘或部分渲染基于旧状态）。Mekanism 的 GuiElement.tick()
	 * 由 GuiMekanism.containerTick 递归调用，是非渲染阶段的正确更新时机。
	 */
	@Override
	public void tick() {
		super.tick();
		if (selectedType != null && provider.getPbUpgradeInstalledCount(selectedType) == 0) {
			clearSelection();
		}
	}

	private List<PbUpgradeType> getInstalledTypes() {
		List<PbUpgradeType> types = new ArrayList<>();
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (!type.isBuiltin() && provider.getPbUpgradeInstalledCount(type) > 0) {
				types.add(type);
			}
		}
		return types;
	}

	private int getMaxElements() {
		return getInstalledTypes().size();
	}

	private int getFocusedElements() {
		return (height - 2) / ELEMENT_HEIGHT;
	}

	/** 1:1对齐MEK原版 GuiScrollableElement.needsScrollBars */
	private boolean needsScrollBars() {
		return getMaxElements() > getFocusedElements();
	}

	/** 1:1对齐MEK原版 GuiScrollableElement.getElements */
	private int getElements() {
		return getMaxElements() - getFocusedElements();
	}

	/** 1:1对齐MEK原版 GuiScrollableElement.getMax（滑块可移动范围） */
	private int getMax() {
		return maxBarHeight - BAR_HEIGHT;
	}

	/** 1:1对齐MEK原版 GuiScrollableElement.getScroll — 计算滑块在轨道中的像素位置 */
	private int getScroll() {
		int max = getMax();
		return Mth.clamp((int) (scroll * max), 0, max);
	}

	/** 1:1对齐MEK原版 GuiScrollableElement.getCurrentSelection — 根据scroll比例计算当前选中索引 */
	private int getCurrentSelection() {
		return needsScrollBars() ? (int) ((getElements() + 0.5) * scroll) : 0;
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
		// 1:1对齐MEK原版GuiScrollList.drawBackground — 滚动条在元素之前渲染
		drawScrollBar(guiGraphics);

		// 元素渲染：启用裁剪防止超出边界
		int clipLeft = getX() + 1;
		int clipTop = getY() + 1;
		int clipRight = getRight() - 1;
		int clipBottom = getBottom() - 1;
		guiGraphics.enableScissor(clipLeft, clipTop, clipRight, clipBottom);

		List<PbUpgradeType> installed = getInstalledTypes();
		int currentSelection = getCurrentSelection();
		int max = Math.min(getFocusedElements(), installed.size());

		try {
			// 第一阶段：彩色条+选中/悬停状态纹理（1:1对齐MEK原版GuiInstallableScrollList.renderElements）
			// 贴图3行：j=0悬停、j=1默认、j=2选中，通过MekanismRenderer.color应用类型颜色
			for (int i = 0; i < max; i++) {
				PbUpgradeType type = installed.get(currentSelection + i);
				int multipliedElement = i * ELEMENT_HEIGHT;
				int shiftedY = getY() + 1 + multipliedElement;

				int j = 1;
				if (type == selectedType) {
					j = 2;
				} else if (mouseX >= getX() + 1 && mouseX < getX() + width - BAR_X_SHIFT - 1
						&& mouseY >= shiftedY && mouseY < shiftedY + ELEMENT_HEIGHT) {
					j = 0;
				}

				MekanismRenderer.color(guiGraphics, type.getColor());
				guiGraphics.blit(UPGRADE_SELECTION, relativeX + 1, relativeY + 1 + multipliedElement,
						0, ELEMENT_HEIGHT * j, SELECTION_TEXTURE_WIDTH, ELEMENT_HEIGHT,
						SELECTION_TEXTURE_WIDTH, SELECTION_TEXTURE_HEIGHT);
				MekanismRenderer.resetColor(guiGraphics);
			}

			// 第二阶段：物品图标（独立循环，避免renderItem切换纹理状态导致后续blit取错纹理）
			for (int i = 0; i < max; i++) {
				PbUpgradeType type = installed.get(currentSelection + i);
				var stack = PbUpgradeInventorySlot.getRepresentativeStack(type);
				if (!stack.isEmpty()) {
					gui().renderItem(guiGraphics, stack, relativeX + ITEM_X_OFFSET,
							relativeY + ITEM_Y_OFFSET + i * ELEMENT_HEIGHT - 1, 0.5F);
				}
			}
		} finally {
			guiGraphics.disableScissor();
		}
	}

	@Override
	public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		// 1:1对齐MEK原版GuiInstallableScrollList.renderForeground — 文字渲染
		int clipLeft = getX() + 1;
		int clipTop = getY() + 1;
		int clipRight = getRight() - 1;
		int clipBottom = getBottom() - 1;
		guiGraphics.enableScissor(clipLeft, clipTop, clipRight, clipBottom);

		List<PbUpgradeType> installed = getInstalledTypes();
		int currentSelection = getCurrentSelection();
		int max = Math.min(getFocusedElements(), installed.size());

		try {
			for (int i = 0; i < max; i++) {
				PbUpgradeType type = installed.get(currentSelection + i);
				// 1:1对齐MEK原版GuiInstallableScrollList.renderForeground：y = 3 + i * elementHeight
				int textY = TEXT_Y_OFFSET + i * ELEMENT_HEIGHT;
				Component name = Component.translatable(type.getNameKey());
				// 1:1对齐MEK原版GuiUpgradeScrollList.drawName：缩放1.0F，颜色titleTextColor()
				// 最大宽度 = barXShift - 16 = (width - 6) - 16 = width - 22
				drawScaledScrollingString(guiGraphics, name, TEXT_X_OFFSET, textY, TextAlignment.LEFT,
						titleTextColor(), width - BAR_X_SHIFT - 16, 0, false, 1.0F);
			}
		} finally {
			guiGraphics.disableScissor();
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY, int button) {
		super.onClick(mouseX, mouseY, button);
		// 1:1对齐MEK原版GuiScrollableElement.onClick — 滚动条点击启动拖拽
		int scroll = getScroll();
		int barX = getBarX();
		int barY = getBarY();
		int x = getGuiLeft() + barX;
		int y = getGuiTop() + barY;
		if (mouseX >= x && mouseX <= x + BAR_WIDTH && mouseY >= y + scroll && mouseY <= y + scroll + BAR_HEIGHT) {
			if (needsScrollBars()) {
				double yAxis = mouseY - getGuiTop();
				dragOffset = (int) (yAxis - (scroll + barY));
				setDragging(true);
			} else {
				this.scroll = 0;
			}
			return;
		}
		// 1:1对齐MEK原版GuiScrollList.onClick — 元素点击选择
		if (mouseX >= getX() + 1 && mouseX < getX() + width - BAR_X_SHIFT - 1
				&& mouseY >= getY() + 1 && mouseY < getBottom() - 1) {
			int index = getCurrentSelection();
			int focused = getFocusedElements();
			int maxElements = getMaxElements();
			for (int i = 0; i < focused && index + i < maxElements; i++) {
				int shiftedY = getY() + 1 + ELEMENT_HEIGHT * i;
				if (mouseY >= shiftedY && mouseY <= shiftedY + ELEMENT_HEIGHT) {
					selectedType = getInstalledTypes().get(index + i);
					onSelectionChange.run();
					return;
				}
			}
			clearSelection();
		}
	}

	@Override
	public void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
		super.onDrag(mouseX, mouseY, deltaX, deltaY);
		// 1:1对齐MEK原版GuiScrollableElement.onDrag
		if (isDragging() && needsScrollBars()) {
			double yAxis = mouseY - getGuiTop();
			int max = getMax();
			if (max > 0) {
				scroll = Mth.clamp((yAxis - getBarY() - dragOffset) / max, 0, 1);
			}
		}
	}

	@Override
	public void onRelease(double mouseX, double mouseY) {
		super.onRelease(mouseX, mouseY);
		// 1:1对齐MEK原版GuiScrollableElement.onRelease
		dragOffset = 0;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double xDelta, double yDelta) {
		// 1:1对齐MEK原版GuiScrollList.mouseScrolled — 使用adjustScroll逻辑
		return isMouseOver(mouseX, mouseY) && adjustScroll(yDelta) || super.mouseScrolled(mouseX, mouseY, xDelta, yDelta);
	}

	/** 1:1对齐MEK原版GuiScrollableElement.adjustScroll */
	private boolean adjustScroll(double delta) {
		if (delta != 0 && needsScrollBars()) {
			int elements = getElements();
			if (elements > 0) {
				if (delta > 0) {
					delta = 1;
				} else {
					delta = -1;
				}
				scroll = Mth.clamp(scroll - delta / elements, 0, 1);
				return true;
			}
		}
		return false;
	}

	@Override
	public void updateTooltip(int mouseX, int mouseY) {
		List<PbUpgradeType> installed = getInstalledTypes();
		int currentSelection = getCurrentSelection();
		int focused = getFocusedElements();
		for (int i = 0; i < focused && currentSelection + i < installed.size(); i++) {
			int y = getY() + 1 + i * ELEMENT_HEIGHT;
			if (mouseX >= getX() + 1 && mouseX < getX() + width - BAR_X_SHIFT - 1 &&
					mouseY >= y && mouseY < y + ELEMENT_HEIGHT) {
				PbUpgradeType type = installed.get(currentSelection + i);
			List<Component> info = List.of(
					Component.translatable(type.getNameKey()).withColor(type.getColor()),
					PbUpgradeTooltipHelper.descriptionComponent(type)
			);
				if (!info.equals(lastInfo)) {
					lastInfo = info;
					lastTooltip = TooltipUtils.create(info);
					cachedTooltipRect = new ScreenRectangle(getX() + 1, y, width - BAR_X_SHIFT - 2, ELEMENT_HEIGHT);
				}
				setTooltip(lastTooltip);
				return;
			}
		}
		lastInfo = new ArrayList<>();
		cachedTooltipRect = null;
		setTooltip(lastTooltip = null);
	}

	@NotNull
	@Override
	protected ScreenRectangle getTooltipRectangle(int mouseX, int mouseY) {
		return cachedTooltipRect == null ? super.getTooltipRectangle(mouseX, mouseY) : cachedTooltipRect;
	}

	// ===== 滚动条渲染（1:1复刻MEK原版GuiScrollableElement.drawScrollBar） =====

	/**
	 * 渲染滚动条轨道与滑块（1:1复刻MEK原版GuiScrollableElement.drawScrollBar）
	 * <br/>
	 * 使用MEK原版scroll_list.png贴图（6×6像素），通过blit渲染：
	 * <ul>
	 *   <li>顶部边框：6×1像素，从贴图(0,0)取</li>
	 *   <li>中间背景：1×maxBarHeight像素纵向平铺，从贴图(0,1)取</li>
	 *   <li>底部边框：6×1像素，从贴图(0,0)取</li>
	 *   <li>滑块：4×4像素，从贴图(0,2)取</li>
	 * </ul>
	 */
	private void drawScrollBar(GuiGraphics guiGraphics) {
		ResourceLocation texture = getResource();
		int tw = SCROLL_TEXTURE_WIDTH;
		int th = SCROLL_TEXTURE_HEIGHT;
		// 动态计算barX/barY，使滚动条跟随窗口拖动（relativeX/relativeY在拖动时更新）
		int barX = getBarX();
		int barY = getBarY();
		// 顶部边框：barX-1, barY-1，6×1像素
		guiGraphics.blit(texture, barX - 1, barY - 1, 0, 0, tw, 1, tw, th);
		// 中间背景：barX-1, barY，1×maxBarHeight像素纵向平铺
		guiGraphics.blit(texture, barX - 1, barY, tw, maxBarHeight, 0, 1, tw, 1, tw, th);
		// 底部边框：barX-1, barY+maxBarHeight，6×1像素
		guiGraphics.blit(texture, barX - 1, barY + maxBarHeight, 0, 0, tw, 1, tw, th);
		// 滑块：barX, barY+getScroll()，4×4像素
		guiGraphics.blit(texture, barX, barY + getScroll(), 0, 2, BAR_WIDTH, BAR_HEIGHT, tw, th);
	}

	@Override
	public boolean hasPersistentData() {
		return true;
	}

	/**
	 * 同步旧组件状态 — 1:1对齐MEK原版GuiScrollableElement.syncFrom
	 * <br/>
	 * 仅在双方都需要滚动条时复制scroll值，避免从需要滚动条切换到不需要时残留无效scroll。
	 */
	@Override
	public void syncFrom(GuiElement element) {
		super.syncFrom(element);
		if (element instanceof GuiPbUpgradeList old) {
			if (needsScrollBars() && old.needsScrollBars()) {
				scroll = old.scroll;
			}
			selectedType = old.selectedType;
			onSelectionChange.run();
		}
	}
}
