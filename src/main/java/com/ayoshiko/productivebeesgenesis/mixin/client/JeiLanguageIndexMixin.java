package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.screen.authoring.BeeDesignerClient;
import mezz.jei.gui.ingredients.IngredientFilter;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 名称批次发布后沿 JEI 原重建入口更新一次，避免继续查询旧名称与拼音。 */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class JeiLanguageIndexMixin {
	@Unique
	private Language productivebeesgenesis$indexedLanguage;
	@Unique
	private long productivebeesgenesis$indexedNamesGeneration;
	@Unique
	private long productivebeesgenesis$indexedResourceGeneration;

	@Shadow
	public abstract void rebuildItemFilter();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void productivebeesgenesis$rememberLanguage(CallbackInfo callback) {
		productivebeesgenesis$indexedLanguage = Language.getInstance();
		productivebeesgenesis$indexedNamesGeneration = BeeDesignerClient.registeredNamesGeneration();
		productivebeesgenesis$indexedResourceGeneration = BeeDesignerClient.resourceNamesGeneration();
	}

	// getElements 是各版共用的查询入口，新版的搜索补全还可单独读取元素信息。
	@Inject(method = {"getElements()Ljava/util/List;", "getAllElementInfos()Ljava/util/Collection;"}, at = @At("HEAD"))
	private void productivebeesgenesis$refreshLanguageIndex(CallbackInfoReturnable<?> callback) {
		Language language = Language.getInstance();
		long namesGeneration = BeeDesignerClient.registeredNamesGeneration();
		long resourceGeneration = BeeDesignerClient.resourceNamesGeneration();
		if (productivebeesgenesis$indexedLanguage != language || productivebeesgenesis$indexedNamesGeneration != namesGeneration
				|| productivebeesgenesis$indexedResourceGeneration != resourceGeneration) {
			productivebeesgenesis$indexedLanguage = language;
			productivebeesgenesis$indexedNamesGeneration = namesGeneration;
			productivebeesgenesis$indexedResourceGeneration = resourceGeneration;
			rebuildItemFilter();
		}
	}
}
