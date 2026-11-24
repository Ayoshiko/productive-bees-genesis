package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import cy.jdkdigital.productivebees.event.ModEventHandler;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.LinkedHashMap;
import java.util.Map;

/** 创造目录只枚举正式身份，PB 原定义仍保留旧蜂种的恢复来源。 */
@Mixin(value = ModEventHandler.class, remap = false)
public abstract class PbDesignerAliasTabMixin {
	@Redirect(method = "tabContents", at = @At(value = "INVOKE",
			target = "Lcy/jdkdigital/productivebees/setup/BeeReloadListener;getData()Ljava/util/Map;"))
	private static Map<ResourceLocation, CompoundTag> productivebeesgenesis$visibleBees(BeeReloadListener listener) {
		Map<ResourceLocation, CompoundTag> visible = new LinkedHashMap<>();
		listener.getData().forEach((id, definition) -> {
			if (!BeeDesignerIdentity.isAlias(id)) {
				visible.put(id, definition);
			}
		});
		return visible;
	}
}
