package com.ayoshiko.productivebeesgenesis.port;
import net.minecraftforge.common.ForgeConfigSpec;
public final class ForgeConfigSpecs {
    public static ForgeConfigSpec.ValueSpec value(ForgeConfigSpec.ConfigValue<?> value){
        try {for(var field:com.ayoshiko.productivebeesgenesis.config.ModConfig.class.getDeclaredFields())if(field.getType()==ForgeConfigSpec.class){field.setAccessible(true);var spec=(ForgeConfigSpec)field.get(null);if(spec.getValues().get(value.getPath())==value)return spec.getSpec().get(value.getPath());}}
        catch(IllegalAccessException e){throw new IllegalStateException(e);}
        throw new IllegalArgumentException("Unknown config field: "+value.getPath());
    }
}
