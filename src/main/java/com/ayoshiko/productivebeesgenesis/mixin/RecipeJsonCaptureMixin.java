package com.ayoshiko.productivebeesgenesis.mixin;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.resources.ResourceLocation;
import com.google.gson.JsonElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RecipeManager.class)
public abstract class RecipeJsonCaptureMixin {
 @Inject(method="apply",at=@At("HEAD")) private void genesis$capture(java.util.Map<ResourceLocation,JsonElement> recipes,net.minecraft.server.packs.resources.ResourceManager resources,net.minecraft.util.profiling.ProfilerFiller profiler,CallbackInfo ci){com.ayoshiko.productivebeesgenesis.port.ForgeRecipeCodecs.capture(recipes);}
}
