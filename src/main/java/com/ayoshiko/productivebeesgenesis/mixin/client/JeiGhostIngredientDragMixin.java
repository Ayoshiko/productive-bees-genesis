package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.render.MekGuiBlockItemRenderContext;
import mezz.jei.gui.ghost.GhostIngredientDrag;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 标记 JEI 当前正在绘制的拖拽预览，使 GuiGraphics 能与窗口内部方块图标区分开。
 * <p>
 * JEI 在 MEK 窗口绘制流程结束前触发 {@code drawItem}，此时 PoseStack 已经带有窗口 Z
 * 偏移，不能再通过当前 Z 值判断是否为覆盖层。上下文使用线程局部状态，渲染返回时立即
 * 清理，避免影响同一帧的 JEI 列表图标。
 */
@Mixin(GhostIngredientDrag.class)
public class JeiGhostIngredientDragMixin {

	@Inject(method = "drawItem(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"))
	private void productivebeesgenesis$beginDragRender(GuiGraphics guiGraphics, int mouseX, int mouseY,
			CallbackInfo callbackInfo) {
		MekGuiBlockItemRenderContext.beginJeiDragRender();
	}

	@Inject(method = "drawItem(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("RETURN"))
	private void productivebeesgenesis$endDragRender(GuiGraphics guiGraphics, int mouseX, int mouseY,
			CallbackInfo callbackInfo) {
		MekGuiBlockItemRenderContext.endJeiDragRender();
	}
}
