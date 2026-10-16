package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import net.minecraft.nbt.CompoundTag;

/** 每次流式读取独享；只记格式位，不依赖 schema 与 ownership 字段的先后顺序。 */
final class BeeSchemaVersion {
	private int observed;
	private int ownershipFields;
	private boolean modernCombined;
	private boolean generatedCombined;
	private boolean weightedCombined;
	private boolean effectsCombined;
	void observeCombined(CompoundTag value) {
		if (!value.isEmpty()) {
			int schema = StrictNbt.integer(value, "schema");
			if (schema >= 6) modernCombined = true;
			if (schema >= 7) generatedCombined = true;
			if (schema >= 8) weightedCombined = true;
			if (schema >= 9) effectsCombined = true;
		}
	}
	void observeOwnership(boolean combined) { ownershipFields |= combined ? 1 : 2; }
	static void requireSupported(int schema) {
		if (schema < 6 || schema > 18) throw new IllegalArgumentException("Unsupported network schema");
	}
	int observe(CompoundTag bees) {
		int schema = 6;
		if (bees.contains("samplingVersion")) {
			int version = StrictNbt.integer(bees, "samplingVersion");
			if (version < 1 || version > 9) throw new IllegalArgumentException("Unsupported bee sampling version");
			schema = version >= 5 ? version + 9 : version + 6;
		}
		if (!bees.isEmpty()) observed |= 1 << (schema - 6);
		return schema;
	}
	void validate(int schema) {
		requireSupported(schema);
		if ((ownershipFields & (schema >= 13 ? 2 : 1)) != 0) throw new IllegalArgumentException("Combined field differs from network schema");
		if (schema < 14 && modernCombined) throw new IllegalArgumentException("New combined work inside an old network schema");
		if (schema < 16 && generatedCombined) throw new IllegalArgumentException("Dynamic combined work inside an old network schema");
		if (schema < 17 && weightedCombined) throw new IllegalArgumentException("Weighted combined work inside an old network schema");
		if (schema < 18 && effectsCombined) throw new IllegalArgumentException("Bee effects inside an old network schema");
		if (observed != 0 && observed != (1 << (schema >= 14 ? schema - 6 : Math.min(schema, 10) - 6)))
			throw new IllegalArgumentException("Bee sampling format differs from network schema");
	}
}
