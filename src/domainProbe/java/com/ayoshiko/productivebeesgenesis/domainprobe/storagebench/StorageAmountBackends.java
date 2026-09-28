package com.ayoshiko.productivebeesgenesis.domainprobe.storagebench;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** 仅供数量模型比较；不包含上游的域管理、脏队列、压缩、收据或存盘协议。 */
final class StorageAmountBackends {
    static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    static final BigInteger MAX_126 = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
    static final String[] NAMES = {"ours-paged", "legacy-sparse", "model-dual126-wide", "model-biginteger"};

    interface Backend {
        ProductAmount amount(ProductKey key);
        void set(ProductKey key, ProductAmount value);
        void visit(BiConsumer<ProductKey, ProductAmount> visitor);
        default void add(ProductKey key, ProductAmount value) { set(key, amount(key).add(value)); }
        default ProductAmount extract(ProductKey key, ProductAmount requested, boolean simulate) {
            ProductAmount taken = amount(key).min(requested);
            if (!simulate) set(key, amount(key).subtract(taken));
            return taken;
        }
        default CompoundTag serialize() {
            var root = new CompoundTag();
            var entries = new ListTag();
            visit((key, value) -> {
                var entry = new CompoundTag();
                entry.putString("key", key.id().getPath());
                if (value.fitsLong()) entry.putLong("small", value.longSaturated());
                else entry.putByteArray("big", value.exact().toByteArray());
                entries.add(entry);
            });
            root.put("entries", entries);
            return root;
        }
    }

    static ProductKey key(int id) {
        // 同一组真实 ProductKey 供所有模型使用；不声称覆盖 AEKey 或复杂组件哈希。
        return new ProductKey(ProductKey.Kind.ITEM,
                ResourceLocation.fromNamespaceAndPath("pbgbench", Integer.toString(id)), new CompoundTag());
    }

    static Backend create(String name) {
        return switch (name) {
            case "ours-paged" -> new Paged();
            case "legacy-sparse" -> new Sparse();
            case "model-dual126-wide" -> new Dual();
            case "model-biginteger" -> new Big();
            default -> throw new IllegalArgumentException("Unknown backend " + name);
        };
    }

    private static final class Paged implements Backend {
        private final PagedProductAmounts values = new PagedProductAmounts();
        public ProductAmount amount(ProductKey key) { return values.amount(key); }
        public void set(ProductKey key, ProductAmount value) { values.set(key, value); }
        public void visit(BiConsumer<ProductKey, ProductAmount> visitor) { values.snapshot().forEach(visitor); }
    }

    private static final class Sparse implements Backend {
        private final SparseProductAmounts<ProductKey> values = new SparseProductAmounts<>();
        public ProductAmount amount(ProductKey key) { return values.amount(key); }
        public void set(ProductKey key, ProductAmount value) { values.set(key, value); }
        public void add(ProductKey key, ProductAmount value) { values.add(key, value); }
        public ProductAmount extract(ProductKey key, ProductAmount requested, boolean simulate) {
            return simulate ? values.amount(key).min(requested) : values.extract(key, requested);
        }
        public void visit(BiConsumer<ProductKey, ProductAmount> visitor) { values.snapshot().forEach(visitor); }
    }

    private static final class Big implements Backend {
        private final Object2ObjectOpenHashMap<ProductKey, BigInteger> values = new Object2ObjectOpenHashMap<>();
        public ProductAmount amount(ProductKey key) { return ProductAmount.of(values.getOrDefault(key, BigInteger.ZERO)); }
        public void set(ProductKey key, ProductAmount value) {
            if (value.isZero()) values.remove(key); else values.put(key, value.exact());
        }
        public void add(ProductKey key, ProductAmount value) {
            if (!value.isZero()) values.merge(key, value.exact(), BigInteger::add);
        }
        public void visit(BiConsumer<ProductKey, ProductAmount> visitor) {
            values.forEach((key, value) -> visitor.accept(key, ProductAmount.of(value)));
        }
    }

    /** 数组加稀疏宽值的实验模型；上游启用 arbitraryPrecision 后会走 BigInteger，不能套用本模型的快路成绩。 */
    private static final class Dual implements Backend {
        private final Object2IntOpenHashMap<ProductKey> ids = new Object2IntOpenHashMap<>();
        private final Map<ProductKey, BigInteger> wide = new HashMap<>();
        private ProductKey[] keys = new ProductKey[256];
        private long[] low = new long[256], high = new long[256];
        private int next;
        Dual() { ids.defaultReturnValue(-1); }
        private int allocate(ProductKey key) {
            if (next == keys.length) {
                keys = Arrays.copyOf(keys, next * 2);
                low = Arrays.copyOf(low, next * 2); high = Arrays.copyOf(high, next * 2);
            }
            int id = next++; keys[id] = key; ids.put(key, id); return id;
        }
        public ProductAmount amount(ProductKey key) {
            int id = ids.getInt(key);
            if (id < 0) return ProductAmount.ZERO;
            BigInteger large = wide.get(key);
            if (large != null) return ProductAmount.of(large);
            return high[id] == 0 ? ProductAmount.of(low[id])
                    : ProductAmount.of(BigInteger.valueOf(high[id]).shiftLeft(63).add(BigInteger.valueOf(low[id])));
        }
        public void set(ProductKey key, ProductAmount value) {
            int id = ids.getInt(key);
            if (value.isZero()) {
                if (id >= 0) { ids.removeInt(key); keys[id] = null; low[id] = high[id] = 0; }
                wide.remove(key); return;
            }
            if (id < 0) id = allocate(key);
            BigInteger exact = value.exact();
            BigInteger projection = exact.min(MAX_126);
            low[id] = projection.and(MAX_LONG).longValueExact();
            high[id] = projection.shiftRight(63).longValueExact();
            if (exact.compareTo(MAX_126) > 0) wide.put(key, exact); else wide.remove(key);
        }
        public void add(ProductKey key, ProductAmount value) {
            if (value.isZero()) return;
            int id = ids.getInt(key);
            if (!value.fitsLong() || wide.containsKey(key)) { Backend.super.add(key, value); return; }
            if (id < 0) id = allocate(key);
            long sum = low[id] + value.longSaturated();
            if (sum < 0) {
                if (high[id] == Long.MAX_VALUE) { Backend.super.add(key, value); return; }
                sum &= Long.MAX_VALUE; high[id]++;
            }
            low[id] = sum;
        }
        public void visit(BiConsumer<ProductKey, ProductAmount> visitor) {
            for (int id = 0; id < next; id++) if (keys[id] != null) visitor.accept(keys[id], amount(keys[id]));
        }
    }

    /** 独立严格 decoder，检验每个键及精确数量；不是生产 checkpoint 格式。 */
    static Map<ProductKey, BigInteger> decode(CompoundTag root) {
        if (!(root.get("entries") instanceof ListTag entries)) throw new IllegalStateException("Missing entries");
        Map<ProductKey, BigInteger> result = new HashMap<>();
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entry) || !entry.contains("key", Tag.TAG_STRING))
                throw new IllegalStateException("Invalid entry");
            var key = key(Integer.parseInt(entry.getString("key")));
            if (entry.contains("small") == entry.contains("big")) throw new IllegalStateException("Ambiguous amount");
            BigInteger value;
            if (entry.contains("small", Tag.TAG_LONG)) value = BigInteger.valueOf(entry.getLong("small"));
            else if (entry.contains("big", Tag.TAG_BYTE_ARRAY)) value = new BigInteger(entry.getByteArray("big"));
            else throw new IllegalStateException("Invalid amount type");
            if (value.signum() <= 0 || result.putIfAbsent(key, value) != null)
                throw new IllegalStateException("Duplicate or nonpositive entry");
        }
        return result;
    }

    private StorageAmountBackends() {}
}
