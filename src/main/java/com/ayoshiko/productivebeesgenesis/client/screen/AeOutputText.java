package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * AE2 输出状态文字 — 注入到 MEK 侧面配置窗口
 * <br/>
 * 38×8 像素文字元素，显示当前 per-tile AE2 输出状态：
 * <ul>
 *   <li>物品类型："AE：开" / "AE：关"</li>
 *   <li>流体类型："AE：开" / "AE：关"</li>
 * </ul>
 * 继承 {@link GuiElement} 以复用 Mekanism 的文字渲染管线，
 * 使用 {@link #drawScaledScrollingString} 右对齐显示，0.8 倍缩放适配有限空间。
 * <p>
 * <b>target 字段</b>：由 {@link AeOutputOverlay#ensureButton} 每帧更新，
 * 点击回调通过 target 获取当前传输类型和方块实体，查询 per-tile 状态。
 */
public class AeOutputText extends GuiElement {

	/** 文字宽度 */
	private static final int TEXT_WIDTH = 38;
	/** 文字高度 */
	private static final int TEXT_HEIGHT = 8;
	/** 文字缩放比例 */
	private static final float TEXT_SCALE = 0.8F;

	/** 当前覆盖层目标 — 由 AeOutputOverlay 每帧更新 */
	AeOutputOverlay.OverlayTarget target;

	/**
	 * 构造 AE2 输出状态文字
	 *
	 * @param gui Mekanism GUI 实例
	 * @param x   文字 X 坐标（相对 GUI 左上角）
	 * @param y   文字 Y 坐标（相对 GUI 左上角）
	 */
	public AeOutputText(GuiMekanism<?> gui, int x, int y) {
		super(gui, x, y, TEXT_WIDTH, TEXT_HEIGHT);
	}

	/**
	 * 渲染前景文字 — 显示 per-tile AE2 输出状态
	 * <br/>
	 * 仅在 canToggle 类型（ITEM/FLUID）时渲染。
	 * 根据 per-tile 状态选择对应语言键，使用右对齐 + 0.8 倍缩放渲染。
	 */
	@Override
	public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		if (this.target == null || !AeOutputOverlay.canToggle(this.target.type())) return;

		TransmissionType type = this.target.type();
		boolean enabled = AeOutputOverlay.isPerTileEnabled(this.target.tile(), type);

		Component text;
		if (type == TransmissionType.FLUID) {
			text = Component.translatable(enabled
					? "productivebeesgenesis.gui.ae2_output.fluid.status.on"
					: "productivebeesgenesis.gui.ae2_output.fluid.status.off");
		} else {
			text = Component.translatable(enabled
					? "productivebeesgenesis.gui.ae2_output.item.status.on"
					: "productivebeesgenesis.gui.ae2_output.item.status.off");
		}
		// 右对齐渲染，0.8 倍缩放适配 38px 宽度
		drawScaledScrollingString(guiGraphics, text, 0, 0, TextAlignment.RIGHT,
				screenTextColor(), getWidth(), 1, false, TEXT_SCALE);
	}
}
