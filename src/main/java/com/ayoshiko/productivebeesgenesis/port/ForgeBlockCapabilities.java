package com.ayoshiko.productivebeesgenesis.port;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import java.util.*;
import java.util.function.BiFunction;

/** Native Forge capability providers, registered before worlds are loaded. */
public final class ForgeBlockCapabilities {
    private record Entry(Capability<?> capability, BiFunction<BlockEntity, Direction, ?> provider) {}
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<BlockEntity,List<java.lang.ref.WeakReference<LazyOptional<?>>>> VIEWS = new WeakHashMap<>();
    public static synchronized void invalidate(BlockEntity tile) {
        var views=VIEWS.remove(tile);
        if(views!=null) for(var reference:views) { var view=reference.get(); if(view!=null) view.invalidate(); }
    }
    public static <T> void register(Capability<T> cap, BiFunction<BlockEntity, Direction, T> factory) { ENTRIES.add(new Entry(cap, factory)); }
    public static <T> T get(Level level, Capability<T> capability, BlockPos pos, Direction side) {
        BlockEntity tile = level.getBlockEntity(pos);
        return tile == null ? null : tile.getCapability(capability, side).orElse(null);
    }
    @SuppressWarnings("unchecked")
    public static <T> T output(BlockEntity tile, Capability<T> cap, Direction side) {
        for (Entry entry : ENTRIES) if (entry.capability == cap) {
            Object value = entry.provider.apply(tile, side);
            if (value != null) return (T)value;
        }
        return null;
    }
    public static <T> LazyOptional<T> view(BlockEntity tile, Capability<T> cap, Direction side) {
        T output = output(tile,cap,side);
        if(output==null) return LazyOptional.empty();
        LazyOptional<T> view=LazyOptional.of(() -> output);
        synchronized(ForgeBlockCapabilities.class) {
            var views=VIEWS.computeIfAbsent(tile,k->new ArrayList<>());
            views.removeIf(reference->reference.get()==null);
            views.add(new java.lang.ref.WeakReference<>(view));
        }
        return view;
    }
}
