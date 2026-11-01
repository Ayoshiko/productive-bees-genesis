package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
public record RecipeHolder<T extends Recipe<?>>(ResourceLocation id,T value) {
    public ResourceLocation getId() { return id; }
    public static <T extends Recipe<?>> RecipeHolder<T> of(T value) { return new RecipeHolder<>(value.getId(),value); }
}
