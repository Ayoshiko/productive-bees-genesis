package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.port.PortState;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.*;

/** 每条端口至多九个实物槽和十个样本；严格拒绝混合或缺失字段。 */
final class PortRecordCodec {
    static CompoundTag encode(PortState port) {
        var tag = new CompoundTag();
        tag.putUUID("id", port.id()); tag.put("origin", CapacityRecordCodec.origin(port.origin()));
        tag.putString("kind", port.kind().name()); tag.putLong("revision", port.revision());
        tag.putLong("configuration", port.configuration()); tag.putBoolean("enabled", port.enabled());
        var target = new CompoundTag(); if (port.target() != null) target.putUUID("member", port.target()); tag.put("target", target);
        var slots = new ListTag();
        for (var stack : port.supplies()) {
            var slot = new CompoundTag();
            if (!stack.empty()) { slot.put("key", ProductRecordCodec.key(stack.key())); slot.putInt("count", stack.count()); }
            slots.add(slot);
        }
        tag.put("supplies", slots);
        var filters = new ListTag();
        port.filters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var filter = new CompoundTag(); filter.putInt("slot", entry.getKey()); filter.put("key", ProductRecordCodec.key(entry.getValue())); filters.add(filter);
        });
        tag.put("filters", filters);
        var wrapper = new CompoundTag(); wrapper.put("data", tag); return wrapper;
    }
    static PortState decode(CompoundTag wrapper, Consumer<ProductKey> validate, java.util.function.ToIntFunction<ProductKey> limits) {
        fields(wrapper, "data"); return data(StrictNbt.compound(wrapper, "data"), validate, limits);
    }
    static PortState data(CompoundTag tag, Consumer<ProductKey> validate, java.util.function.ToIntFunction<ProductKey> limits) {
        fields(tag, "id", "origin", "kind", "revision", "configuration", "enabled", "target", "supplies", "filters");
        var codec = new ProductRecordCodec(validate); var supplies = StrictNbt.list(tag, "supplies");
        var filters = StrictNbt.list(tag, "filters");
        if (supplies.size() != PortState.SLOTS || filters.size() > 10) throw new IllegalArgumentException("Invalid port list size");
        var stacks = new ArrayList<PortState.Stack>();
        for (var raw : supplies) {
            var slot = (CompoundTag) raw;
            if (slot.isEmpty()) stacks.add(PortState.Stack.EMPTY);
            else {
                fields(slot, "key", "count");
                var stack = new PortState.Stack(codec.readKey(StrictNbt.compound(slot, "key")), StrictNbt.integer(slot, "count"));
                if (stack.count() > limits.applyAsInt(stack.key())) throw new IllegalArgumentException("Supply stack exceeds its component limit");
                stacks.add(stack);
            }
        }
        var keys = new HashMap<Integer, ProductKey>();
        for (var raw : filters) {
            var filter = (CompoundTag) raw; fields(filter, "slot", "key");
            if (keys.putIfAbsent(StrictNbt.integer(filter, "slot"), codec.readKey(StrictNbt.compound(filter, "key"))) != null)
                throw new IllegalArgumentException("Duplicate port filter");
        }
        var target = StrictNbt.compound(tag, "target"); UUID member = null;
        if (!target.isEmpty()) { fields(target, "member"); member = StrictNbt.uuid(target, "member"); }
        var origin = StrictNbt.compound(tag, "origin"); fields(origin, "dimension", "x", "y", "z");
        return new PortState(StrictNbt.uuid(tag, "id"), CapacityRecordCodec.readOrigin(origin),
                StrictNbt.choice(tag, "kind", PortState.Kind.class), StrictNbt.number(tag, "revision"), StrictNbt.number(tag, "configuration"),
                StrictNbt.bool(tag, "enabled"), member, stacks, keys);
    }
    private static void fields(CompoundTag tag, String... names) {
        if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("Invalid port fields");
    }
    private PortRecordCodec() { }
}
