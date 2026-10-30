package com.ayoshiko.productivebeesgenesis.mixin.recipe;
import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Uses PB 12's native wire format; failed fallback writes never corrupt the destination. */
@Mixin(targets="cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe$Serializer")
public abstract class AdvancedBeehiveRecipeSerializerMixin {
    @Inject(method="toNetwork", at=@At("HEAD"), cancellable=true, remap=false)
    private void productivebeesgenesis$fallback(FriendlyByteBuf buffer, AdvancedBeehiveRecipe recipe, CallbackInfo ci) {
        if (!(recipe.ingredient.get() == null)) return;
        FriendlyByteBuf staged = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BeeIngredientFallback.writeFallbackBeeIngredient(staged); staged.writeInt(0);
            buffer.writeBytes(staged);
            ci.cancel();
        } finally { staged.release(); }
    }
}
