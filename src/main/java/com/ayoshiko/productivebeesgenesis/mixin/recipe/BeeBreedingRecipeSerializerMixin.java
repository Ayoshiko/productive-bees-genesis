package com.ayoshiko.productivebeesgenesis.mixin.recipe;
import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Uses PB 12's native wire format; failed fallback writes never corrupt the destination. */
@Mixin(targets="cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe$Serializer")
public abstract class BeeBreedingRecipeSerializerMixin {
    @Inject(method="toNetwork", at=@At("HEAD"), cancellable=true, remap=false)
    private void productivebeesgenesis$fallback(FriendlyByteBuf buffer, BeeBreedingRecipe recipe, CallbackInfo ci) {
        if (!(recipe.offspring.get() == null || recipe.ingredients.stream().anyMatch(value -> value == null || value.get() == null))) return;
        FriendlyByteBuf staged = new FriendlyByteBuf(Unpooled.buffer());
        try {
            staged.writeInt(recipe.ingredients.size()); for (var value : recipe.ingredients) { if (value == null || value.get() == null) BeeIngredientFallback.writeFallbackBeeIngredient(staged); else value.get().toNetwork(staged); } if (recipe.offspring.get() == null) BeeIngredientFallback.writeFallbackBeeIngredient(staged); else recipe.offspring.get().toNetwork(staged);
            buffer.writeBytes(staged);
            ci.cancel();
        } finally { staged.release(); }
    }
}
