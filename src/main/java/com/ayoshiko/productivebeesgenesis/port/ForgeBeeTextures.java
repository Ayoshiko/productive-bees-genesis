package com.ayoshiko.productivebeesgenesis.port;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
public final class ForgeBeeTextures {
 @SuppressWarnings("unchecked") public static Map<String,ResourceLocation> layers(String renderer){try{var field=cy.jdkdigital.productivebees.client.render.entity.layers.BeeBodyLayer.class.getDeclaredField("baseTextures");field.setAccessible(true);return ((Map<String,Map<String,ResourceLocation>>)field.get(null)).get(renderer);}catch(ReflectiveOperationException e){throw new IllegalStateException("PB texture lookup unavailable",e);}}
}
