package com.ayoshiko.productivebeesgenesis.port;
import com.mojang.serialization.*;
import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.*;
public final class ForgeRecipeCodecs {
    private static volatile java.util.Map<ResourceLocation,JsonElement> sources=java.util.Map.of();
    public static void capture(java.util.Map<ResourceLocation,JsonElement> values){
        var next=new java.util.HashMap<ResourceLocation,JsonElement>();values.forEach((id,json)->next.put(id,json.deepCopy()));sources=java.util.Map.copyOf(next);ForgeRecipeIds.clear();
    }
    /** Closed copy of the JSON actually supplied to RecipeManager during the last reload. */
    public static JsonElement source(ResourceLocation id) {
        JsonElement source = sources.get(id);
        return source == null ? null : source.deepCopy();
    }
    public static final Codec<Recipe<?>> CODEC=Codec.PASSTHROUGH.flatXmap(value->{try {
        var json=value.convert(JsonOps.INSTANCE).getValue().getAsJsonObject();
        Recipe<?> recipe=RecipeManager.fromJson(new ResourceLocation("productivebeesgenesis:designer_validation"),ForgeJsonRecipes.toForge(json));
        return DataResult.success(recipe);
    }catch(RuntimeException e){return DataResult.error(()->e.toString());}},recipe->{
        var json=sources.get(recipe.getId());
        if(json==null && recipe instanceof com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe r) json=com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE,r).result().orElse(null);
        if(json==null && recipe instanceof com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe r) json=com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE,r).result().orElse(null);
        return json==null ? DataResult.error(()->"Recipe JSON unavailable: "+recipe.getId()) : DataResult.success(new Dynamic<>(JsonOps.INSTANCE,ForgeJsonRecipes.toPortable(json.getAsJsonObject())));
    });
}
