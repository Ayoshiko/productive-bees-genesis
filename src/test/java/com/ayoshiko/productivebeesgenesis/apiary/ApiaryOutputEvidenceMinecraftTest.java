package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ApiaryOutputEvidenceMinecraftTest {
    @Test void exactEvidenceKeepsComponentsAndCountsWithoutSharingProducerStacks() throws Exception {
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var stack = new ItemStack(Items.HONEYCOMB, 100000);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("完整组件审计"));
        var cache = new ApiaryOutputEvidenceCache();
        String first = cache.encode(List.of(stack), provider);
        assertSame(first, cache.encode(List.of(stack.copy()), provider));
        var item = TagParser.parseTag("{items:" + first + "}").getList("items", 10).getCompound(0);
        assertEquals(100000, item.getInt("generated_count"));
        assertTrue(ItemStack.isSameItemSameComponents(stack, ItemStack.parse(provider, item).orElseThrow()));
        stack.setCount(7);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("修改后组件"));
        String changed = cache.encode(List.of(stack), provider);
        assertNotEquals(first, changed);
        assertEquals(100000, TagParser.parseTag("{items:" + first + "}").getList("items", 10).getCompound(0).getInt("generated_count"));
        assertEquals(7, TagParser.parseTag("{items:" + changed + "}").getList("items", 10).getCompound(0).getInt("generated_count"));
    }

    @Test void varyingAmountsAndNestedFieldsKeepCanonicalEvidenceAndIndependentOrder() throws Exception {
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var first = new ItemStack(Items.HONEYCOMB);
        CompoundTag nested = new CompoundTag();
        nested.putInt("generated_count", 77);
        nested.putString("quoted key", "双引号\"与单引号'");
        first.set(DataComponents.CUSTOM_DATA, CustomData.of(nested));
        var second = new ItemStack(Items.PAPER, 3);
        var cache = new ApiaryOutputEvidenceCache();
        for (int count : new int[]{1, 7, 65536, Integer.MAX_VALUE}) {
            first.setCount(count);
            ListTag expected = new ListTag();
            for (ItemStack stack : List.of(first, second)) {
                CompoundTag saved = (CompoundTag) stack.copyWithCount(1).save(provider);
                saved.putInt("generated_count", stack.getCount());
                expected.add(saved);
            }
            String encoded = cache.encode(List.of(first, second), provider);
            assertEquals(expected.toString(), encoded);
            var decoded = TagParser.parseTag("{items:" + encoded + "}").getList("items", 10);
            assertEquals(count, decoded.getCompound(0).getInt("generated_count"));
            assertEquals(77, ItemStack.parse(provider, decoded.getCompound(0)).orElseThrow()
                    .get(DataComponents.CUSTOM_DATA).copyTag().getInt("generated_count"));
            assertNotEquals(encoded, cache.encode(List.of(second, first), provider));
        }
    }

    @Test void registryAndRecipeChangesDiscardOnlyEncodingAndKeepOriginalEvidence() {
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var stack = new ItemStack(Items.DIAMOND, 3);
        var cache = new ApiaryOutputEvidenceCache();
        String first = cache.encode(List.of(stack), provider);
        ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
        String reloaded = cache.encode(List.of(stack), provider);
        assertEquals(first, reloaded);
        assertNotSame(first, reloaded);
        var replacement = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        String replaced = cache.encode(List.of(stack), replacement);
        assertEquals(first, replaced);
        assertNotSame(reloaded, replaced);
        cache.clear();
        String unloaded = cache.encode(List.of(stack), replacement);
        assertEquals(first, unloaded);
        assertNotSame(replaced, unloaded);
    }

    @Test void oversizedEvidenceRemainsExactWithoutRetainingAnUnboundedCacheEntry() throws Exception {
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var stack = new ItemStack(Items.DIAMOND, 5);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("长".repeat(9000)));
        var cache = new ApiaryOutputEvidenceCache();
        String first = cache.encode(List.of(stack), provider);
        String second = cache.encode(List.of(stack), provider);
        assertEquals(first, second);
        assertNotSame(first, second);
        var saved = TagParser.parseTag("{items:" + first + "}").getList("items", 10).getCompound(0);
        assertTrue(ItemStack.isSameItemSameComponents(stack, ItemStack.parse(provider, saved).orElseThrow()));
        assertEquals(5, saved.getInt("generated_count"));
    }
}
