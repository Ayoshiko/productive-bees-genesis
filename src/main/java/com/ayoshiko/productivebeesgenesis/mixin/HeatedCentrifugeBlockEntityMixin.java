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
import cy.jdkdigital.productivebees.common.block.entity.HeatedCentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 热能离心机Mixin：HeatedCentrifugeBlockEntity 重写了父类方法，需独立注入
 */
@Mixin(HeatedCentrifugeBlockEntity.class)
public class HeatedCentrifugeBlockEntityMixin {

	/** canOperate RETURN — 输出满时阻止启动 (#4 复用公共方法) */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpace(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) return;
		if (MyriadCreationsEventHandler.shouldBlockOperation(
				((CentrifugeBlockEntity)(Object)this).inventoryHandler)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * completeRecipeProcessing TAIL — 追加随机蜜脾产出（支持Omega升级倍率）
	 * <p>
	 * 注入3参数版本，通过强转父类获取 getProductivityModifier()
	 */
	@Inject(method = "completeRecipeProcessing", at = @At("TAIL"))
	private void productivebeesgenesis$appendRandomCombsForHeated(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CallbackInfo ci) {
		try {
			ItemStack input = invHandler.getStackInSlot(1);
			CentrifugeBlockEntity entity = (CentrifugeBlockEntity)(Object)this;
			int modifier = ((CentrifugeBlockEntityAccessor) entity).productivebeesgenesis_getProductivityModifier();
			MyriadCreationsEventHandler.appendRandomCombs(input, invHandler, random, modifier);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("热能离心机 Mixin 异常", e);
		}
	}
}
