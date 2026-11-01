package com.ayoshiko.productivebeesgenesis.port;
import com.mojang.serialization.*;
import net.minecraft.world.item.crafting.Ingredient;
public record DesignerChancedOutput(Ingredient ingredient,int min,int max,float chance){
 public static final Codec<DesignerChancedOutput> CODEC=Codec.PASSTHROUGH.flatXmap(value->{try {
  var json=value.convert(JsonOps.INSTANCE).getValue().getAsJsonObject();var ingredient=ForgeIngredientCodecs.CODEC.parse(JsonOps.INSTANCE,json.get("item")).getOrThrow(false,m->{});
  return DataResult.success(new DesignerChancedOutput(ingredient,json.has("min")?json.get("min").getAsInt():1,json.has("max")?json.get("max").getAsInt():1,json.has("chance")?json.get("chance").getAsFloat():1));
 }catch(RuntimeException e){return DataResult.error(e::toString);}},value->{var json=new com.google.gson.JsonObject();json.add("item",value.ingredient.toJson());json.addProperty("min",value.min);json.addProperty("max",value.max);json.addProperty("chance",value.chance);return DataResult.success(new Dynamic<>(JsonOps.INSTANCE,json));});
}
