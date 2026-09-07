package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import mekanism.client.render.MekanismRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 蜜蜂槽可视化渲染器
 * <br/>
 * 从 {@code GuiMekApiary} 拆分而来，职责（SRP）：持有三个子渲染器
 * （实体模型 / 名称 / Tooltip）并按几何信息把它们画到正确位置。
 * GUI 主类只保留"何时渲染"，本类负责"渲染什么、怎么批处理"。
 * <p>
 * 性能要点（沿用原实现并强化）：
 * <ul>
 *   <li>整批实体渲染只 begin/endBatch 一次，避免每只蜜蜂都切换深度测试状态</li>
 *   <li>状态灯与名称合并进同一次遍历，空槽提前 continue</li>
 *   <li>坐标换算走 {@link ApiaryBeeSlotGeometry} 缓存实例，不再每帧重算布局常量</li>
 *   <li>Tooltip 命中判定由 O(N) 遍历改为几何整除定位（常数次比较）</li>
 * </ul>
 */
final class ApiaryBeeVisualsRenderer {

	/** 选中槽位高亮色（半透明品红，在蜜蜂模型下方渲染避免遮挡） */
	private static final int SELECTED_HIGHLIGHT_COLOR = 0x40FF00A0;

	private final BeeEntityRenderer entityRenderer = new BeeEntityRenderer();
	private final BeeNameRenderer nameRenderer = new BeeNameRenderer();
	private final BeeTooltipRenderer tooltipRenderer = new BeeTooltipRenderer();

	/**
	 * 渲染蜜蜂可视化内容（选中高亮 → 蜜蜂模型 → 状态灯 → 名称）
	 * <br/>
	 * 在 {@code drawForegroundText} 阶段调用，此时 PoseStack 已被父类
	 * translate(leftPos, topPos)，故一律使用局部坐标。
	 *
	 * @param guiGraphics GUI 绘图上下文
	 * @param tile        蜂箱方块实体
	 * @param geometry    蜜蜂槽几何
	 * @param font        字体（名称渲染用）
	 * @param compactMode 紧凑模式（高行数蜂箱不渲染名称以节省垂直空间）
	 */
	void render(GuiGraphics guiGraphics, TileEntityMekApiary tile, ApiaryBeeSlotGeometry geometry,
			Font font, boolean compactMode) {
		renderSelectionHighlight(guiGraphics, tile, geometry);

		BeeSlot[] beeSlots = tile.getBeeSlots();
		float partialTick = MekanismRenderer.getPartialTick();
		// 先提交整批实体渲染，再绘制状态灯和名称，避免每只蜜蜂都 endBatch() 并切换深度测试
		boolean batchStarted = false;
		try {
			for (int i = 0; i < beeSlots.length; i++) {
				BeeSlot beeSlot = beeSlots[i];
				if (beeSlot.isEmpty()) continue;
				if (!batchStarted) {
					entityRenderer.beginBatch();
					batchStarted = true;
				}
				entityRenderer.renderBee(guiGraphics, geometry.slotX(i), geometry.slotY(i), beeSlot, partialTick);
			}
		} finally {
			if (batchStarted) {
				entityRenderer.endBatch();
			}
		}

		for (int i = 0; i < beeSlots.length; i++) {
			BeeSlot beeSlot = beeSlots[i];
			if (beeSlot.isEmpty()) continue;
			int slotX = geometry.slotX(i);
			int slotY = geometry.slotY(i);
			entityRenderer.renderStatusLight(guiGraphics, slotX, slotY, beeSlot.getState());
			// 紧凑模式（5 行蜂箱）不渲染名称，节省垂直空间适配 scale=4@1080p
			if (!compactMode) {
				nameRenderer.renderName(guiGraphics, slotX, slotY, beeSlot, font, i);
			}
		}
	}

	/** 渲染选中槽位高亮边框（Bug 9）— 在蜜蜂模型之前绘制，避免遮挡模型 */
	private void renderSelectionHighlight(GuiGraphics guiGraphics, TileEntityMekApiary tile,
			ApiaryBeeSlotGeometry geometry) {
		int selectedSlot = tile.getClientSelectedBeeSlot();
		if (selectedSlot < 0 || selectedSlot >= geometry.getSlotCount()) return;
		int selX = geometry.slotX(selectedSlot) - 1;
		int selY = geometry.slotY(selectedSlot) - 1;
		// GuiGraphics.fill 自动管理 blend 状态
		guiGraphics.fill(selX, selY,
				selX + ApiaryGuiLayoutHelper.SLOT + 2, selY + ApiaryGuiLayoutHelper.SLOT + 2,
				SELECTED_HIGHLIGHT_COLOR);
	}

	/**
	 * 渲染鼠标悬停蜜蜂槽位的 Tooltip
	 * <br/>
	 * 命中判定走几何整除定位，只在命中且该格有蜜蜂时才渲染。
	 *
	 * @param mouseX  鼠标 X（屏幕坐标）
	 * @param mouseY  鼠标 Y（屏幕坐标）
	 * @param leftPos GUI 左边界
	 * @param topPos  GUI 上边界
	 * @return true 表示已渲染蜜蜂 Tooltip（调用方应跳过默认 Tooltip）
	 */
	boolean renderTooltipIfHovered(GuiGraphics guiGraphics, TileEntityMekApiary tile,
			ApiaryBeeSlotGeometry geometry, int mouseX, int mouseY, int leftPos, int topPos) {
		int index = geometry.hitTest(leftPos, topPos, mouseX, mouseY);
		if (index < 0) return false;
		BeeSlot[] beeSlots = tile.getBeeSlots();
		if (index >= beeSlots.length) return false;
		BeeSlot beeSlot = beeSlots[index];
		if (beeSlot.isEmpty()) return false;
		tooltipRenderer.renderTooltip(guiGraphics, mouseX, mouseY, beeSlot,
				leftPos + geometry.slotX(index), topPos + geometry.slotY(index));
		return true;
	}

	/** 释放名称渲染缓存 — GUI 关闭时调用，避免跨界面持有过期组件 */
	void clearCaches() {
		nameRenderer.clearCache();
	}
}
