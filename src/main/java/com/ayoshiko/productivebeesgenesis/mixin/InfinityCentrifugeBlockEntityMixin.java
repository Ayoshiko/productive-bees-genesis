package com.ayoshiko.productivebeesgenesis.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.InfinityCreationEventHandler;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 无尽·创世 CentrifugeBlockEntity Mixin：离心机行为注入（无尽·创世体系）
 * <p>
 * 与 {@link CentrifugeBlockEntityMixin} 并存，两者互不干扰：
 * <ul>
 *   <li>本 Mixin 调用 {@link InfinityCreationEventHandler}，仅处理 infinitycreation 蜜脾/蜜脾块</li>
 *   <li>原 Mixin 调用 MyriadCreationsEventHandler，仅处理 myriadcreations 蜜脾</li>
 * </ul>
 * 方法名使用 productivebeesgenesis$infinity$ 前缀以与原 Mixin 区分。
 * 公共逻辑委托给 {@link CentrifugeMixinHelper}。
 */
@Mixin(CentrifugeBlockEntity.class)
public class InfinityCentrifugeBlockEntityMixin {

	/** canOperate RETURN — 输出满时阻止机器启动（仅对无尽·创世蜜脾/蜜脾块生效） */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$infinity$checkOutputSpaceBeforeStart(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this, InfinityCreationEventHandler::shouldBlockOperation);
	}

	/** canProcessRecipe HEAD — 双重保险（仅对无尽·创世蜜脾/蜜脾块生效） */
	@Inject(method = "canProcessRecipe", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$infinity$checkOutputSpace(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanProcessRecipe(invHandler, cir, InfinityCreationEventHandler::shouldBlockOperation);
	}

	/**
	 * completeRecipeProcessing TAIL — 无尽·创世蜜脾追加随机产出
	 * <p>
	 * 注入3参数版本（唯一可匹配的签名）。
	 */
	@Inject(method = "completeRecipeProcessing", at = @At("TAIL"))
	private void productivebeesgenesis$infinity$appendRandomCombs(
			RecipeHolder<?> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		CentrifugeMixinHelper.appendRandomCombs(
				invHandler, random, (CentrifugeBlockEntity) (Object) this,
				InfinityCreationEventHandler::appendRandomCombs,
				"无尽·创世 Centrifuge Mixin 异常");
	}
}
