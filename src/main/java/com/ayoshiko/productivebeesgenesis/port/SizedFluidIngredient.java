package com.ayoshiko.productivebeesgenesis.port;
import com.google.gson.*;
import com.mojang.serialization.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
public record SizedFluidIngredient(JsonObject definition) {
    public static final Codec<SizedFluidIngredient> FLAT_CODEC=Codec.PASSTHROUGH.flatXmap(value->{try{
        var json=value.convert(JsonOps.INSTANCE).getValue().getAsJsonObject();var ingredient=new SizedFluidIngredient(json.deepCopy());
        if(ingredient.getFluids().length==0 || json.has("amount") && json.get("amount").getAsInt()<1)throw new IllegalArgumentException("Invalid fluid ingredient");
        return DataResult.success(ingredient);
    }catch(RuntimeException e){return DataResult.error(e::toString);}},ingredient->DataResult.success(new Dynamic<>(JsonOps.INSTANCE,ingredient.definition.deepCopy())));
    public static SizedFluidIngredient of(FluidStack stack){JsonObject json=new JsonObject();json.addProperty("fluid",BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());json.addProperty("amount",stack.getAmount());if(stack.hasTag())json.addProperty("nbt",stack.getTag().toString());return new SizedFluidIngredient(json);}
    public FluidStack[] getFluids(){
        int amount=definition.has("amount")?definition.get("amount").getAsInt():1;
        JsonObject fluid=definition.has("ingredient")?definition.getAsJsonObject("ingredient"):definition;
        java.util.List<FluidStack> result=new java.util.ArrayList<>();
        if(fluid.has("tag")) {
            var tag=net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID,new ResourceLocation(fluid.get("tag").getAsString()));
            BuiltInRegistries.FLUID.getTag(tag).ifPresent(values->values.forEach(holder->result.add(new FluidStack(holder.value(),amount))));
        }else {
            String id=fluid.has("fluid")?fluid.get("fluid").getAsString():fluid.get("id").getAsString();
            var key=new ResourceLocation(id);if(!BuiltInRegistries.FLUID.containsKey(key))throw new IllegalArgumentException("Unknown fluid "+id);
            FluidStack stack=new FluidStack(BuiltInRegistries.FLUID.get(key),amount);
            if(fluid.has("nbt"))stack.setTag(ForgeItemStackCodec.tag(fluid.get("nbt")));result.add(stack);
        }
        return result.toArray(FluidStack[]::new);
    }
}
