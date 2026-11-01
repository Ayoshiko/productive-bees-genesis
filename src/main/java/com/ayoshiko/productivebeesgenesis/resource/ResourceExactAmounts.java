package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** 权威数量使用精确整数；EMC 可超过 long，其他资源保留原能力上限。 */
public final class ResourceExactAmounts {
    public static final int MAX_DECIMAL_DIGITS = 4096;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    public static final BigInteger MAX_VALUE = BigInteger.TEN.pow(MAX_DECIMAL_DIGITS).subtract(BigInteger.ONE);

    private ResourceExactAmounts() {}

    public static BigInteger number(Number value) {
        if (value instanceof BigInteger exact) return exact;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(value.longValue());
        }
        throw new IllegalArgumentException("Resource amount must be an exact integer");
    }

    public static BigInteger parse(String value) {
        if (value.length() > MAX_DECIMAL_DIGITS || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid exact resource amount");
        }
        return new BigInteger(value);
    }

    public static BigInteger validate(ResourceKey key, BigInteger amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_VALUE) > 0) {
            throw new IllegalArgumentException("Resource amount exceeds its exact type or persistence budget");
        }
        if (!ResourceTypes.EMC.equals(key)) amount.longValueExact();
        return amount;
    }

    public static Map<ResourceKey, BigInteger> copy(Map<ResourceKey, ? extends Number> amounts) {
        if (amounts.size() > ResourceAmounts.MAX_KEYS) throw new IllegalArgumentException("Too many resource identities");
        var result = new LinkedHashMap<ResourceKey, BigInteger>();
        amounts.forEach((key, value) -> {
            if (key == null || value == null) throw new IllegalArgumentException("Missing resource identity or quantity");
            result.put(key, validate(key, number(value)));
        });
        return Map.copyOf(result);
    }

    public static Map<ResourceKey, BigInteger> plus(Map<ResourceKey, ? extends Number> first,
            Map<ResourceKey, ? extends Number> second) {
        var result = new LinkedHashMap<>(copy(first));
        second.forEach((key, value) -> result.merge(key, number(value), BigInteger::add));
        return copy(result);
    }

    public static void checkAddition(Map<ResourceKey, BigInteger> first, Map<ResourceKey, ? extends Number> second) {
        if (second.size() > ResourceAmounts.MAX_KEYS) throw new IllegalArgumentException("Too many resource identities");
        int keys = first.size();
        for (var entry : second.entrySet()) {
            var amount = validate(entry.getKey(), number(entry.getValue()));
            var previous = first.get(entry.getKey());
            if (previous == null) {
                if (++keys > ResourceAmounts.MAX_KEYS) throw new IllegalArgumentException("Too many resource identities");
            } else validate(entry.getKey(), previous.add(amount));
        }
    }

    public static void within(Map<ResourceKey, BigInteger> actual, Map<ResourceKey, BigInteger> maximum) {
        actual.forEach((key, amount) -> {
            if (amount.compareTo(maximum.getOrDefault(key, BigInteger.ZERO)) > 0) {
                throw new IllegalArgumentException("Frozen result exceeds reservation");
            }
        });
    }

    /** 旧能力只暴露单次可处理量；该视图不得写回账户或用于保存。 */
    public static Map<ResourceKey, Long> boundedView(Map<ResourceKey, BigInteger> amounts) {
        var result = new LinkedHashMap<ResourceKey, Long>();
        amounts.forEach((key, value) -> result.put(key, value.min(LONG_MAX).longValueExact()));
        return Map.copyOf(result);
    }

    public static Map<ResourceKey, Long> longExact(Map<ResourceKey, BigInteger> amounts) {
        var result = new LinkedHashMap<ResourceKey, Long>();
        amounts.forEach((key, value) -> result.put(key, value.longValueExact()));
        return Map.copyOf(result);
    }

    public static ListTag save(Map<ResourceKey, BigInteger> amounts) {
        var result = new ListTag();
        amounts.forEach((key, amount) -> {
            var entry = new CompoundTag();
            entry.put("key", key.save());
            write(entry, "amount", key, amount);
            result.add(entry);
        });
        return result;
    }

    public static void write(CompoundTag tag, String field, ResourceKey key, BigInteger amount) {
        validate(key, amount);
        if (amount.compareTo(LONG_MAX) <= 0) tag.putLong(field, amount.longValueExact());
        else tag.putString(field, amount.toString());
    }

    public static BigInteger read(CompoundTag tag, String field, ResourceKey key) {
        if (tag.contains(field, Tag.TAG_LONG)) return validate(key, BigInteger.valueOf(tag.getLong(field)));
        if (ResourceTypes.EMC.equals(key) && tag.contains(field, Tag.TAG_STRING)) {
            return validate(key, parse(tag.getString(field)));
        }
        throw new IllegalArgumentException("Invalid exact resource quantity");
    }

    public static Map<ResourceKey, BigInteger> load(Tag raw) {
        if (!(raw instanceof ListTag list) || list.size() > ResourceAmounts.MAX_KEYS
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid resource amount list");
        }
        var result = new LinkedHashMap<ResourceKey, BigInteger>();
        for (Tag element : list) {
            var entry = (CompoundTag) element;
            ResourceAccountData.keys(entry, java.util.Set.of("key", "amount"));
            if (!entry.contains("key", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing resource key");
            var key = ResourceKey.load(entry.getCompound("key"));
            if (result.putIfAbsent(key, read(entry, "amount", key)) != null) {
                throw new IllegalArgumentException("Duplicate resource identity");
            }
        }
        return copy(result);
    }
}
