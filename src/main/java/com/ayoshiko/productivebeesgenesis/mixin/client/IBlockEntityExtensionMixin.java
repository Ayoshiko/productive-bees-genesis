package com.ayoshiko.productivebeesgenesis.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.client.render.cosmic.MyriadCombModelData;

import cy.jdkdigital.productivebees.common.block.entity.CombBlockBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.extensions.IBlockEntityExtension;

/**
 * IBlockEntityExtension Mixin — 为蜜脾块方块提供 ModelData
 * <br/>
 * NeoForge 的 {@code getModelData()} 是 {@link IBlockEntityExtension} 接口的 default 方法，
 * 不在 BlockEntity 类字节码中，因此必须 Mixin 接口本身而非具体子类。
 * <p>
 * 当目标 BlockEntity 为 {@link CombBlockBlockEntity} 时，将 combType 传递给 BakedModel，
 * 使万象创世蜜脾块方块可以切换为无尽创世蜜脾块的纹理。
 * 其他 BlockEntity 不受影响，继续返回默认的 {@link ModelData#EMPTY}。
 */
@Mixin(IBlockEntityExtension.class)
public interface IBlockEntityExtensionMixin {

	@Inject(method = "getModelData", at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$getModelData(CallbackInfoReturnable<ModelData> cir) {
		if ((Object) this instanceof CombBlockBlockEntity self) {
			ResourceLocation combType = self.getCombType();
			if (combType != null) {
				cir.setReturnValue(ModelData.builder()
						.with(MyriadCombModelData.COMB_TYPE, combType)
						.build());
			}
		}
	}
}
