package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class ProductRecordCodec {
	private final Consumer<ProductKey> validate;
	public ProductRecordCodec(Consumer<ProductKey> validate) { this.validate = java.util.Objects.requireNonNull(validate); }
	public static CompoundTag key(ProductKey key) {
		var tag = new CompoundTag();
		tag.putString("kind", key.kind().name()); tag.putString("id", key.id().toString()); tag.put("components", key.components());
		return tag;
	}
	public ProductKey readKey(CompoundTag tag) {
		if (!tag.getAllKeys().equals(java.util.Set.of("kind", "id", "components"))) throw new IllegalArgumentException("Unknown or missing product key fields");
		var key = new ProductKey(StrictNbt.choice(tag, "kind", ProductKey.Kind.class),
				ResourceLocation.parse(StrictNbt.string(tag, "id")), StrictNbt.compound(tag, "components"));
		validate.accept(key);
		return key;
	}
	public static Tag amount(ProductAmount amount) {
		return amount.fitsLong() ? LongTag.valueOf(amount.longSaturated()) : new ByteArrayTag(amount.exact().toByteArray());
	}
	public static ProductAmount readAmount(CompoundTag tag, String name) {
		Tag value = tag.get(name);
		if (value instanceof LongTag number) return ProductAmount.of(number.getAsLong());
		if (value instanceof ByteArrayTag bytes) {
			byte[] encoded = bytes.getAsByteArray();
			if (encoded.length == 0) throw new IllegalArgumentException("Empty integer");
			BigInteger number = new BigInteger(encoded);
			if (number.signum() <= 0 || number.bitLength() <= 63 || !Arrays.equals(number.toByteArray(), encoded)) {
				throw new IllegalArgumentException("Noncanonical large amount");
			}
			return ProductAmount.of(number);
		}
		throw new IllegalArgumentException("Missing or invalid amount: " + name);
	}
	public static CompoundTag entry(ProductKey key, ProductAmount amount) {
		var tag = new CompoundTag(); tag.put("key", key(key)); tag.put("amount", amount(amount)); return tag;
	}
	public static ListTag amounts(Map<ProductKey, ProductAmount> amounts) {
		var list = new ListTag(); amounts.forEach((key, amount) -> list.add(entry(key, amount))); return list;
	}
	public Map<ProductKey, ProductAmount> readAmounts(ListTag list) {
		Map<ProductKey, ProductAmount> values = new ConcurrentHashMap<>();
		for (Tag raw : list) {
			if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("Expected amount record");
			if (!tag.getAllKeys().equals(java.util.Set.of("key", "amount"))) throw new IllegalArgumentException("Unknown or missing amount fields");
			var key = readKey(StrictNbt.compound(tag, "key"));
			var amount = readAmount(tag, "amount");
			if (amount.isZero() || values.putIfAbsent(key, amount) != null) throw new IllegalArgumentException("Duplicate or empty balance");
		}
		return Map.copyOf(values);
	}
}
