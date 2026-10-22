package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerLifecycle;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 保留原实体和身份，通过服务器当前目录停止已删除蜂种的后续行为。 */
@Mixin(ConfigurableBee.class)
public abstract class DesignerRetiredBeeMixin {

	@Inject(method = "canSelfBreed", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$retiredBreeding(CallbackInfoReturnable<Boolean> result) {
		if (BeeDesignerLifecycle.deleted(((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(false);
		}
	}

	@Inject(method = "getBreedingIngredient", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$retiredBreedingItem(CallbackInfoReturnable<Ingredient> result) {
		if (BeeDesignerLifecycle.deleted(((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(Ingredient.EMPTY);
		}
	}

	@Inject(method = "isFlowerValid", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$retiredFlower(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
		if (BeeDesignerLifecycle.deleted(((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(false);
		}
	}

	@Inject(method = "isFlowerItem", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$retiredFlowerItem(ItemStack stack, CallbackInfoReturnable<Boolean> result) {
		if (BeeDesignerLifecycle.deleted(((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(false);
		}
	}

	@Inject(method = "isFlowerBlock", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$retiredFlowerBlock(BlockState state, CallbackInfoReturnable<Boolean> result) {
		if (BeeDesignerLifecycle.deleted(((ConfigurableBee) (Object) this).getBeeType())) {
			result.setReturnValue(false);
		}
	}

	@Inject(method = "postPollinate", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$retiredPollination(CallbackInfo result) {
		if (BeeDesignerLifecycle.deleted(((ConfigurableBee) (Object) this).getBeeType())) {
			result.cancel();
		}
	}
}
