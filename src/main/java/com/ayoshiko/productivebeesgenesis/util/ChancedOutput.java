package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.nbt.IntArrayTag;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts PB 12's inclusive percentage threshold to the batch sampler's probability. */
public record ChancedOutput(int min, int max, float chance) {
    public static Map<ItemStack, ChancedOutput> convert(Map<ItemStack, IntArrayTag> outputs) {
        Map<ItemStack, ChancedOutput> result = new LinkedHashMap<>();
        outputs.forEach((item, tag) -> {
            int[] values = tag.getAsIntArray();
            if (values.length < 3) throw new IllegalArgumentException("Invalid PB output array");
            result.put(item, new ChancedOutput(values[0], values[1], Math.max(0, Math.min(100, values[2] + 1)) / 100F));
        });
        return result;
    }
}
