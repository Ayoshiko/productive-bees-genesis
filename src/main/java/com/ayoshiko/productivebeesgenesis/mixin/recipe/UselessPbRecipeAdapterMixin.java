package com.ayoshiko.productivebeesgenesis.mixin.recipe;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.datafixers.util.Pair;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.world.level.material.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
/** PB 12 legitimately returns null for an item-only recipe; the local adapter expects an empty pair. */
@Pseudo
@Mixin(targets="com.sorrowmist.useless.content.recipe.adapters.productivebees.CentrifugeRecipeAdapter",remap=false)
public abstract class UselessPbRecipeAdapterMixin {
 @WrapOperation(method="convertAll",at=@At(value="INVOKE",target="Lcy/jdkdigital/productivebees/common/recipe/CentrifugeRecipe;getFluidOutputs()Lcom/mojang/datafixers/util/Pair;"),require=1)
 private Pair<Fluid,Integer> genesis$emptyFluid(CentrifugeRecipe recipe,Operation<Pair<Fluid,Integer>> original){var fluid=original.call(recipe);return fluid==null?Pair.of(Fluids.EMPTY,0):fluid;}
}
