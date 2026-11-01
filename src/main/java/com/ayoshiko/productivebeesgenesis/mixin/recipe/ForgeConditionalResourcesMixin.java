package com.ayoshiko.productivebeesgenesis.mixin.recipe;
import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
/** Evaluate Genesis optional loot/advancement conditions before vanilla resolves item IDs. */
@Mixin(SimpleJsonResourceReloadListener.class)
public abstract class ForgeConditionalResourcesMixin {
 @Inject(method="scanDirectory",at=@At("TAIL"))
 private static void genesis$conditions(ResourceManager manager,String directory,Gson gson,Map<ResourceLocation,JsonElement> resources,CallbackInfo ci){
  if(!directory.equals("loot_tables")&&!directory.equals("advancements"))return;
  resources.entrySet().removeIf(entry->entry.getKey().getNamespace().equals("productivebeesgenesis")&&entry.getValue().isJsonObject()&&!CraftingHelper.processConditions(entry.getValue().getAsJsonObject(),"conditions",ICondition.IContext.EMPTY));
 }
}
