package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppresses only Productive Bees honey fluid while preserving other recipe fluids. */
@Mixin(CentrifugeBlockEntity.class)
public abstract class CentrifugeUselessByproductMixin {

	@Redirect(
			method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;ZI)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I",
					remap = false
			)
	)
	private int productivebeesgenesis$discardHoney(FluidTank tank, FluidStack stack,
			IFluidHandler.FluidAction action) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		if (UselessByproductUpgradeHelper.hasUpgrade(blockEntity)
				&& UselessByproductUpgradeHelper.isHoney(stack)) {
			return 0;
		}
		return tank.fill(stack, action);
	}
}
