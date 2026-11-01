package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.network.chat.Component;
public final class DataComponents {
    public record Key<T>(String name) {}
    public static final Key<CustomData> CUSTOM_DATA=new Key<>(""), ENTITY_DATA=new Key<>("EntityTag"), BLOCK_ENTITY_DATA=new Key<>("BlockEntityTag");
    public static final Key<Component> CUSTOM_NAME=new Key<>("Name");
    public static final Key<Integer> MAX_STACK_SIZE=new Key<>("MaxStackSize");
}
