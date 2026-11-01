package com.ayoshiko.productivebeesgenesis.port;
import com.mojang.serialization.*;
import com.google.gson.*;
import mekanism.api.chemical.Chemical;
public record ForgeChemicalIngredient(JsonObject definition) {
    public static final Codec<ForgeChemicalIngredient> CODEC=Codec.PASSTHROUGH.flatXmap(value->{try{
        JsonObject json=value.convert(JsonOps.INSTANCE).getValue().getAsJsonObject();
        if(!json.has("amount")||json.get("amount").getAsLong()<1)throw new IllegalArgumentException("Invalid chemical amount");
        return DataResult.success(new ForgeChemicalIngredient(json.deepCopy()));
    }catch(RuntimeException e){return DataResult.error(e::toString);}},value->DataResult.success(new Dynamic<>(JsonOps.INSTANCE,value.definition.deepCopy())));
    public static ForgeChemicalIngredient of(Chemical<?> chemical,long amount){var json=new JsonObject();String key=switch(ForgeChemicals.kind(chemical)){case 0->"gas";case 1->"infuse_type";case 2->"pigment";default->"slurry";};json.addProperty(key,chemical.getRegistryName().toString());json.addProperty("amount",amount);return new ForgeChemicalIngredient(json);}
}
