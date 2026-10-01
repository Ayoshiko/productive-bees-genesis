package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import net.minecraft.nbt.CompoundTag;

/** 每次流式读取独享；只记格式位，不依赖 schema 与 ownership 字段的先后顺序。 */
final class BeeSchemaVersion {
	private int observed;
	static void requireSupported(int schema) {
		if (schema != 6 && schema != 7) throw new IllegalArgumentException("Unsupported network schema");
	}
	int observe(CompoundTag bees) {
		int schema = bees.contains("samplingVersion") ? 7 : 6;
		if (!bees.isEmpty()) observed |= schema == 6 ? 1 : 2;
		return schema;
	}
	void validate(int schema) {
		requireSupported(schema);
		if (observed != 0 && observed != (schema == 6 ? 1 : 2))
			throw new IllegalArgumentException("Bee sampling format differs from network schema");
	}
}
