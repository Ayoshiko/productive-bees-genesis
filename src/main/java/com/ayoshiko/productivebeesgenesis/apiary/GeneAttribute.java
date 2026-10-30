package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.util.BeeAttribute;
import cy.jdkdigital.productivebees.util.BeeAttributes;

/** Sampling domains backed by Productive Bees 1.20's registered integer attributes. */
enum GeneAttribute {
    TYPE(null, "type", 0, 0),
    PRODUCTIVITY(BeeAttributes.PRODUCTIVITY, "productivity", 0, 3),
    ENDURANCE(BeeAttributes.ENDURANCE, "endurance", 0, 3),
    TEMPER(BeeAttributes.TEMPER, "temper", 1, 3),
    BEHAVIOR(BeeAttributes.BEHAVIOR, "behavior", 0, 2),
    WEATHER_TOLERANCE(BeeAttributes.WEATHER_TOLERANCE, "weather_tolerance", 0, 2);

    final BeeAttribute<Integer> beeAttribute;
    final String key;
    final int defaultValue;
    final int maxValue;

    GeneAttribute(BeeAttribute<Integer> beeAttribute, String key, int defaultValue, int maxValue) {
        this.beeAttribute = beeAttribute;
        this.key = key;
        this.defaultValue = defaultValue;
        this.maxValue = maxValue;
    }

    static GeneAttribute fromBeeAttribute(BeeAttribute<Integer> value) {
        if (value == null) return null;
        for (GeneAttribute attribute : values()) {
            if (attribute.beeAttribute == value) return attribute;
        }
        return null;
    }
}
