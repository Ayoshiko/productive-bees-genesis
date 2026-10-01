package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 封存 PB 数量的严格白名单；旧数量不因配置上限降低而截断。 */
public final class PbCentrifugeUpgradeCounts {
	public static boolean supported(PbUpgradeType type) {
		return type != null && switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4, TIME, TIME_2, STABILITY, USELESS_BYPRODUCT -> true;
			default -> false;
		};
	}
	public static Map<PbUpgradeType, Integer> read(CompoundTag extra) {
		if (!extra.contains(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS, Tag.TAG_COMPOUND))
			throw new IllegalArgumentException("Missing sealed PB counts");
		var counts = extra.getCompound(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS);
		Map<PbUpgradeType, Integer> result = new EnumMap<>(PbUpgradeType.class);
		for (var key : counts.getAllKeys()) {
			var type = PbUpgradeType.byId(key);
			if (!supported(type) || !counts.contains(key, Tag.TAG_INT) || counts.getInt(key) <= 0)
				throw new IllegalArgumentException("Unsupported sealed PB upgrade: " + key);
			result.put(type, counts.getInt(key));
		}
		return result;
	}
	public static CompoundTag withCount(CompoundTag extra, PbUpgradeType type, int count) {
		if (!supported(type) || count < 0) throw new IllegalArgumentException("Invalid PB upgrade count");
		read(extra);
		var result = extra.copy(); var counts = result.getCompound(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS);
		if (count == 0) counts.remove(type.getId()); else counts.putInt(type.getId(), count);
		return result;
	}
	private PbCentrifugeUpgradeCounts() { }
}
