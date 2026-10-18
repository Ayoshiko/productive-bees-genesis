package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/** 有界精确数量映射；所有验证都在修改账户之前完成。 */
final class ResourceAmounts {
	// 单配方仍限 16 项；账户须容纳 19 个处理槽的不同产物，并为换料留出余量。
	static final int MAX_KEYS = 512;

	private ResourceAmounts() {}

	static Map<ResourceKey, Long> copy(Map<ResourceKey, Long> amounts) {
		if (amounts.size() > MAX_KEYS) throw new IllegalArgumentException("Too many resource identities");
		var result = new LinkedHashMap<ResourceKey, Long>();
		amounts.forEach((key, amount) -> {
			if (key == null || amount == null || amount <= 0) {
				throw new IllegalArgumentException("Resource amounts must be positive");
			}
			result.put(key, amount);
		});
		return Map.copyOf(result);
	}

	static Map<ResourceKey, Long> plus(Map<ResourceKey, Long> first, Map<ResourceKey, Long> second) {
		var result = new LinkedHashMap<>(first);
		second.forEach((key, amount) -> result.merge(key, amount, Math::addExact));
		return copy(result);
	}

	/** 只读容量检查不构建合并余额；first 已由账户验证并封闭。 */
	static void checkAddition(Map<ResourceKey, Long> first, Map<ResourceKey, Long> second) {
		if (second.size() > MAX_KEYS) throw new IllegalArgumentException("Too many resource identities");
		int keys = first.size();
		for (var entry : second.entrySet()) {
			var key = entry.getKey();
			var amount = entry.getValue();
			if (key == null || amount == null || amount <= 0) {
				throw new IllegalArgumentException("Resource amounts must be positive");
			}
			var existing = first.get(key);
			if (existing == null) {
				if (++keys > MAX_KEYS) throw new IllegalArgumentException("Too many resource identities");
			} else {
				Math.addExact(existing, amount);
			}
		}
	}

	static void within(Map<ResourceKey, Long> actual, Map<ResourceKey, Long> maximum) {
		actual.forEach((key, amount) -> {
			if (amount > maximum.getOrDefault(key, 0L)) {
				throw new IllegalArgumentException("Frozen result exceeds reservation");
			}
		});
	}

	static ListTag save(Map<ResourceKey, Long> amounts) {
		var list = new ListTag();
		amounts.forEach((key, amount) -> {
			var entry = new CompoundTag();
			entry.put("key", key.save());
			entry.putLong("amount", amount);
			list.add(entry);
		});
		return list;
	}

	static Map<ResourceKey, Long> load(Tag raw) {
		if (!(raw instanceof ListTag list) || list.size() > MAX_KEYS
				|| (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
			throw new IllegalArgumentException("Invalid resource amount list");
		}
		var result = new LinkedHashMap<ResourceKey, Long>();
		for (Tag element : list) {
			var entry = (CompoundTag) element;
			ResourceAccountData.keys(entry, java.util.Set.of("key", "amount"));
			if (!entry.contains("key", Tag.TAG_COMPOUND) || !entry.contains("amount", Tag.TAG_LONG)) {
				throw new IllegalArgumentException("Incomplete resource amount");
			}
			var key = ResourceKey.load(entry.getCompound("key"));
			if (result.putIfAbsent(key, entry.getLong("amount")) != null) {
				throw new IllegalArgumentException("Duplicate resource identity");
			}
		}
		return copy(result);
	}
}
