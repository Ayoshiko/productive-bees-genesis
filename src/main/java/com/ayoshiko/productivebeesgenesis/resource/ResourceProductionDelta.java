package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只为打开的资源窗口传输变化；新增身份带 key，后续仅传槽号、统计量和状态。 */
public record ResourceProductionDelta(long revision, boolean reset, long windowMillis, List<Entry> entries) {
    public static final int MAX_ENTRIES = ResourceAmounts.MAX_KEYS;
    public record Entry(int id, ResourceKey key, BigInteger produced, ResourceProductionSnapshot.State state) {
        public Entry {
            if (id < 0 || id >= MAX_ENTRIES || produced == null || produced.signum() < 0
                    || produced.compareTo(ResourceExactAmounts.MAX_VALUE) > 0) {
                throw new IllegalArgumentException("Invalid production delta");
            }
        }
    }
    public ResourceProductionDelta {
        if (revision < 0 || windowMillis < 1 || windowMillis > 5000 || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid production delta header");
        }
        entries = List.copyOf(entries);
        var ids = new HashSet<Integer>();
        for (var entry : entries) if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate production slot");
    }
    public static ResourceProductionDelta empty() { return new ResourceProductionDelta(0, true, 5000, List.of()); }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(revision);
        buffer.writeBoolean(reset);
        buffer.writeVarLong(windowMillis);
        buffer.writeVarInt(entries.size());
        for (var entry : entries) {
            buffer.writeVarInt(entry.id());
            buffer.writeBoolean(entry.state() != null);
            if (entry.state() == null) continue;
            buffer.writeBoolean(entry.key() != null);
            if (entry.key() != null) buffer.writeNbt(entry.key().save());
            buffer.writeUtf(entry.produced().toString(), ResourceExactAmounts.MAX_DECIMAL_DIGITS);
            buffer.writeEnum(entry.state());
        }
    }
    public static ResourceProductionDelta decode(FriendlyByteBuf buffer) {
        long revision = buffer.readVarLong();
        boolean reset = buffer.readBoolean();
        long window = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Production delta exceeds budget");
        var entries = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            int id = buffer.readVarInt();
            if (!buffer.readBoolean()) {
                entries.add(new Entry(id, null, BigInteger.ZERO, null));
                continue;
            }
            ResourceKey key = null;
            if (buffer.readBoolean()) {
                var raw = buffer.readNbt(new NbtAccounter(4096));
                if (raw==null) throw new IllegalArgumentException("Invalid production key"); var tag=raw;
                key = ResourceKey.load(tag);
            }
            var decimal = buffer.readUtf(ResourceExactAmounts.MAX_DECIMAL_DIGITS);
            var amount = decimal.equals("0") ? BigInteger.ZERO : ResourceExactAmounts.parse(decimal);
            entries.add(new Entry(id, key, amount, buffer.readEnum(ResourceProductionSnapshot.State.class)));
        }
        return new ResourceProductionDelta(revision, reset, window, entries);
    }
    public static final class Publisher {
        private final ResourceKey[] keys = new ResourceKey[MAX_ENTRIES];
        private Map<ResourceKey, ResourceProductionSnapshot.Entry> previous = Map.of();
        private long revision;
        public ResourceProductionDelta update(ResourceProductionSnapshot snapshot, long acknowledged) {
            var current = new LinkedHashMap<ResourceKey, ResourceProductionSnapshot.Entry>();
            for (var row : snapshot.entries()) current.put(row.key(), row);
            boolean reset = revision == 0 || acknowledged != revision;
            var changes = new ArrayList<Entry>();
            var removed = new java.util.BitSet(MAX_ENTRIES);
            for (int i = 0; i < keys.length; i++) {
                var key = keys[i];
                if (key == null) continue;
                var row = current.remove(key);
                if (row == null) {
                    keys[i] = null;
                    removed.set(i);
                } else if (reset || !row.equals(previous.get(key))) {
                    changes.add(new Entry(i, reset ? key : null, row.produced(), row.state()));
                }
            }
            var additions = current.values().iterator();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != null) continue;
                if (additions.hasNext()) {
                    var row = additions.next();
                    keys[i] = row.key();
                    changes.add(new Entry(i, row.key(), row.produced(), row.state()));
                } else if (!reset && removed.get(i)) changes.add(new Entry(i, null, BigInteger.ZERO, null));
            }
            var next = new LinkedHashMap<ResourceKey, ResourceProductionSnapshot.Entry>();
            for (var row : snapshot.entries()) next.put(row.key(), row);
            previous = next;
            if (reset || !changes.isEmpty()) revision++;
            return new ResourceProductionDelta(revision, reset, snapshot.windowMillis(), changes);
        }
    }
    public static final class Receiver {
        private ResourceProductionSnapshot.Entry[] rows = new ResourceProductionSnapshot.Entry[MAX_ENTRIES];
        private long revision;
        public long revision() { return revision; }
        public ResourceProductionSnapshot apply(ResourceProductionDelta delta) {
            if (!delta.reset() && delta.revision() != revision && delta.revision() != revision + 1) {
                throw new IllegalArgumentException("Production delta gap");
            }
            var next = delta.reset() ? new ResourceProductionSnapshot.Entry[MAX_ENTRIES] : rows.clone();
            for (var change : delta.entries()) {
                if (change.state() == null) {
                    next[change.id()] = null;
                    continue;
                }
                var old = next[change.id()];
                var key = change.key();
                if (key == null && old != null) key = old.key();
                if (key == null) throw new IllegalArgumentException("Missing production identity");
                next[change.id()] = new ResourceProductionSnapshot.Entry(key, change.produced(), change.state());
            }
            var values = new ArrayList<ResourceProductionSnapshot.Entry>();
            for (var row : next) if (row != null) values.add(row);
            var snapshot = new ResourceProductionSnapshot(delta.windowMillis(), values);
            rows = next;
            revision = delta.revision();
            return snapshot;
        }
    }
}
