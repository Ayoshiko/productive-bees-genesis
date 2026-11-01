package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.NbtAccounter;
import java.util.*;
import java.math.BigInteger;

/** 窗口专用目录：身份只在加入或替换时发送，平时只发送槽号和精确整数数量。 */
public record ResourceInventoryDelta(long revision, boolean reset, List<Entry> entries) {
    public static final int MAX_ENTRIES = ResourceAmounts.MAX_KEYS;
    public record Entry(int id, ResourceKey key, BigInteger exactAmount) {
        public Entry(int id, ResourceKey key, long amount) { this(id, key, BigInteger.valueOf(amount)); }
        public long amount() { return exactAmount.longValueExact(); }
        public Entry {
            if (id < 0 || id >= MAX_ENTRIES || exactAmount == null || exactAmount.signum() < 0 || exactAmount.compareTo(ResourceExactAmounts.MAX_VALUE) > 0) throw new IllegalArgumentException("Invalid resource delta");
        }
    }
    public ResourceInventoryDelta {
        if (revision < 0 || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid inventory revision");
        entries = List.copyOf(entries);
        var ids = new HashSet<Integer>();
        for (var entry : entries) if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate delta slot");
    }
    public static ResourceInventoryDelta empty() { return new ResourceInventoryDelta(0, true, List.of()); }
    public void encode(FriendlyByteBuf b) {
        b.writeVarLong(revision); b.writeBoolean(reset); b.writeVarInt(entries.size());
        for (var entry : entries) {
            b.writeVarInt(entry.id); b.writeUtf(entry.exactAmount.toString(), ResourceExactAmounts.MAX_DECIMAL_DIGITS); b.writeBoolean(entry.key != null);
            if (entry.key != null) b.writeNbt(entry.key.save());
        }
    }
    public static ResourceInventoryDelta decode(FriendlyByteBuf b) {
        long revision = b.readVarLong(); boolean reset = b.readBoolean(); int size = b.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("Resource directory exceeds budget");
        var entries = new ArrayList<Entry>(size);
        for (int i = 0; i < size; i++) {
            int id = b.readVarInt();
            String decimal = b.readUtf(ResourceExactAmounts.MAX_DECIMAL_DIGITS);
            BigInteger amount = decimal.equals("0") ? BigInteger.ZERO : ResourceExactAmounts.parse(decimal);
            ResourceKey key = null;
            if (b.readBoolean()) {
                var raw = b.readNbt(new NbtAccounter(4096));
                if (raw==null) throw new IllegalArgumentException("Invalid resource key"); var tag=raw;
                key = ResourceKey.load(tag);
            }
            entries.add(new Entry(id, key, amount));
        }
        return new ResourceInventoryDelta(revision, reset, entries);
    }

    /** 每个当前菜单会话一份；只保留上一份不可变余额与有界目录，关菜单立即释放。 */
    public static final class Publisher {
        private final ResourceKey[] keys = new ResourceKey[MAX_ENTRIES];
        private Map<ResourceKey, BigInteger> previous = Map.of();
        private long revision;
        public ResourceInventoryDelta update(Map<ResourceKey, ? extends Number> values, long acknowledged) {
            Map<ResourceKey, BigInteger> current = ResourceExactAmounts.copy(values);
            if (current.size() > MAX_ENTRIES) throw new IllegalArgumentException("Resource directory exceeds budget");
            boolean reset = acknowledged != revision || revision == 0;
            if (!reset && current == previous) return new ResourceInventoryDelta(revision, false, List.of());
            var changes = new ArrayList<Entry>();
            var removed = new BitSet(MAX_ENTRIES);
            var pending = new LinkedHashMap<>(current);
            for (int i = 0; i < keys.length; i++) {
                var key = keys[i];
                if (key != null && current.containsKey(key)) {
                    BigInteger amount = pending.remove(key);
                    if (reset || !amount.equals(previous.getOrDefault(key, BigInteger.ZERO))) changes.add(new Entry(i, reset ? key : null, amount));
                } else if (key != null) {
                    keys[i] = null;
                    removed.set(i);
                    // 空槽的最终值统一在后面写入，避免同一 delta 对同一个 id 写两次。
                }
            }
            var additions = pending.entrySet().iterator();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != null) continue;
                if (additions.hasNext()) {
                    var entry = additions.next(); keys[i] = entry.getKey();
                    changes.add(new Entry(i, entry.getKey(), entry.getValue()));
                } else if (!reset && removed.get(i)) {
                    changes.add(new Entry(i, null, 0));
                }
            }
            previous = current;
            if (!reset && changes.isEmpty()) return new ResourceInventoryDelta(revision, false, List.of());
            return new ResourceInventoryDelta(++revision, reset, changes);
        }
    }

    /** 客户端整批校验后才替换视图；断档用下次查询请求完整目录。 */
    public static final class Receiver {
        private ResourceKey[] keys = new ResourceKey[MAX_ENTRIES];
        private BigInteger[] amounts = new BigInteger[MAX_ENTRIES];
        private long revision;
        public long revision() { return revision; }
        public List<ResourceStack> apply(ResourceInventoryDelta delta) {
            if (!delta.reset && delta.revision != revision && delta.revision != revision + 1)
                throw new IllegalArgumentException("Resource delta revision gap");
            var nextKeys = delta.reset ? new ResourceKey[MAX_ENTRIES] : keys.clone();
            var nextAmounts = delta.reset ? new BigInteger[MAX_ENTRIES] : amounts.clone();
            for (var entry : delta.entries) {
                if (entry.key != null) nextKeys[entry.id] = entry.key;
                if (entry.exactAmount.signum() > 0 && nextKeys[entry.id] == null) throw new IllegalArgumentException("Missing resource identity");
                nextAmounts[entry.id] = entry.exactAmount;
                if (entry.exactAmount.signum() == 0) nextKeys[entry.id] = null;
            }
            var rows = new ArrayList<ResourceStack>();
            var seen = new HashSet<ResourceKey>();
            for (int i = 0; i < nextKeys.length; i++) if (nextKeys[i] != null) {
                if (!seen.add(nextKeys[i])) throw new IllegalArgumentException("Duplicate resource identity");
                ResourceExactAmounts.validate(nextKeys[i], nextAmounts[i]);
                rows.add(new ResourceStack(nextKeys[i], nextAmounts[i]));
            }
            keys = nextKeys; amounts = nextAmounts; revision = delta.revision;
            return List.copyOf(rows);
        }
    }
}
