package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/** Immutable snapshot of the integer attributes saved by ProductiveBee. */
record GeneSampleProfile(int productivity, int endurance, int temper, int behavior, int weatherTolerance) {
    private static final GeneSampleProfile DEFAULT = new GeneSampleProfile(0, 0, 1, 0, 0);

    static GeneSampleProfile fromBeeData(@Nullable CompoundTag beeData) {
        if (beeData == null) return DEFAULT;
        return new GeneSampleProfile(read(beeData, GeneAttribute.PRODUCTIVITY),
                read(beeData, GeneAttribute.ENDURANCE), read(beeData, GeneAttribute.TEMPER),
                read(beeData, GeneAttribute.BEHAVIOR), read(beeData, GeneAttribute.WEATHER_TOLERANCE));
    }

    int value(GeneAttribute attribute) {
        return switch (attribute) {
            case PRODUCTIVITY -> productivity;
            case ENDURANCE -> endurance;
            case TEMPER -> temper;
            case BEHAVIOR -> behavior;
            case WEATHER_TOLERANCE -> weatherTolerance;
            case TYPE -> throw new IllegalArgumentException("TYPE is represented by the bee registry key");
        };
    }

    private static int read(CompoundTag data, GeneAttribute attribute) {
        String key = "bee_" + attribute.key;
        if (!data.contains(key, Tag.TAG_ANY_NUMERIC)) return attribute.defaultValue;
        int value = data.getInt(key);
        return value >= 0 && value <= attribute.maxValue ? value : attribute.defaultValue;
    }
}
