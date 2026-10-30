package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;

/** 数字控件显示服务端实际接受的边界；无效原文仍保留在草稿中。 */
record DesignerNumberRange(BigDecimal min, BigDecimal max, boolean integer, boolean exclusiveMin) {
    static DesignerNumberRange of(double min, double max, boolean integer) {
        return new DesignerNumberRange(BigDecimal.valueOf(min), BigDecimal.valueOf(max), integer, false);
    }

    static DesignerNumberRange forMachine(BeeDesignerMachineRecipes.Entry entry) {
        return new DesignerNumberRange(BigDecimal.ONE, BigDecimal.valueOf(entry.maximumAmount()), true, false);
    }

    static DesignerNumberRange forField(String label, String key) {
        if (key.equals("size") || key.equals("pollinatedSize") || key.equals("speed")) {
            return new DesignerNumberRange(BigDecimal.ZERO, BigDecimal.valueOf(100), false, true);
        }
        if (key.equals("attack")) {
            return of(0, Integer.MAX_VALUE, false);
        }
		if (key.equals("eu")) {
			return of(0, Integer.MAX_VALUE, true);
		}
        if (key.equals("breedingItemCount") || label.equals("egg_count")) {
            return of(1, 64, true);
        }
        if (key.toLowerCase(java.util.Locale.ROOT).contains("chance") || key.equals("probability")) {
            return of(0, 1, false);
        }
        if (key.equals("min") || key.equals("max") || key.equals("processingTime") || key.equals("processing_time")
                || label.equals("processing_time") || label.equals("fluid_amount")) {
            return of(1, Integer.MAX_VALUE / 4, true);
        }
        if (key.equals("priority")) {
            return of(Integer.MIN_VALUE, Integer.MAX_VALUE, true);
        }
        if (key.equals("x") || key.equals("y")) {
            return of(0, Integer.MAX_VALUE - 1, true);
        }
        return of(1, Integer.MAX_VALUE, true);
    }

    boolean valid(String text) {
        try {
            BigDecimal value = new BigDecimal(text);
            int lower = value.compareTo(min);
            if ((exclusiveMin ? lower <= 0 : lower < 0) || value.compareTo(max) > 0) {
                return false;
            }
            return !integer || value.stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException | ArithmeticException failure) {
            return false;
        }
    }

    BigDecimal clamp(BigDecimal value) {
        BigDecimal lower = exclusiveMin ? min.add(new BigDecimal("0.1")) : min;
        return value.max(lower).min(max);
    }

    Component description() {
        return Component.translatable(integer ? "designer.range_integer" : "designer.range_decimal",
                (exclusiveMin ? "> " : "") + min.stripTrailingZeros().toPlainString(), max.stripTrailingZeros().toPlainString());
    }
}
