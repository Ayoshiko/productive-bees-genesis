package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
public final class ForgeComponents {
    @SuppressWarnings("unchecked") public static <T> T get(ItemStack stack, DataComponents.Key<T> key) {
        if(key==DataComponents.CUSTOM_NAME) return stack.hasCustomHoverName() ? (T)stack.getHoverName() : null;
        if(key==DataComponents.MAX_STACK_SIZE) return null;
        var tag=key.name().isEmpty() ? stack.getTag() : stack.getTagElement(key.name());
        return tag==null ? null : (T)CustomData.of(tag);
    }
    public static <T> void remove(ItemStack stack,DataComponents.Key<T> key) { if(key.name().isEmpty())stack.setTag(null);else stack.removeTagKey(key.name()); }
    public static <T> boolean has(ItemStack stack,DataComponents.Key<T> key) { return get(stack,key)!=null; }
    public static <T> void set(ItemStack stack,DataComponents.Key<T> key,T value) {
        if(key==DataComponents.CUSTOM_NAME) { stack.setHoverName((Component)value); return; }
        if(value instanceof CustomData data) { if(key.name().isEmpty()) stack.setTag(data.copyTag()); else stack.addTagElement(key.name(),data.copyTag()); }
        else throw new IllegalArgumentException("Unsupported item data: "+key.name());
    }
}
