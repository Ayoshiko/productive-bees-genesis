package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.nbt.CompoundTag;
public record CustomData(CompoundTag data) {
    public static final CustomData EMPTY=new CustomData(new CompoundTag());
    public static CustomData of(CompoundTag tag) { return new CustomData(tag.copy()); }
    public CompoundTag copyTag() { return data.copy(); }
    public boolean contains(String key) { return data.contains(key); }
    public CompoundTag getUnsafe() { return data; }
    public boolean isEmpty() { return data.isEmpty(); }
}
