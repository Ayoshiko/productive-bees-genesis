package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import java.util.EnumMap;
import java.util.Map;
import mekanism.api.SerializationConstants;
import mekanism.api.Upgrade;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 严格读取封存升级；不能把重复、越界或损坏数据按原生宽松读取静默修正。 */
public final class NativeUpgradeCounts {
	public static Map<Upgrade, Integer> read(CompoundTag tag) {
		Map<Upgrade, Integer> result = new EnumMap<>(Upgrade.class);
		if (!tag.contains(SerializationConstants.UPGRADES)) return result;
		if (!(tag.get(SerializationConstants.UPGRADES) instanceof ListTag list)
				|| !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Malformed native upgrades");
		var types = Upgrade.values();
		if (list.size() > types.length) throw new IllegalArgumentException("Too many native upgrade entries");
		for (var raw : list) {
			var entry = (CompoundTag) raw;
			int ordinal = entry.getInt(SerializationConstants.TYPE), count = entry.getInt(SerializationConstants.AMOUNT);
			if (entry.size() != 2 || !entry.contains(SerializationConstants.TYPE, Tag.TAG_INT) || !entry.contains(SerializationConstants.AMOUNT, Tag.TAG_INT)
					|| ordinal < 0 || ordinal >= types.length || count <= 0
					|| result.putIfAbsent(types[ordinal], count) != null) throw new IllegalArgumentException("Invalid or duplicate native upgrade");
		}
		return result;
	}
	/** 保留组件的输入／输出槽；仅规范写回已安装数量。 */
	public static CompoundTag withCount(CompoundTag source, Upgrade type, int count) {
		if (type == null || count < 0 || count > type.getMax()) throw new IllegalArgumentException("Invalid native upgrade count");
		var counts = read(source);
		if (count == 0) counts.remove(type); else counts.put(type, count);
		var result = source.copy();
		if (counts.isEmpty()) result.remove(SerializationConstants.UPGRADES); else Upgrade.saveMap(counts, result);
		return result;
	}
	private NativeUpgradeCounts() { }
}
