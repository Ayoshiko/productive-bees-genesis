package com.ayoshiko.productivebeesgenesis.mixin.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 原生 I18n 与文本组件各持有语言对象，已应用名称必须同时发布。 */
@Mixin(I18n.class)
public interface DesignerLanguageAccess {
	@Invoker("setLanguage")
	static void productivebeesgenesis$setLanguage(Language language) {
		throw new AssertionError();
	}
}
