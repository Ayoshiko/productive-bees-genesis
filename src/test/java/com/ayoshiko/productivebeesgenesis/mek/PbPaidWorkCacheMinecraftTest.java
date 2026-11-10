package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

@Tag("minecraft")
class PbPaidWorkCacheMinecraftTest {
    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test
    void equivalentInputsShareOneFrozenTemplateAndEncodingAcrossLanes() {
        var cache = new PbPaidWorkCache(3);
        var recipe = recipe();
        ItemStack input = named("shared");
        var first = cache.get(0, input, recipe, 20, REGISTRIES);
        var encoded = first.reservation(REGISTRIES);

        var second = cache.get(1, input.copyWithCount(64), recipe, 20, REGISTRIES);
        var third = cache.get(2, input.copy(), recipe, 20, REGISTRIES);

        assertSame(first, second);
        assertSame(first, third);
        assertSame(encoded, second.reservation(REGISTRIES));
        assertEquals(1, first.input.getCount());
        input.set(DataComponents.CUSTOM_NAME, Component.literal("changed after lookup"));
        assertTrue(first.matches(named("shared")));
        assertFalse(first.matches(input));
    }

    @Test
    void fullComponentsRecipeHolderIdentityAndBaseTicksRemainPartOfTheKey() {
        var cache = new PbPaidWorkCache(4);
        var recipe = recipe();
        ItemStack input = named("original");
        var original = cache.get(0, input, recipe, 20, REGISTRIES);
        var sameIdAndValue = new RecipeHolder<>(recipe.id(), recipe.value());
        assertEquals(recipe, sameIdAndValue);
        assertNotSame(recipe, sameIdAndValue);

        assertNotSame(original, cache.get(1, input, sameIdAndValue, 20, REGISTRIES));
        assertNotSame(original, cache.get(2, input, recipe, 21, REGISTRIES));
        ItemStack changedComponent = input.copy();
        CompoundTag component = new CompoundTag();
        component.putInt("variant", 1);
        changedComponent.set(DataComponents.CUSTOM_DATA, CustomData.of(component));
        assertNotSame(original, cache.get(3, changedComponent, recipe, 20, REGISTRIES));
        assertSame(original, cache.get(0, input.copyWithCount(2), recipe, 20, REGISTRIES));
    }

    @Test
    void alternatingInputsReuseRecentTemplatesButOlderUnreferencedInputsExpire() {
        var cache = new PbPaidWorkCache(1);
        var recipe = recipe();
        ItemStack firstInput = named("first");
        ItemStack secondInput = named("second");
        var first = cache.get(0, firstInput, recipe, 20, REGISTRIES);
        var second = cache.get(0, secondInput, recipe, 20, REGISTRIES);

        assertSame(first, cache.get(0, firstInput, recipe, 20, REGISTRIES));
        assertSame(second, cache.get(0, secondInput, recipe, 20, REGISTRIES));
        cache.get(0, named("third"), recipe, 20, REGISTRIES);
        assertSame(second, cache.get(0, secondInput, recipe, 20, REGISTRIES));
        assertNotSame(first, cache.get(0, firstInput, recipe, 20, REGISTRIES));
    }

    @Test
    void emptyLaneReleasesBothRootsWhileOtherLanesKeepTheirSharedTemplate() {
        var cache = new PbPaidWorkCache(2);
        var recipe = recipe();
        ItemStack sharedInput = named("shared");
        ItemStack localInput = named("local");
        var shared = cache.get(0, sharedInput, recipe, 20, REGISTRIES);
        var local = cache.get(0, localInput, recipe, 20, REGISTRIES);
        assertSame(shared, cache.get(1, sharedInput, recipe, 20, REGISTRIES));

        cache.forget(0);
        cache.forget(0);
        assertSame(shared, cache.get(1, sharedInput, recipe, 20, REGISTRIES));
        var recreatedLocal = cache.get(0, localInput, recipe, 20, REGISTRIES);
        assertNotSame(local, recreatedLocal);

        cache.forget(1);
        assertNotSame(shared, cache.get(0, sharedInput, recipe, 20, REGISTRIES));
        cache.forget(0);
        assertNotSame(recreatedLocal, cache.get(1, localInput, recipe, 20, REGISTRIES));
    }

    @Test
    void providerReplacementAndExplicitClearInvalidateAllLanesAndRecentTemplates() {
        var cache = new PbPaidWorkCache(2);
        var recipe = recipe();
        ItemStack firstInput = named("first");
        ItemStack secondInput = named("second");
        var first = cache.get(0, firstInput, recipe, 20, REGISTRIES);
        cache.get(0, secondInput, recipe, 20, REGISTRIES);
        assertSame(first, cache.get(1, firstInput, recipe, 20, REGISTRIES));
        HolderLookup.Provider replacement = mock(HolderLookup.Provider.class, delegatesTo(REGISTRIES));

        var afterProviderChange = cache.get(1, firstInput, recipe, 20, replacement);
        assertNotSame(first, afterProviderChange);
        assertSame(afterProviderChange, cache.get(0, firstInput, recipe, 20, replacement));
        var secondAfterChange = cache.get(0, secondInput, recipe, 20, replacement);
        cache.clear();

        assertNotSame(afterProviderChange, cache.get(1, firstInput, recipe, 20, replacement));
        assertNotSame(secondAfterChange, cache.get(0, secondInput, recipe, 20, replacement));
        assertNotSame(first, cache.get(0, firstInput, recipe, 20, REGISTRIES));
    }

    @Test
    void sharedTemplatesCountOnceAgainstTheBudgetAndEvictLeastRecentlyUsedEntries() {
        var cache = new PbPaidWorkCache(4);
        var recipe = recipe();
        int payloadSize = (int) (PbPausedWorkBuffer.MAX_WORK_BYTES / 3);
        ItemStack firstInput = withPayload("first", payloadSize);
        ItemStack secondInput = withPayload("second", payloadSize);
        ItemStack thirdInput = withPayload("third", payloadSize);
        var first = cache.get(0, firstInput, recipe, 20, REGISTRIES);
        assertSame(first, cache.get(1, firstInput, recipe, 20, REGISTRIES));
        var second = cache.get(2, secondInput, recipe, 20, REGISTRIES);
        long pairBytes = first.reservation(REGISTRIES).bytes() + second.reservation(REGISTRIES).bytes();
        assertTrue(pairBytes <= PbPausedWorkBuffer.MAX_WORK_BYTES);
        assertSame(second, cache.get(2, secondInput, recipe, 20, REGISTRIES));
        assertSame(first, cache.get(0, firstInput, recipe, 20, REGISTRIES));

        var third = cache.get(3, thirdInput, recipe, 20, REGISTRIES);
        assertTrue(pairBytes + third.reservation(REGISTRIES).bytes() > PbPausedWorkBuffer.MAX_WORK_BYTES);
        assertSame(first, cache.get(1, firstInput, recipe, 20, REGISTRIES));
        assertSame(third, cache.get(3, thirdInput, recipe, 20, REGISTRIES));
        assertNotSame(second, cache.get(2, secondInput, recipe, 20, REGISTRIES));
    }

    @Test
    void oversizedWorkIsReturnedWithoutRetentionOrEvictingOtherLanes() {
        var cache = new PbPaidWorkCache(2);
        var recipe = recipe();
        ItemStack smallInput = named("small");
        var small = cache.get(1, smallInput, recipe, 20, REGISTRIES);
        ItemStack input = withPayload("oversized", (int) PbPausedWorkBuffer.MAX_WORK_BYTES);

        var first = cache.get(0, input, recipe, 20, REGISTRIES);
        assertTrue(first.reservation(REGISTRIES).bytes() > PbPausedWorkBuffer.MAX_WORK_BYTES);
        assertNotSame(first, cache.get(0, input, recipe, 20, REGISTRIES));
        assertSame(small, cache.get(1, smallInput, recipe, 20, REGISTRIES));
        assertFalse(new PbPausedWorkBuffer(1).reserve(0, first, REGISTRIES));
    }

    @Test
    void cacheEvictionAndClearPreserveEachIndependentlyPaidPausedCycle() {
        var cache = new PbPaidWorkCache(2);
        var recipe = recipe();
        int payloadSize = (int) (PbPausedWorkBuffer.MAX_WORK_BYTES / 3);
        ItemStack input = withPayload("paid", payloadSize);
        var work = cache.get(0, input, recipe, 20, REGISTRIES);
        assertSame(work, cache.get(1, input, recipe, 20, REGISTRIES));
        var buffer = new PbPausedWorkBuffer(2);
        assertTrue(buffer.reserve(0, work, REGISTRIES));
        assertTrue(buffer.reserve(1, work, REGISTRIES));
        assertTrue(buffer.park(0, 7, 20));
        assertTrue(buffer.park(1, 11, 20));
        ListTag original = buffer.save();

        cache.get(0, withPayload("replacement one", payloadSize), recipe, 20, REGISTRIES);
        cache.get(1, withPayload("replacement two", payloadSize), recipe, 20, REGISTRIES);
        cache.clear();
        assertEquals(original, buffer.save());

        var first = buffer.take(0, input);
        assertNotNull(first);
        assertSame(work, first.work);
        assertEquals(7, first.progress);
        assertNull(buffer.take(0, input));
        var second = buffer.take(1, input);
        assertNotNull(second);
        assertSame(work, second.work);
        assertNotSame(first, second);
        assertEquals(11, second.progress);
        assertTrue(buffer.save().isEmpty());
        buffer.release(0);
        assertEquals(11, second.progress);
    }

    private static ItemStack named(String name) {
        ItemStack input = new ItemStack(Items.HONEYCOMB);
        input.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return input;
    }

    private static ItemStack withPayload(String name, int size) {
        ItemStack input = named(name);
        CompoundTag component = new CompoundTag();
        component.putByteArray("payload", new byte[size]);
        input.set(DataComponents.CUSTOM_DATA, CustomData.of(component));
        return input;
    }

    private static RecipeHolder<CentrifugeRecipe> recipe() {
        var output = new ChancedOutput(Ingredient.of(Items.DIAMOND), 1, 1, 1);
        CentrifugeRecipe value = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
            @Override
            public FluidStack getFluidOutputs() {
                return FluidStack.EMPTY;
            }
        };
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "paid_cache"), value);
    }
}
