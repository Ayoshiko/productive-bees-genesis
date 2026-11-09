package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.blaze3d.systems.RenderSystem;
import mekanism.client.gui.GuiUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 扩展资源窗口的独立像素材质；图标与文字不烘焙进窗口，便于本地化和资源包替换。 */
final class ResourceGuiTextures {
	static final int TEXT = 0xFF30383D, MUTED = 0xFF555F65;
	static final ResourceLocation WINDOW = texture("window");
	static final ResourceLocation WIDGETS = texture("widgets");
	private ResourceGuiTextures() {}
	private static ResourceLocation texture(String name) {
		return ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "textures/gui/resource/" + name + ".png");
	}
	static void sprite(GuiGraphics graphics, int x, int y, int u, int v, int width, int height) {
		graphics.setColor(1, 1, 1, 1);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		graphics.blit(WIDGETS, x, y, u, v, width, height, 256, 128);
		RenderSystem.disableBlend();
	}
	static void button(GuiGraphics graphics, int x, int y, int width, int height, int state) {
		graphics.setColor(1, 1, 1, 1);
		GuiUtils.blitNineSlicedSized(graphics, WIDGETS, x, y, width, height, 2, 16, 16, state * 16, 32, 256, 128);
	}
}
