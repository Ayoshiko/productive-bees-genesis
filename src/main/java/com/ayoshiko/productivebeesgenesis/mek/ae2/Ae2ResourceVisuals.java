package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 仅由已通过 AE2 守卫的客户端展示调用，复用键所属模组的渲染器。 */
public final class Ae2ResourceVisuals {
	private Ae2ResourceVisuals() {}
	public static Component name(Object key) { return AEKeyRendering.getDisplayName((AEKey) key); }
	public static void draw(GuiGraphics graphics, Object key) {
		AEKeyRendering.drawInGui(Minecraft.getInstance(), graphics, 0, 0, (AEKey) key);
	}
}
