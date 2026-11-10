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
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void alternatingInputsReuseRecentTemplatesAfterTheirLaneMovesOn() {
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
        assertSame(first, cache.get(0, firstInput, recipe, 20, REGISTRIES));
    }

    @Test
    void emptyLanesReleaseTheirFastReferencesButKeepBoundedRecentTemplates() {
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
        assertSame(local, cache.get(0, localInput, recipe, 20, REGISTRIES));

        cache.forget(1);
        assertSame(shared, cache.get(0, sharedInput, recipe, 20, REGISTRIES));
        cache.forget(0);
        assertSame(local, cache.get(1, localInput, recipe, 20, REGISTRIES));
    }

    @Test
    void rotatingWorkingSetReusesItsFrozenWorkAndEncodingAcrossEmptyLanes() {
        int lanes = 18;
        var cache = new PbPaidWorkCache(lanes);
        var recipe = recipe();
        List<ItemStack> inputs = new ArrayList<>();
        List<PbRecipeProcessorStateHelper.PaidWork> work = new ArrayList<>();
        List<PbPausedWorkBuffer.Entry> encoded = new ArrayList<>();
        for (int index = 0; index < 96; index++) {
            ItemStack input = named("rotation-" + index);
            var template = cache.get(index % lanes, input, recipe, 20, REGISTRIES);
            inputs.add(input);
            work.add(template);
            encoded.add(template.reservation(REGISTRIES));
            cache.forget(index % lanes);
        }
        for (int round = 0; round < 3; round++) {
            for (int index = 0; index < inputs.size(); index++) {
                int lane = (index + round) % lanes;
                var template = cache.get(lane, inputs.get(index).copyWithCount(64), recipe, 20, REGISTRIES);
                assertSame(work.get(index), template);
                assertSame(encoded.get(index), template.reservation(REGISTRIES));
                cache.forget(lane);
            }
        }
    }

    @Test
    void entryLimitScalesWithLanesAndNeverExceedsTwoHundredFiftySix() {
        int[] laneCounts = {1, 18, 128};
        int[] capacities = {32, 144, 256};
        var recipe = recipe();
        for (int scenario = 0; scenario < laneCounts.length; scenario++) {
            int lanes = laneCounts[scenario];
            int capacity = capacities[scenario];
            var cache = new PbPaidWorkCache(lanes);
            List<ItemStack> inputs = new ArrayList<>();
            List<PbRecipeProcessorStateHelper.PaidWork> work = new ArrayList<>();
            for (int index = 0; index < capacity; index++) {
                ItemStack input = named("entry-" + index);
                inputs.add(input);
                work.add(cache.get(index % lanes, input, recipe, 20, REGISTRIES));
                cache.forget(index % lanes);
            }
            assertSame(work.getFirst(), cache.get(0, inputs.getFirst(), recipe, 20, REGISTRIES));
            cache.get(0, named("overflow"), recipe, 20, REGISTRIES);
            assertSame(work.getFirst(), cache.get(0, inputs.getFirst(), recipe, 20, REGISTRIES));
            assertSame(work.get(2), cache.get(0, inputs.get(2), recipe, 20, REGISTRIES));
            assertNotSame(work.get(1), cache.get(0, inputs.get(1), recipe, 20, REGISTRIES));
        }
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

    @Test
    void freshlyFrozenWorkMatchesTheOriginalCodecPathForProbabilitiesAndPlainFluid() {
        ItemStack input = named("frozen input");
        Map<ItemStack, ChancedOutput> outputs = new LinkedHashMap<>();
        outputs.put(new ItemStack(Items.DIAMOND), new ChancedOutput(Ingredient.of(Items.DIAMOND), 2, 9, 0.375F));
        outputs.put(new ItemStack(Items.EMERALD), new ChancedOutput(Ingredient.of(Items.EMERALD), 0, 7, -0.0F));
        outputs.put(new ItemStack(Items.IRON_INGOT), new ChancedOutput(Ingredient.of(Items.IRON_INGOT), 3, 3, 1.0F));
        FluidStack fluid = new FluidStack(Fluids.WATER, 875);
        var selected = resolvedRecipe(outputs, fluid);
        CompoundTag original = PbCentrifugeRecipeSnapshot.capture(selected, REGISTRIES);
        var restored = PbCentrifugeRecipeSnapshot.restore(original, input, REGISTRIES);

        var work = PbRecipeProcessorStateHelper.PaidWork.create(input, selected, 37, REGISTRIES);

        assertEquals(original, work.recipeSnapshot);
        assertEquals(37, work.baseTicks);
        assertEquals(selected.id(), work.recipe.id());
        assertFrozenOutputsEqual(restored.value(), work.recipe.value());
        assertTrue(FluidStack.matches(restored.value().getFluidOutputs(), work.recipe.value().getFluidOutputs()));
        assertEquals(work.save(REGISTRIES), PbRecipeProcessorStateHelper.PaidWork.load(work.save(REGISTRIES), REGISTRIES).save(REGISTRIES));
        assertTrue(work.recipe.value().ingredient.test(input));
    }

    @Test
    void componentOutputsKeepCodecNormalizationAndDoNotRetainMutableSourceStacks() {
        ItemStack input = named("source input");
        ItemStack output = new ItemStack(Items.DIAMOND);
        output.set(DataComponents.CUSTOM_NAME, Component.literal("source output"));
        CompoundTag originalData = new CompoundTag();
        originalData.putInt("value", 7);
        output.set(DataComponents.CUSTOM_DATA, CustomData.of(originalData));
        output.set(DataComponents.CREATIVE_SLOT_LOCK, Unit.INSTANCE);
        Map<ItemStack, ChancedOutput> outputs = new LinkedHashMap<>();
        outputs.put(output, new ChancedOutput(Ingredient.of(output), 1, 4, 0.5F));
        FluidStack fluid = new FluidStack(Fluids.WATER, 250);
        fluid.set(DataComponents.CUSTOM_DATA, CustomData.of(originalData));
        fluid.set(DataComponents.CREATIVE_SLOT_LOCK, Unit.INSTANCE);
        var selected = resolvedRecipe(outputs, fluid);
        CompoundTag original = PbCentrifugeRecipeSnapshot.capture(selected, REGISTRIES);
        var restored = PbCentrifugeRecipeSnapshot.restore(original, input, REGISTRIES);

        var work = PbRecipeProcessorStateHelper.PaidWork.create(input, selected, 20, REGISTRIES);
        CompoundTag saved = work.save(REGISTRIES);
        ItemStack frozenOutput = work.recipe.value().getRecipeOutputs().keySet().iterator().next();

        assertFrozenOutputsEqual(restored.value(), work.recipe.value());
        assertTrue(FluidStack.matches(restored.value().getFluidOutputs(), work.recipe.value().getFluidOutputs()));
        assertFalse(frozenOutput.has(DataComponents.CREATIVE_SLOT_LOCK));
        assertFalse(work.recipe.value().getFluidOutputs().has(DataComponents.CREATIVE_SLOT_LOCK));
        assertEquals(7, frozenOutput.get(DataComponents.CUSTOM_DATA).copyTag().getInt("value"));
        input.set(DataComponents.CUSTOM_NAME, Component.literal("changed input"));
        output.set(DataComponents.CUSTOM_NAME, Component.literal("changed output"));
        output.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        output.setCount(64);
        fluid.setAmount(1);
        fluid.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        FluidStack returnedFluid = work.recipe.value().getFluidOutputs();
        returnedFluid.setAmount(99);
        returnedFluid.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        assertEquals(saved, work.save(REGISTRIES));
        assertFrozenOutputsEqual(restored.value(), work.recipe.value());
        assertTrue(FluidStack.matches(restored.value().getFluidOutputs(), work.recipe.value().getFluidOutputs()));
        assertTrue(work.matches(named("source input")));
        assertFalse(work.matches(input));
    }

    @Test
    void freshlyFrozenWorkRetainsEmptyFluidAndRejectsTheSameInvalidProbability() {
        var selected = recipe();
        ItemStack input = new ItemStack(Items.HONEYCOMB);
        var work = PbRecipeProcessorStateHelper.PaidWork.create(input, selected, 20, REGISTRIES);
        assertTrue(work.recipe.value().getFluidOutputs().isEmpty());
        assertFalse(work.recipeSnapshot.contains("fluid"));

        Map<ItemStack, ChancedOutput> invalid = new LinkedHashMap<>();
        invalid.put(new ItemStack(Items.DIAMOND), new ChancedOutput(Ingredient.of(Items.DIAMOND), 1, 1, Float.NaN));
        var malformed = resolvedRecipe(invalid, FluidStack.EMPTY);
        CompoundTag encoded = PbCentrifugeRecipeSnapshot.capture(malformed, REGISTRIES);

        assertThrows(IllegalArgumentException.class,
                () -> PbCentrifugeRecipeSnapshot.restore(encoded, input, REGISTRIES));
        assertThrows(IllegalArgumentException.class,
                () -> PbRecipeProcessorStateHelper.PaidWork.create(input, malformed, 20, REGISTRIES));
    }

    @Test
    void persistedWorkStillStrictlyRejectsMalformedOutputCountsAndUnknownFields() {
        var work = PbRecipeProcessorStateHelper.PaidWork.create(
                new ItemStack(Items.HONEYCOMB), recipe(), 20, REGISTRIES);
        CompoundTag invalidCount = work.save(REGISTRIES);
        CompoundTag item = invalidCount.getCompound("recipe").getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        item.getCompound("stack").putInt("count", 2);
        CompoundTag unknownField = work.save(REGISTRIES);
        unknownField.getCompound("recipe").putInt("unexpected", 1);

        assertThrows(IllegalArgumentException.class,
                () -> PbRecipeProcessorStateHelper.PaidWork.load(invalidCount, REGISTRIES));
        assertThrows(IllegalArgumentException.class,
                () -> PbRecipeProcessorStateHelper.PaidWork.load(unknownField, REGISTRIES));
    }

    private static void assertFrozenOutputsEqual(CentrifugeRecipe expected, CentrifugeRecipe actual) {
        var expectedEntries = new ArrayList<>(expected.getRecipeOutputs().entrySet());
        var actualEntries = new ArrayList<>(actual.getRecipeOutputs().entrySet());
        assertEquals(expectedEntries.size(), actualEntries.size());
        for (int index = 0; index < expectedEntries.size(); index++) {
            var left = expectedEntries.get(index);
            var right = actualEntries.get(index);
            assertTrue(ItemStack.isSameItemSameComponents(left.getKey(), right.getKey()));
            assertEquals(left.getKey().getCount(), right.getKey().getCount());
            assertEquals(left.getValue().min(), right.getValue().min());
            assertEquals(left.getValue().max(), right.getValue().max());
            assertEquals(Float.floatToRawIntBits(left.getValue().chance()),
                    Float.floatToRawIntBits(right.getValue().chance()));
        }
    }

    private static RecipeHolder<CentrifugeRecipe> resolvedRecipe(
            Map<ItemStack, ChancedOutput> outputs, FluidStack fluid) {
        var value = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(), null, 20) {
            @Override
            public Map<ItemStack, ChancedOutput> getRecipeOutputs() {
                return outputs;
            }

            @Override
            public FluidStack getFluidOutputs() {
                return fluid;
            }
        };
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(
                "productivebeesgenesis", "resolved_cache"), value);
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
