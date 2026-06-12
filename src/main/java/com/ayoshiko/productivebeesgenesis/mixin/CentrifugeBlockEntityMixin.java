package com.ayoshiko.productivebeesgenesis.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.CentrifugeBlockEntityAccessor;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * CentrifugeBlockEntity Mixin：离心机行为注入
 */
@Mixin(CentrifugeBlockEntity.class)
public class CentrifugeBlockEntityMixin {

	/** canOperate RETURN — 输出满时阻止机器启动（修复PB原版空转耗能）(#4 复用公共方法) */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpaceBeforeStart(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) return;
		if (MyriadCreationsEventHandler.shouldBlockOperation(
				((CentrifugeBlockEntity)(Object)this).inventoryHandler)) {
			cir.setReturnValue(false);
		}
	}

	/** canProcessRecipe HEAD — 双重保险 */
	@Inject(method = "canProcessRecipe", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpace(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			CallbackInfoReturnable<Boolean> cir) {
		if (MyriadCreationsEventHandler.shouldBlockOperation(invHandler)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * completeRecipeProcessing TAIL — 万象创世蜜脾追加随机产出
	 * <p>
	 * 注入3参数版本（唯一可匹配的签名），通过强转获取 getProductivityModifier()。
	 * PB内部流程: 3参数版→计算modifier→调用5参数版→产出×modifier
	 */
	@Inject(method = "completeRecipeProcessing", at = @At("TAIL"))
	private void productivebeesgenesis$appendRandomCombs(
			RecipeHolder<?> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		try {
			ItemStack input = invHandler.getStackInSlot(1);
			CentrifugeBlockEntity entity = (CentrifugeBlockEntity)(Object)this;
			int modifier = ((CentrifugeBlockEntityAccessor) entity).productivebeesgenesis_getProductivityModifier();
			MyriadCreationsEventHandler.appendRandomCombs(input, invHandler, random, modifier);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("Centrifuge Mixin 异常", e);
		}
	}
}
