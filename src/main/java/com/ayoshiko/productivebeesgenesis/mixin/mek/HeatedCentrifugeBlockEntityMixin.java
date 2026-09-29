package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.util.CentrifugeMixinHelper;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.HeatedCentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * 热能离心机 Mixin：追加万象蜜脾产物空间检查（万象创世体系）。
	 * <p>
	 * Productive Bees 13.14.0 让热能离心机继承 PoweredCentrifugeBlockEntity 的
	 * {@code canOperate()}；父类已经提供能量门控，因此这里只保留产物提交前的守恒检查。
	 * 公共逻辑委托给 {@link CentrifugeMixinHelper}。
	 */
@Mixin(HeatedCentrifugeBlockEntity.class)
public abstract class HeatedCentrifugeBlockEntityMixin {

	/** 扣料前预留热能配方产物空间，失败时保留输入（支持 Omega 升级倍率）。 */
	@Inject(method = "completeRecipeProcessing", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$appendRandomCombsForHeated(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		if (!CentrifugeMixinHelper.appendRandomCombs(
				recipe, invHandler, random, (CentrifugeBlockEntity) (Object) this, true)) {
			ci.cancel();
		}
	}
}
