package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import net.minecraft.nbt.CompoundTag;

/** 每次流式读取独享；只记格式位，不依赖 schema 与 ownership 字段的先后顺序。 */
final class BeeSchemaVersion {
	private int observed;
	static void requireSupported(int schema) {
		if (schema != 6 && schema != 7 && schema != 8) throw new IllegalArgumentException("Unsupported network schema");
	}
	int observe(CompoundTag bees) {
		int schema = 6;
		if (bees.contains("samplingVersion")) {
			int version = StrictNbt.integer(bees, "samplingVersion");
			if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported bee sampling version");
			schema = version + 6;
		}
		if (!bees.isEmpty()) observed |= 1 << (schema - 6);
		return schema;
	}
	void validate(int schema) {
		requireSupported(schema);
		if (observed != 0 && observed != (1 << (schema - 6)))
			throw new IllegalArgumentException("Bee sampling format differs from network schema");
	}
}
