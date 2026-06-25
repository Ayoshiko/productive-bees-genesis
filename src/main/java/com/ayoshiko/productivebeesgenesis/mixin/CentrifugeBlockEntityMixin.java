package com.ayoshiko.productivebeesgenesis.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * CentrifugeBlockEntity Mixin：离心机行为注入（万象创世体系）
 * <p>
 * 公共逻辑委托给 {@link CentrifugeMixinHelper}，本类仅保留 @Inject 注解与方法签名。
 */
@Mixin(CentrifugeBlockEntity.class)
public class CentrifugeBlockEntityMixin {

	/** canOperate RETURN — 输出满时阻止机器启动（修复PB原版空转耗能） */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpaceBeforeStart(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this, MyriadCreationsEventHandler::shouldBlockOperation);
	}

	/** canProcessRecipe HEAD — 双重保险 */
	@Inject(method = "canProcessRecipe", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpace(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanProcessRecipe(invHandler, cir, MyriadCreationsEventHandler::shouldBlockOperation);
	}

	/**
	 * completeRecipeProcessing TAIL — 万象创世蜜脾追加随机产出
	 * <p>
	 * 注入3参数版本（唯一可匹配的签名）。
	 */
	@Inject(method = "completeRecipeProcessing", at = @At("TAIL"))
	private void productivebeesgenesis$appendRandomCombs(
			RecipeHolder<?> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		CentrifugeMixinHelper.appendRandomCombs(
				invHandler, random, (CentrifugeBlockEntity) (Object) this,
				MyriadCreationsEventHandler::appendRandomCombs,
				"Centrifuge Mixin 异常");
	}
}
