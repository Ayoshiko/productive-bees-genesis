package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import net.minecraft.nbt.*;

/** 只读、截断的组件说明；不编码整棵 NBT，也不作为资产身份或相等判断。 */
final class ProductComponentPreview {
	private static final java.util.List<String> PREFERRED = java.util.List.of("productivebees:bee_type", "minecraft:custom_name");
	static String describe(CompoundTag components, int limit) {
		var preview = new ProductComponentPreview(limit);
		preview.append(components, 0);
		return preview.text.toString();
	}
	private final StringBuilder text = new StringBuilder();
	private final int limit;
	private int nodes;
	private boolean truncated;
	private ProductComponentPreview(int limit) { this.limit = limit; }
	private void add(String value) {
		int remaining = limit - text.length();
		if (remaining <= 0 || truncated) return;
		if (value.length() <= remaining) { text.append(value); return; }
		int end = Math.max(0, remaining - 1);
		if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
		text.append(value, 0, end).append('…');
		truncated = true;
	}
	private void append(Tag value, int depth) {
		if (++nodes > 12 || text.length() >= limit) { add("…"); return; }
		if (value instanceof StringTag string) add(string.getAsString());
		else if (value instanceof NumericTag) add(value.toString());
		else if (value instanceof CompoundTag compound) {
			if (depth >= 2) { add("{…}"); return; }
			add("{"); int seen = 0;
			if (depth == 0) for (String key : PREFERRED) if (compound.contains(key)) {
				if (seen++ > 0) add(", ");
				add(key); add("="); append(compound.get(key), depth + 1);
			}
			for (String key : compound.getAllKeys()) {
				if (depth == 0 && PREFERRED.contains(key)) continue;
				if (nodes >= 12 || text.length() >= limit - 1) { add("…"); break; }
				if (seen++ > 0) add(", ");
				add(key); add("="); append(compound.get(key), depth + 1);
			}
			add("}");
		} else if (value instanceof CollectionTag<?> collection) add("[" + collection.size() + "]");
		else add("…");
	}
}
