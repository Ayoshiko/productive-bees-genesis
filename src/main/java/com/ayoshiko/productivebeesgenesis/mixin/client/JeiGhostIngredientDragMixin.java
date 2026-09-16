package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.render.MekGuiBlockItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mezz.jei.gui.ghost.GhostIngredientDrag;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 标记 JEI 当前正在绘制的拖拽预览，使 GuiGraphics 能与窗口内部方块图标区分开。
 * <p>
 * JEI 在 MEK 窗口绘制流程结束前触发 {@code drawItem}，此时 PoseStack 已经带有窗口 Z
 * 偏移，不能再通过当前 Z 值判断是否为覆盖层。上下文使用线程局部状态，渲染返回时立即
 * 清理，避免影响同一帧的 JEI 列表图标。使用单一方法包裹和 {@code try/finally}，
 * 即使 JEI 或其它渲染 Mixin 抛出异常也不会把拖拽状态泄漏到后续渲染。
 */
@Mixin(GhostIngredientDrag.class)
public class JeiGhostIngredientDragMixin {

	@WrapMethod(method = "drawItem(Lnet/minecraft/client/gui/GuiGraphics;II)V", require = 0)
	private void productivebeesgenesis$wrapDragRender(GuiGraphics guiGraphics, int mouseX, int mouseY,
			Operation<Void> original) {
		MekGuiBlockItemRenderContext.beginJeiDragRender();
		try {
			original.call(guiGraphics, mouseX, mouseY);
		} finally {
			MekGuiBlockItemRenderContext.endJeiDragRender();
		}
	}
}
