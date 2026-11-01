package com.ayoshiko.productivebeesgenesis.mixin.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 编辑器只读取原生末级字形，不调用可能被全局字体模组替换的文本渲染。 */
@Mixin(Font.class)
public interface DesignerFontAccess {
	@Invoker("getFontSet")
	FontSet productivebeesgenesis$fontSet(ResourceLocation id);
}
