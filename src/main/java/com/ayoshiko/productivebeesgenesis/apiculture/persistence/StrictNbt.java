package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** NBT 默认值会掩盖缺字段；权威数据必须区分缺失、错误类型与合法零值。 */
final class StrictNbt {
	private StrictNbt() { }
	static Tag require(CompoundTag root, String key, int type) {
		Tag value = root.get(key);
		if (value == null || value.getId() != type) throw new IllegalArgumentException("Missing or invalid field: " + key);
		return value;
	}
	static CompoundTag compound(CompoundTag root, String key) { return (CompoundTag) require(root, key, Tag.TAG_COMPOUND); }
	static ListTag list(CompoundTag root, String key) {
		var list = (ListTag) require(root, key, Tag.TAG_LIST);
		if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Expected record list: " + key);
		return list;
	}
	static String string(CompoundTag root, String key) { require(root, key, Tag.TAG_STRING); return root.getString(key); }
	static int integer(CompoundTag root, String key) { require(root, key, Tag.TAG_INT); return root.getInt(key); }
	static long number(CompoundTag root, String key) { require(root, key, Tag.TAG_LONG); return root.getLong(key); }
	static double decimal(CompoundTag root, String key) { require(root, key, Tag.TAG_DOUBLE); return root.getDouble(key); }
	static boolean bool(CompoundTag root, String key) {
		require(root, key, Tag.TAG_BYTE);
		byte value = root.getByte(key);
		if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid boolean: " + key);
		return value == 1;
	}
	static UUID uuid(CompoundTag root, String key) {
		require(root, key, Tag.TAG_INT_ARRAY);
		if (!root.hasUUID(key)) throw new IllegalArgumentException("Invalid UUID: " + key);
		return root.getUUID(key);
	}
	static <E extends Enum<E>> E choice(CompoundTag root, String key, Class<E> type) { return Enum.valueOf(type, string(root, key)); }
}
