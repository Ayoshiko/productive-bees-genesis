package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneValue;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 单只蜜蜂可采样的属性基因快照。
 * <p>
 * 快照只保存 PB 枚举值，不持有蜜蜂 NBT，可安全缓存在 {@link BeeSlot} 中。
 */
record GeneSampleProfile(
		GeneValue productivity,
		GeneValue endurance,
		GeneValue temper,
		GeneValue behavior,
		GeneValue weatherTolerance) {

	private static final String ATTACHMENTS_KEY = "neoforge:attachments";
	private static final String ATTRIBUTES_HANDLER_KEY = "productivebees:attributes_handler";

	private static final GeneSampleProfile DEFAULT = new GeneSampleProfile(
			GeneValue.PRODUCTIVITY_NORMAL,
			GeneValue.ENDURANCE_WEAK,
			GeneValue.TEMPER_NORMAL,
			GeneValue.BEHAVIOR_DIURNAL,
			GeneValue.WEATHER_TOLERANCE_NONE);

	/** 从蜜蜂 NBT 读取五类属性；缺失或损坏的值使用项目既有默认值。 */
	static GeneSampleProfile fromBeeData(@Nullable CompoundTag beeData) {
		if (beeData == null) return DEFAULT;
		CompoundTag attachments = beeData.getCompound(ATTACHMENTS_KEY);
		if (!attachments.contains(ATTRIBUTES_HANDLER_KEY)) return DEFAULT;
		CompoundTag attributes = attachments.getCompound(ATTRIBUTES_HANDLER_KEY);
		return new GeneSampleProfile(
				readValue(attributes, "bee_productivity", GeneAttribute.PRODUCTIVITY,
						GeneValue.PRODUCTIVITY_NORMAL),
				readValue(attributes, "bee_endurance", GeneAttribute.ENDURANCE,
						GeneValue.ENDURANCE_WEAK),
				readValue(attributes, "bee_temper", GeneAttribute.TEMPER,
						GeneValue.TEMPER_NORMAL),
				readValue(attributes, "bee_behavior", GeneAttribute.BEHAVIOR,
						GeneValue.BEHAVIOR_DIURNAL),
				readValue(attributes, "bee_weather_tolerance", GeneAttribute.WEATHER_TOLERANCE,
						GeneValue.WEATHER_TOLERANCE_NONE));
	}

	/** 返回指定属性的 PB 枚举值；TYPE 由当前蜂种分组键提供。 */
	GeneValue value(GeneAttribute attribute) {
		return switch (attribute) {
			case PRODUCTIVITY -> productivity;
			case ENDURANCE -> endurance;
			case TEMPER -> temper;
			case BEHAVIOR -> behavior;
			case WEATHER_TOLERANCE -> weatherTolerance;
			case TYPE -> throw new IllegalArgumentException("TYPE gene has no GeneValue");
		};
	}

	private static GeneValue readValue(CompoundTag attributes, String key, GeneAttribute attribute,
			GeneValue fallback) {
		GeneValue value = GeneValue.byName(attributes.getString(key));
		return value == null || !belongsTo(value, attribute) ? fallback : value;
	}

	private static boolean belongsTo(GeneValue value, GeneAttribute attribute) {
		String name = value.getSerializedName();
		return switch (attribute) {
			case PRODUCTIVITY -> name.startsWith("productivity.");
			case ENDURANCE -> name.startsWith("endurance.");
			case TEMPER -> name.startsWith("temper.");
			case BEHAVIOR -> name.startsWith("behavior.");
			case WEATHER_TOLERANCE -> name.startsWith("weather_tolerance.");
			case TYPE -> false;
		};
	}
}
