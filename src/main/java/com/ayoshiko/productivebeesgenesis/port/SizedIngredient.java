package com.ayoshiko.productivebeesgenesis.port;
import com.mojang.serialization.*;
import com.google.gson.*;
import net.minecraft.world.item.crafting.Ingredient;
public record SizedIngredient(Ingredient ingredient,int count) {
    public static final Codec<SizedIngredient> FLAT_CODEC=Codec.PASSTHROUGH.flatXmap(value->{try{
        var json=value.convert(JsonOps.INSTANCE).getValue().getAsJsonObject();return DataResult.success(new SizedIngredient(Ingredient.fromJson(json),json.has("count")?json.get("count").getAsInt():1));
    }catch(RuntimeException e){return DataResult.error(e::toString);}},value->{var json=value.ingredient.toJson();if(!json.isJsonObject())return DataResult.error(()->"Sized alternatives require an explicit ingredient object");var result=json.getAsJsonObject().deepCopy();result.addProperty("count",value.count);return DataResult.success(new Dynamic<>(JsonOps.INSTANCE,result));});
}
