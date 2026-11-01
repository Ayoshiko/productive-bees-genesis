package com.ayoshiko.productivebeesgenesis.port;
import com.mojang.serialization.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
public final class ForgeIngredientCodecs {
    public static final Codec<Ingredient> CODEC=Codec.PASSTHROUGH.flatXmap(value->{try {
        var json=value.convert(JsonOps.INSTANCE).getValue();
        return DataResult.success(Ingredient.fromJson(json.isJsonObject()?ForgeJsonRecipes.toForge(json.getAsJsonObject()):json));
    }catch(RuntimeException e){return DataResult.error(e::toString);}},ingredient->DataResult.success(new Dynamic<>(JsonOps.INSTANCE,ForgeJsonRecipes.portableIngredient(ingredient.toJson()))));
    public static Ingredient of(boolean strict,ItemStack stack){return strict&&stack.hasTag()?StrictNBTIngredient.of(stack):Ingredient.of(stack);}
}
