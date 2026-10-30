package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class ForgeBeeTags {
    private ForgeBeeTags() {}
    public static final TagKey<Item> WAXES = TagKey.create(Registries.ITEM, new ResourceLocation("forge", "wax"));
    public static final TagKey<Item> STORAGE_BLOCK_HONEYCOMBS = TagKey.create(Registries.ITEM, new ResourceLocation("forge", "storage_blocks/honeycombs"));
}
