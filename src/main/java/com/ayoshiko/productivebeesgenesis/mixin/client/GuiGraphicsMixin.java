package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.screen.LargeStackCountRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the AE2 honeycomb pull window's fixed-size count label to item stacks
 * rendered by normal inventory slots.
 *
 * <p>The vanilla decoration call is retained for durability bars, while its
 * count text is suppressed and replaced with the shared fixed-scale renderer.
 * This avoids width-dependent font scaling: 1.5K, 123K, and 2.4M all use the
 * same rendered font size.
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

	/** Vanilla does not draw a count for a single-item stack. */
	@Unique
	private static final int productivebeesgenesis$FORMAT_THRESHOLD = 2;

	@Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;"
			+ "Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
			at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$formatLargeItemCount(Font font, ItemStack stack, int x, int y,
			String text, CallbackInfo ci) {
		if (text == null && !stack.isEmpty() && stack.getCount() >= productivebeesgenesis$FORMAT_THRESHOLD) {
			GuiGraphics graphics = (GuiGraphics) (Object) this;
			// Keep vanilla durability-bar rendering, but prevent it from drawing a second count label.
			graphics.renderItemDecorations(font, stack, x, y, "");
			LargeStackCountRenderer.renderCountAt(graphics, font, x, y, stack.getCount());
			ci.cancel();
		}
	}
}
