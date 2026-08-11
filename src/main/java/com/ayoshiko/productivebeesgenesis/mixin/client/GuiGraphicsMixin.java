package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * GuiGraphics 物品数量渲染 Mixin — 大数字格式化
	 * <br/>
	 * 拦截 {@link GuiGraphics#renderItemDecorations(Font, ItemStack, int, int, String)}，
	 * 当 text 为 null（即使用默认数量显示）且物品数量 >= 1000 时，
	 * 使用 {@link NumberFormatter} 格式化数量文本（如 1500 → "1.5K"）。
	 * <p>
	 * 原理：在方法 HEAD 处注入，满足条件时以格式化后的 text 递归调用原方法并取消原始调用。
	 * 递归调用中 text 非 null，条件不满足，原方法正常执行，不会产生无限递归。
	 * <p>
	 * 影响范围：仅影响数量 >= 1000 的物品栈渲染（原版上限 64，不影响普通物品）。
	 * 高堆叠场景出现在蜂箱/离心机的分等级输出槽（{@code TieredOutputInventorySlot}）中，
	 * 堆叠上限可达 64 × 配置倍率（最高可达数百万）。
	 * <p>
	 * 线程安全：仅从客户端渲染线程调用，NumberFormatter 本身线程安全。
	 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

	/** 触发格式化的最小数量阈值 */
	@Unique
	private static final int productivebeesgenesis$FORMAT_THRESHOLD = 1000;

	/**
	 * 拦截 renderItemDecorations 5参数重载 — 格式化大数量文本
	 * <br/>
	 * 当 text==null（默认数量显示）且 count >= 1000 时：
	 * <ol>
	 *   <li>使用 NumberFormatter 格式化数量</li>
	 *   <li>以格式化后的 text 调用原方法（递归进入但条件不满足，正常执行）</li>
	 *   <li>取消原始调用</li>
	 * </ol>
	 *
	 * @param font       字体渲染器
	 * @param stack      物品栈
	 * @param x          渲染 x 坐标
	 * @param y          渲染 y 坐标
	 * @param text       自定义文本（null 时使用默认数量）
	 * @param ci         回调信息
	 */
	@Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
			at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$formatLargeItemCount(Font font, ItemStack stack, int x, int y,
			String text, CallbackInfo ci) {
		if (text == null && !stack.isEmpty() && stack.getCount() >= productivebeesgenesis$FORMAT_THRESHOLD) {
			String formatted = NumberFormatter.format(stack.getCount());
			// 递归调用：text 非 null，条件不满足，原方法正常执行
			if (font.width(formatted) > 18) {
				formatted = NumberFormatter.formatCompact(stack.getCount());
			}
			GuiGraphics graphics = (GuiGraphics) (Object) this;
			if (font.width(formatted) <= 18) {
				graphics.renderItemDecorations(font, stack, x, y, formatted);
				ci.cancel();
				return;
			}
			graphics.renderItemDecorations(font, stack, x, y, "");
			int textWidth = font.width(formatted);
			float scale = 18.0F / textWidth;
			graphics.pose().pushPose();
			graphics.pose().translate(x + 18.0F, y + 16.0F, 200.0F);
			graphics.pose().scale(scale, scale, 1.0F);
			graphics.drawString(font, formatted, -textWidth, -font.lineHeight, 0xFFFFFF, true);
			graphics.pose().popPose();
			ci.cancel();
		}
	}
}
