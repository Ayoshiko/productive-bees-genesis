package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeMixinHelper;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * CentrifugeBlockEntity Mixin：离心机行为注入（万象创世体系）
	 * <p>
	 * 公共逻辑委托给 {@link CentrifugeMixinHelper}，本类仅保留 @Inject 注解与方法签名。
	 */
@Mixin(CentrifugeBlockEntity.class)
public abstract class CentrifugeBlockEntityMixin {

	/** canOperate RETURN — 输出满时阻止机器启动（修复PB原版空转耗能） */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpaceBeforeStart(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this,
			MyriadCreationsEventHandler::shouldBlockOperation);
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
	 * completeRecipeProcessing HEAD — 扣料前预留产物空间，保留最后一份输入的类型。
	 * <p>
	 * 注入3参数版本（唯一可匹配的签名）。
	 */
	@Inject(method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;)V",
			at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$appendRandomCombs(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		if (!CentrifugeMixinHelper.appendRandomCombs(
				recipe, invHandler, random, (CentrifugeBlockEntity) (Object) this, false)) {
			ci.cancel();
		}
	}
}
