package com.ayoshiko.productivebeesgenesis.mixin.recipe;
import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Uses PB 12's native wire format; failed fallback writes never corrupt the destination. */
@Mixin(targets="cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe$Serializer")
public abstract class BeeSpawningRecipeSerializerMixin {
    @Inject(method="toNetwork", at=@At("HEAD"), cancellable=true, remap=false)
    private void productivebeesgenesis$fallback(FriendlyByteBuf buffer, BeeSpawningRecipe recipe, CallbackInfo ci) {
        if (!(recipe.output.stream().anyMatch(value -> value == null || value.get() == null))) return;
        FriendlyByteBuf staged = new FriendlyByteBuf(Unpooled.buffer());
        try {
            recipe.ingredient.toNetwork(staged); recipe.spawnItem.toNetwork(staged); staged.writeInt(recipe.output.size()); for (var value : recipe.output) { if (value == null || value.get() == null) BeeIngredientFallback.writeFallbackBeeIngredient(staged); else value.get().toNetwork(staged); } staged.writeUtf(recipe.biomes);
            buffer.writeBytes(staged);
            ci.cancel();
        } finally { staged.release(); }
    }
}
