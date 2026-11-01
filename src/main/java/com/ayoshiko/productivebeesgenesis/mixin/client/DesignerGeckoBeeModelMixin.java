package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerPreviewData;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/** 预览不污染世界缓存；同蜂种模型声明变化时撤销原模型和动画条目。 */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "cy.jdkdigital.productivebees.compat.geckolib.client.render.model.GeckoBeeModel", remap = false)
public abstract class DesignerGeckoBeeModelMixin {
	@Shadow
	private Map<ResourceLocation, ResourceLocation> modelCache;
	@Shadow
	private Map<ResourceLocation, ResourceLocation> animationCache;
	@Unique
	private Map<ResourceLocation, CompoundTag> productivebeesgenesis$modelDeclarations;
	@Inject(method = "getModelResource(Lcy/jdkdigital/productivebees/common/entity/bee/GeckoBee;)Lnet/minecraft/resources/ResourceLocation;",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$previewModel(@Coerce ConfigurableBee bee, CallbackInfoReturnable<ResourceLocation> callback) {
		if (((DesignerPreviewData) bee).productivebeesgenesis$isPreview()) {
			var data = bee.getNBTData();
			String renderer = bee.getRenderer().isEmpty() ? "default" : bee.getRenderer();
			callback.setReturnValue(data.contains("model") ? new ResourceLocation(data.getString("model"))
					: new ResourceLocation("productivebees", "geo/entity/" + renderer + ".geo.json"));
		} else {
			productivebeesgenesis$refreshDeclaration(bee);
		}
	}

	@Inject(method = "getAnimationResource(Lcy/jdkdigital/productivebees/common/entity/bee/GeckoBee;)Lnet/minecraft/resources/ResourceLocation;",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$previewAnimation(@Coerce ConfigurableBee bee, CallbackInfoReturnable<ResourceLocation> callback) {
		if (((DesignerPreviewData) bee).productivebeesgenesis$isPreview()) {
			var data = bee.getNBTData();
			callback.setReturnValue(data.contains("animation") ? new ResourceLocation(data.getString("animation"))
					: new ResourceLocation("productivebees", "animations/entity/bee.animation.json"));
		} else {
			productivebeesgenesis$refreshDeclaration(bee);
		}
	}

	@Unique
	private void productivebeesgenesis$refreshDeclaration(ConfigurableBee bee) {
		if (productivebeesgenesis$modelDeclarations == null) {
			productivebeesgenesis$modelDeclarations = new LinkedHashMap<>(64, 0.75F, true);
		}
		ResourceLocation type = new ResourceLocation(bee.getBeeType());
		CompoundTag data = bee.getNBTData();
		CompoundTag previous = productivebeesgenesis$modelDeclarations.get(type);
		if (previous != null && previous.getString("model").equals(data.getString("model"))
				&& previous.getString("renderer").equals(data.getString("renderer"))
				&& previous.getString("animation").equals(data.getString("animation"))) return;
		CompoundTag snapshot = new CompoundTag();
		snapshot.putString("model", data.getString("model"));
		snapshot.putString("renderer", data.getString("renderer"));
		snapshot.putString("animation", data.getString("animation"));
		if (previous == null && productivebeesgenesis$modelDeclarations.size() >= 512) {
			var oldest = productivebeesgenesis$modelDeclarations.keySet().iterator();
			ResourceLocation removed = oldest.next();
			oldest.remove();
			modelCache.remove(removed);
			animationCache.remove(removed);
		}
		productivebeesgenesis$modelDeclarations.put(type, snapshot);
		modelCache.remove(type);
		animationCache.remove(type);
	}
}
