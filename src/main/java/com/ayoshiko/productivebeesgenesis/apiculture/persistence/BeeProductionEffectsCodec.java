package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeProductionEffects;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 完整基因模板和精华规则随已付周期保存，不从读档时的配方补造。 */
final class BeeProductionEffectsCodec {
	static CompoundTag encode(BeeProductionEffects effects) {
		var tag = new CompoundTag();
		tag.putBoolean("essence", effects.essence());
		tag.putBoolean("discard", effects.discardByproducts());
		tag.putFloat("chance", effects.geneChance());
		var genes = new ListTag();
		for (var key : effects.genes()) genes.add(ProductRecordCodec.key(key));
		tag.put("genes", genes);
		var conversions = new ListTag();
		for (var rule : effects.conversions()) {
			var value = new CompoundTag();
			value.put("source", ProductRecordCodec.key(rule.source()));
			value.put("target", ProductRecordCodec.key(rule.target()));
			value.putInt("input", rule.input());
			value.putInt("output", rule.output());
			value.putBoolean("chained", rule.chained());
			conversions.add(value);
		}
		tag.put("conversions", conversions);
		var removed = new ListTag();
		effects.discarded().stream().sorted(java.util.Comparator.comparing(ProductKey::orderingKey))
				.forEach(key -> removed.add(ProductRecordCodec.key(key)));
		tag.put("removed", removed);
		return tag;
	}
	static BeeProductionEffects decode(CompoundTag tag, Consumer<ProductKey> validate) {
		if (!tag.getAllKeys().equals(Set.of("essence", "discard", "chance", "genes", "conversions", "removed"))
				|| !tag.contains("chance", Tag.TAG_FLOAT)) throw new IllegalArgumentException("Invalid bee effects fields");
		var codec = new ProductRecordCodec(validate);
		var genes = new ArrayList<ProductKey>();
		for (var raw : StrictNbt.list(tag, "genes")) genes.add(codec.readKey((CompoundTag) raw));
		var conversions = new ArrayList<BeeProductionEffects.Conversion>();
		for (var raw : StrictNbt.list(tag, "conversions")) {
			var value = (CompoundTag) raw;
			if (!value.getAllKeys().equals(Set.of("source", "target", "input", "output", "chained")))
				throw new IllegalArgumentException("Invalid bee conversion fields");
			conversions.add(new BeeProductionEffects.Conversion(codec.readKey(StrictNbt.compound(value, "source")),
					codec.readKey(StrictNbt.compound(value, "target")), StrictNbt.integer(value, "input"),
					StrictNbt.integer(value, "output"), StrictNbt.bool(value, "chained")));
		}
		var removed = new HashSet<ProductKey>();
		for (var raw : StrictNbt.list(tag, "removed"))
			if (!removed.add(codec.readKey((CompoundTag) raw))) throw new IllegalArgumentException("Duplicate discarded bee output");
		return new BeeProductionEffects(StrictNbt.bool(tag, "essence"), StrictNbt.bool(tag, "discard"),
				tag.getFloat("chance"), genes, conversions, removed);
	}
	private BeeProductionEffectsCodec() { }
}
