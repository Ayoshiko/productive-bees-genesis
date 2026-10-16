package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGeneration;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 外部生成请求与已固定候选池的严格格式，不通过读档补造或再次执行结果。 */
final class BeeGenerationCodec {
	static CompoundTag encode(BeeGeneration generation) {
		var tag = new CompoundTag();
		tag.put("source", generation.source().copy());
		tag.putString("phase", generation.phase().name());
		var pool = new ListTag();
		for (var drop : generation.pool()) {
			var value = new CompoundTag();
			value.put("key", ProductRecordCodec.key(drop.key()));
			value.putInt("count", drop.count());
			if (drop.weight() != 1) value.putDouble("weight", drop.weight());
			pool.add(value);
		}
		tag.put("pool", pool);
		tag.putBoolean("fixed", generation.fixedDone());
		tag.put("consumed", ProductRecordCodec.amount(generation.consumed()));
		tag.putString("failure", generation.failure());
		return tag;
	}
	static BeeGeneration decode(CompoundTag tag, Consumer<ProductKey> validate, boolean weighted) {
		if (!tag.getAllKeys().equals(Set.of("source", "phase", "pool", "fixed", "consumed", "failure")))
			throw new IllegalArgumentException("Unknown or missing bee generation fields");
		var codec = new ProductRecordCodec(validate);
		var pool = new ArrayList<BeeGeneration.Drop>();
		for (var raw : StrictNbt.list(tag, "pool")) {
			var value = (CompoundTag) raw;
			var expected = new java.util.HashSet<>(Set.of("key", "count"));
			if (weighted && value.contains("weight")) expected.add("weight");
			if (!value.getAllKeys().equals(expected) || value.contains("weight") && !value.contains("weight", Tag.TAG_DOUBLE))
				throw new IllegalArgumentException("Invalid generated bee pool entry");
			pool.add(new BeeGeneration.Drop(codec.readKey(StrictNbt.compound(value, "key")), StrictNbt.integer(value, "count"),
					value.contains("weight") ? value.getDouble("weight") : 1));
		}
		return new BeeGeneration(new AssetImage(StrictNbt.compound(tag, "source")), StrictNbt.choice(tag, "phase", BeeGeneration.Phase.class),
				pool, StrictNbt.bool(tag, "fixed"), ProductRecordCodec.readAmount(tag, "consumed"), StrictNbt.string(tag, "failure"));
	}
	private BeeGenerationCodec() { }
}
