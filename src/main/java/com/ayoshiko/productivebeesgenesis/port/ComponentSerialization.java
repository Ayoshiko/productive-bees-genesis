package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.network.chat.Component;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
public final class ComponentSerialization {
    public static final Codec<Component> CODEC=Codec.PASSTHROUGH.xmap(value -> Component.Serializer.fromJson(value.convert(JsonOps.INSTANCE).getValue()), value -> new com.mojang.serialization.Dynamic<>(JsonOps.INSTANCE,Component.Serializer.toJsonTree(value)));
}
