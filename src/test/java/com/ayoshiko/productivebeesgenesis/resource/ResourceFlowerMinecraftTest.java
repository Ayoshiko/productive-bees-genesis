package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceFlowerMinecraftTest {
    private static CompoundTag data(String key, String value) {
        var data = new CompoundTag();
        data.putString("flowerType", "blocks");
        data.putString(key, value);
        return data;
    }

    @Test void concreteBlockAndItemMustExistAndBeNonempty() {
        assertEquals("", PbResourceCatalog.flowerProblem(data("flowerBlock", "minecraft:copper_block")));
        assertEquals("", PbResourceCatalog.flowerProblem(data("flowerItem", "minecraft:diamond")));
        for (var key : List.of("flowerBlock", "flowerItem")) {
            for (var id : List.of("minecraft:air", "example:missing", "bad id", "")) {
                assertEquals("invalid_flower", PbResourceCatalog.flowerProblem(data(key, id)));
            }
        }
    }

    @Test void dynamicInverseAndSideEffectDefinitionsStayRejected() {
        var data = data("flowerBlock", "minecraft:copper_block");
        data.putString("flowerType", "entity_types");
        assertEquals("unsupported_bee", PbResourceCatalog.flowerProblem(data));
        data.putString("flowerType", "blocks");
        data.putBoolean("inverseFlower", true);
        assertEquals("unsupported_bee", PbResourceCatalog.flowerProblem(data));
        data.remove("inverseFlower");
        data.putString("postPollination", "");
        assertEquals("unsupported_bee", PbResourceCatalog.flowerProblem(data));
        assertEquals("", PbResourceCatalog.flowerProblem(data("flowerFluid", "minecraft:water")));
        assertEquals("invalid_flower", PbResourceCatalog.flowerProblem(data("flowerFluid", "minecraft:empty")));
        assertEquals("unsupported_bee", PbResourceCatalog.flowerProblem(null));
    }

    @Test void blockOnlyAndItemOnlyTagsReflectCurrentMembership() {
        var originalBlocks = BuiltInRegistries.BLOCK.getTags().collect(Collectors.toMap(
                entry -> entry.getFirst(), entry -> entry.getSecond().stream().toList()));
        var originalItems = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
                entry -> entry.getFirst(), entry -> entry.getSecond().stream().toList()));
        var id = ResourceLocation.parse("example:resource_flower_test");
        var blockKey = TagKey.create(BuiltInRegistries.BLOCK.key(), id);
        var itemKey = TagKey.create(BuiltInRegistries.ITEM.key(), id);
        var data = data("flowerTag", id.toString());
        try {
            var blockTags = new HashMap<>(originalBlocks);
            var itemTags = new HashMap<>(originalItems);
            blockTags.put(blockKey, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.COPPER_BLOCK)));
            itemTags.put(itemKey, List.of());
            BuiltInRegistries.BLOCK.bindTags(blockTags);
            BuiltInRegistries.ITEM.bindTags(itemTags);
            assertEquals("", PbResourceCatalog.flowerProblem(data));
            blockTags.put(blockKey, List.of());
            itemTags.put(itemKey, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIAMOND)));
            BuiltInRegistries.BLOCK.bindTags(blockTags);
            BuiltInRegistries.ITEM.bindTags(itemTags);
            assertEquals("", PbResourceCatalog.flowerProblem(data));
            itemTags.put(itemKey, List.of());
            BuiltInRegistries.ITEM.bindTags(itemTags);
            assertEquals("invalid_flower", PbResourceCatalog.flowerProblem(data));
            blockTags.put(blockKey, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.AIR)));
            itemTags.put(itemKey, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.AIR)));
            BuiltInRegistries.BLOCK.bindTags(blockTags);
            BuiltInRegistries.ITEM.bindTags(itemTags);
            assertEquals("invalid_flower", PbResourceCatalog.flowerProblem(data));
        } finally {
            BuiltInRegistries.BLOCK.bindTags(originalBlocks);
            BuiltInRegistries.ITEM.bindTags(originalItems);
        }
    }

    @Test void malformedOrMissingTagCannotFallBackToBlock() {
        for (var id : List.of("", "bad tag", "#minecraft:flowers", "example:missing_flowers")) {
            var data = data("flowerTag", id);
            data.putString("flowerBlock", "minecraft:copper_block");
            assertEquals("invalid_flower", PbResourceCatalog.flowerProblem(data));
        }
    }
}
