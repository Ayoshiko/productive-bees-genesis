package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.resources.ResourceLocation;
public final class ForgeRecipeIds {
 private static final java.util.Map<Object,ResourceLocation> IDS=java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
 public static void put(Object recipe,ResourceLocation id){IDS.put(recipe,id);}
 public static ResourceLocation get(Object recipe,ResourceLocation fallback){return IDS.getOrDefault(recipe,fallback);}
 public static void clear(){IDS.clear();}
}
