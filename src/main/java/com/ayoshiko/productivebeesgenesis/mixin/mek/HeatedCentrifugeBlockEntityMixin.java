package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * 热能离心机Mixin：HeatedCentrifugeBlockEntity 重写了父类方法，需独立注入（万象创世体系）
	 * <p>
	 * 公共逻辑委托给 {@link CentrifugeMixinHelper}。
	 */
@Mixin(HeatedCentrifugeBlockEntity.class)
public abstract class HeatedCentrifugeBlockEntityMixin {

	/** canOperate RETURN — 输出满时阻止启动 */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpace(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this, MyriadCreationsEventHandler::shouldBlockOperation);
	}

	/** completeRecipeProcessing TAIL — 追加随机蜜脾产出（支持Omega升级倍率） */
	@Inject(method = "completeRecipeProcessing", at = @At("TAIL"))
	private void productivebeesgenesis$appendRandomCombsForHeated(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		CentrifugeMixinHelper.appendRandomCombs(
				invHandler, random, (CentrifugeBlockEntity) (Object) this,
				MyriadCreationsEventHandler::appendRandomCombs,
				"热能离心机 Mixin 异常");
	}
}
