package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 只返回规范化的身份视图；不改变存储索引里原物品的哈希与相等关系。 */
@Mixin(PatchedDataComponentMap.class)
public abstract class BeeIdentityComponentViewMixin {
	@Unique
	private CustomData productivebeesgenesis$identitySource;
	@Unique
	private CustomData productivebeesgenesis$identityView;
	@Unique
	private long productivebeesgenesis$identityGeneration = -1;

	@Inject(method = "get", at = @At("RETURN"), cancellable = true)
	private <T> void productivebeesgenesis$resolveIdentity(DataComponentType<? extends T> type,
			CallbackInfoReturnable<T> ci) {
		Object value = ci.getReturnValue();
		if (!(value instanceof ResourceLocation || value instanceof CustomData || value instanceof List) || !BeeDesignerIdentity.hasMigrations()) {
			return;
		}
		if (value instanceof ResourceLocation id && type == ModDataComponents.BEE_TYPE.get()) {
			ci.setReturnValue((T) BeeDesignerIdentity.migrate(id));
		} else if (value instanceof List<?> list && type == ModDataComponents.BEE_TYPE_LIST.get()) {
			if (list.stream().anyMatch(id -> BeeDesignerIdentity.isAlias((ResourceLocation) id))) {
				ci.setReturnValue((T) list.stream().map(id -> BeeDesignerIdentity.migrate((ResourceLocation) id)).toList());
			}
		} else if (value instanceof CustomData data && (type == DataComponents.ENTITY_DATA || type == DataComponents.CUSTOM_DATA)) {
			long generation = BeeDesignerIdentity.generation();
			if (data != productivebeesgenesis$identitySource || generation != productivebeesgenesis$identityGeneration) {
				productivebeesgenesis$identitySource = data;
				productivebeesgenesis$identityView = BeeDesignerIdentity.migrateCustomData(data);
				productivebeesgenesis$identityGeneration = generation;
			}
			ci.setReturnValue((T) productivebeesgenesis$identityView);
		}
	}
}
