package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccount;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PbCombinedOutputMinecraftTest {
	@org.junit.jupiter.api.BeforeAll static void configs() { net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs(); }
	@org.junit.jupiter.api.AfterAll static void unload() { net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER); }
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static PbRecipeContext context() {
        var context = mock(PbRecipeContext.class);
        when(context.productivityModifier()).thenReturn(2);
        when(context.primaryOutputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
        when(context.secondaryOutputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
        when(context.tertiaryOutputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
        when(context.inputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
        return context;
    }

    private static CentrifugeRecipe recipe(float chance) {
        var named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("frozen component"));
        var outputs = List.of(named, new ItemStack(Items.EMERALD), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT))
                .stream().map(stack -> new ChancedOutput(Ingredient.of(stack), 1, 1, chance)).toList();
        return new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), outputs, null, 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
    }

    private static Map<String, Integer> counts(PbRecipeCompleter batch) {
        return batch.getPendingOutputs().entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().getItem().toString() + e.getKey().getComponentsPatch(), Map.Entry::getValue));
    }

    @Test void prepareSharesOrdinaryGenerationWithoutTouchingInputOrOutput() {
        var context = context();
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 8));
        var recipe = recipe(1);
        var ordinary = new PbRecipeCompleter(context);
        ordinary.accumulatePbRecipeOutputsBatch(recipe, 0, 2, 8);
        ordinary.prepareOutputUpgrades(false);
        var prepared = new PbRecipeCompleter(context).prepare(recipe, 8, 91, false);
        assertEquals(counts(ordinary), counts(prepared));
        assertEquals(8, ordinary.pendingInputShrink());
        assertEquals(0, prepared.pendingInputShrink());
        assertEquals(8, context.inputSlot(0).getCount());
        assertTrue(context.primaryOutputSlot(0).isEmpty());
        verify(context, never()).productivebeesgenesis$markForSave();
    }

    @Test void repeatedOutputPreparationReusesConversionsButNewItemsAndUpgradeChangesInvalidate() {
        var context = context();
        when(context.level()).thenReturn(mock(net.minecraft.world.level.Level.class));
        when(context.productivebeesgenesis$hasRawOreSmeltingUpgrade()).thenReturn(true);
        var batch = new PbRecipeCompleter(context);
        batch.accumulatePbRecipeOutputsBatch(recipe(1), 0, 2, 8);
        try (var conversions = mockStatic(com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.class)) {
            conversions.when(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingOutputs(
                    context.level(), batch.getPendingOutputs())).thenReturn(false);
            batch.prepareOutputUpgrades(false);
            var first = counts(batch);
            for (int i = 0; i < 256; i++) batch.prepareOutputUpgrades(false);
            assertEquals(first, counts(batch));
            assertEquals(8, batch.pendingInputShrink());
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingOutputs(
                    context.level(), batch.getPendingOutputs()), times(1));
            batch.accumulatePbRecipeOutputsBatch(recipe(1), 0, 2, 1);
            batch.prepareOutputUpgrades(false);
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingOutputs(
                    context.level(), batch.getPendingOutputs()), times(2));
            when(context.suppressesUselessByproducts()).thenReturn(true);
            batch.prepareOutputUpgrades(false);
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingOutputs(
                    context.level(), batch.getPendingOutputs()), times(3));
        }
    }

    @Test void sameSeedKeepsOutputsAndMaximumCoversProbabilityRemainders() {
        var owner = new PbRecipeCompleter(context());
        var recipe = recipe(.37f);
        var maximum = counts(owner.prepare(recipe, 256, 0, true));
        for (int seed = 0; seed < 32; seed++) {
            var actual = counts(owner.prepare(recipe, 256, seed, false));
            assertEquals(actual, counts(owner.prepare(recipe, 256, seed, false)));
            actual.forEach((key, amount) -> assertTrue(amount <= maximum.get(key)));
        }
    }

    @Test void largeBatchesCannotClipItemsWhileCreditingAllResources() {
        var context = context();
        var slot = mock(mekanism.api.inventory.IInventorySlot.class);
        when(slot.getStack()).thenReturn(ItemStack.EMPTY);
        when(slot.getLimit(org.mockito.ArgumentMatchers.any())).thenReturn(Integer.MAX_VALUE);
        when(context.primaryOutputSlot(0)).thenReturn(slot);
        when(context.secondaryOutputSlot(0)).thenReturn(slot);
        when(context.tertiaryOutputSlot(0)).thenReturn(slot);
        var owner = new PbRecipeCompleter(context);
        assertFalse(owner.prepare(recipe(1), Integer.MAX_VALUE, 0, true).fitsPrepared(0, Integer.MAX_VALUE));
        assertTrue(owner.prepare(recipe(1), Integer.MAX_VALUE / 2, 0, true).fitsPrepared(0, Integer.MAX_VALUE / 2));
    }

    @Test void reusedCapacityProbeRechecksOverflowRecipeUpgradesAndLiveCapacity() {
        var context = context();
        var owner = new PbRecipeCompleter(context);
        var recipe = recipe(1);
        assertFalse(owner.fitsMaximum(recipe, 0, Integer.MAX_VALUE));
        assertTrue(owner.fitsMaximum(recipe, 0, 8));
        assertEquals(32, new PbCentrifugeOutputPlan(owner, recipe, 0).maximumInputs(100));
        when(context.productivityModifier()).thenReturn(4);
        assertEquals(16, new PbCentrifugeOutputPlan(owner, recipe, 0).maximumInputs(100));
        assertTrue(owner.fitsMaximum(recipe(0), 0, 100));
        assertFalse(owner.hasPendingOutputs());
        assertEquals(0, owner.pendingInputShrink());
        assertTrue(context.primaryOutputSlot(0).isEmpty());
        verify(context, never()).productivebeesgenesis$markForSave();
    }

    @Test void resourceInputDebitHandlesOversizedStacksWithoutChangingComponentsOrSimulation() {
        for (int requested : List.of(64, 65, 256, 512)) {
            var context = context();
            var slot = new BasicInventorySlot(512, (stack, automation) -> true,
                    (stack, automation) -> true, stack -> true, null, 0, 0) {
                { obeyStackLimit = false; }
            };
            when(context.inputSlot(0)).thenReturn(slot);
            var input = new ItemStack(Items.HONEYCOMB, 512);
            input.set(DataComponents.CUSTOM_NAME, Component.literal("exact resource input"));
            assertEquals(512, slot.getLimit(input));
            assertTrue(slot.insertItem(input, Action.EXECUTE, AutomationType.INTERNAL).isEmpty());
            var access = new MekResourceCentrifuge.Access(context, 0, 0, null, null, null);
            var simulated = access.consume(requested, true);
            assertEquals(requested, simulated.getCount());
            assertTrue(ItemStack.isSameItemSameComponents(input, simulated));
            assertEquals(512, slot.getCount());
            var consumed = access.consume(requested, false);
            assertEquals(requested, consumed.getCount());
            assertTrue(ItemStack.isSameItemSameComponents(input, consumed));
            assertEquals(512 - requested, slot.getCount());
            assertTrue(access.consume(0, false).isEmpty());
            assertTrue(access.consume(-1, false).isEmpty());
            assertEquals(512 - requested, slot.getCount());
            var remainder = access.consume(512, false);
            assertEquals(512 - requested, remainder.getCount());
            assertTrue(slot.isEmpty());
        }
    }

    @Test void fourTypesUsePaidPendingAndRestartDoesNotConsumeOrRegenerate() {
        var context = context();
        var owner = new PbRecipeCompleter(context);
        var prepared = owner.prepare(recipe(1), 8, 71, false);
        assertTrue(prepared.fitsPrepared(0, 8));
        owner.acceptPrepared(prepared);
        assertFalse(owner.flushPendingPbOutputs(0));
        assertTrue(owner.hasCommittedPendingOutputs());
        assertEquals(1, owner.getPendingOutputs().size());
        assertEquals(16, owner.pendingItemCount());
        var saved = owner.saveCommittedPending(REGISTRIES);
        assertTrue(saved.getBoolean("prepared"));
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(saved, REGISTRIES);
        for (var slot : List.of(context.primaryOutputSlot(0), context.secondaryOutputSlot(0), context.tertiaryOutputSlot(0)))
            slot.setStack(ItemStack.EMPTY);
        assertTrue(restored.flushPendingPbOutputs(0));
        assertEquals(16, context.primaryOutputSlot(0).getCount());
        assertNull(restored.saveCommittedPending(REGISTRIES));
        assertTrue(context.inputSlot(0).isEmpty());
    }

    @Test void frozenRecipeKeepsActualComponentsAndDoesNotResolveTagsAgain() {
        var original = recipe(1);
        var snapshot = PbCentrifugeRecipeSnapshot.capture(new RecipeHolder<>(ResourceLocation.parse("test:pb"), original), REGISTRIES);
        var frozen = PbCentrifugeRecipeSnapshot.restore(snapshot, new ItemStack(Items.HONEYCOMB), REGISTRIES).value();
        var owner = new PbRecipeCompleter(context());
        assertEquals(counts(owner.prepare(original, 7, 0, false)), counts(owner.prepare(frozen, 7, 0, false)));
        assertTrue(frozen.getRecipeOutputs().keySet().stream().anyMatch(stack -> stack.has(DataComponents.CUSTOM_NAME)));
    }

    @Test void corruptPaidPendingKeepsOriginalInsteadOfDroppingAnEntry() {
        var owner = new PbRecipeCompleter(context());
        owner.acceptPrepared(owner.prepare(recipe(1), 1, 0, false));
        var saved = owner.saveCommittedPending(REGISTRIES);
        saved.getList("items", 10).getCompound(0).putString("productivebeesgenesis_count", "unknown");
        var restored = new PbRecipeCompleter(context());
        restored.loadCommittedPending(saved, REGISTRIES);
        assertTrue(restored.hasCommittedPendingOutputs());
        assertFalse(restored.flushPendingPbOutputs(0));
        assertEquals(saved, restored.saveCommittedPending(REGISTRIES));
    }

    @Test void compactFrozenRecipeReadsLegacyNumbersAndRejectsDamagedArrays() {
        var original = recipe(.37f);
        var snapshot = PbCentrifugeRecipeSnapshot.capture(new RecipeHolder<>(ResourceLocation.parse("test:pb"), original), REGISTRIES);
        var legacy = snapshot.copy();
        legacy.putInt("version", 1);
        for (var value : legacy.getList("items", 10)) {
            var entry = (CompoundTag) value;
            var amounts = entry.getIntArray("amounts");
            entry.remove("amounts");
            entry.putInt("min", amounts[0]); entry.putInt("max", amounts[1]);
            entry.putFloat("chance", Float.intBitsToFloat(amounts[2]));
        }
        var owner = new PbRecipeCompleter(context());
        for (var saved : List.of(snapshot, legacy)) {
            var restored = PbCentrifugeRecipeSnapshot.restore(saved, new ItemStack(Items.HONEYCOMB), REGISTRIES).value();
            assertEquals(counts(owner.prepare(original, 8, 19, false)), counts(owner.prepare(restored, 8, 19, false)));
        }
        snapshot.getList("items", 10).getCompound(0).putIntArray("amounts", new int[]{1, 1});
        assertThrows(IllegalArgumentException.class,
                () -> PbCentrifugeRecipeSnapshot.restore(snapshot, new ItemStack(Items.HONEYCOMB), REGISTRIES));
    }

    @Test void malformedPendingEnvelopeSurvivesProcessorSaveAndBlocksConsumption() {
        var context = context();
        when(context.processes()).thenReturn(2);
        var processor = new PbRecipeProcessor(context, "test");
        String key = "productivebeesgenesis_pb_committed_pending";
        var duplicate = new net.minecraft.nbt.ListTag();
        var lane = new CompoundTag(); lane.putInt("process", 1);
        duplicate.add(lane); duplicate.add(lane.copy());
        var wrongType = new net.minecraft.nbt.ListTag();
        lane = new CompoundTag(); lane.putString("process", "1"); wrongType.add(lane);
        for (var raw : List.<net.minecraft.nbt.Tag>of(duplicate, wrongType, net.minecraft.nbt.StringTag.valueOf("unreadable"))) {
            var saved = new CompoundTag(); saved.put(key, raw);
            processor.loadAdditional(saved, REGISTRIES);
            assertTrue(processor.hasResourceWork(0));
            assertTrue(processor.hasResourceWork(1));
            assertFalse(processor.drainCommittedPendingOutputs(1));
            var written = new CompoundTag(); processor.saveAdditional(written, REGISTRIES);
            assertEquals(raw, written.get(key));
        }
    }

    @Test void directOutputFailureEntersHostCustodyAndDoesNotRetryOrWriteLocalSlots() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
        int[] calls = {0};
        var tile = new TileEntityMekCentrifuge(net.minecraft.core.Holder.direct(block), net.minecraft.core.BlockPos.ZERO, block.defaultBlockState()) {
            @Override public boolean productivebeesgenesis$isDirectAeOutputEnabled() { return true; }
            @Override public int productivebeesgenesis$pushGeneratedItemToAe(ItemStack template, int amount) {
                calls[0]++;
                throw new IllegalStateException("receiver wrote before failing");
            }
        };
        var owner = new PbRecipeCompleter(tile);
        owner.acceptPrepared(owner.prepare(recipe(1), 1, 0, false));
        assertFalse(owner.flushPendingPbOutputs(0));
        assertEquals(1, calls[0]);
        assertTrue(com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(tile).blocked());
        assertTrue(tile.primaryOutputSlot(0).isEmpty());
        var pending = owner.saveCommittedPending(REGISTRIES);
        assertFalse(owner.flushPendingPbOutputs(0));
        assertEquals(pending, owner.saveCommittedPending(REGISTRIES));
        assertEquals(1, calls[0]);
    }

    @Test void isolatedResourceAccountDoesNotSuspendOrdinaryLanesButKeepsUncertainProduction() {
        var tile = mock(TileEntityMekCentrifuge.class);
        when(tile.processes()).thenReturn(3);
        var host = mock(ResourceHiveHost.class);
        var account = mock(ResourceAccount.class);
        var reference = new ResourceAccount.Reference(java.util.UUID.randomUUID(), 1);
        when(host.reference()).thenReturn(reference);
        when(host.account(tile)).thenReturn(account);
        when(account.canExtract(reference)).thenReturn(false);
        try (var hosts = mockStatic(ResourceHiveHost.class)) {
            hosts.when(() -> ResourceHiveHost.existing(tile)).thenReturn(host);
            assertFalse(MekResourceCentrifuge.suspended(tile, 0));
            assertFalse(MekResourceCentrifuge.suspended(tile, 0));
            var ticket = new ResourceAccount.Ticket(reference, 1);
            var production = new CompoundTag();
            production.putString("host", "resource_centrifuging");
            production.putInt("lane", 1);
            when(account.productionTicket()).thenReturn(ticket);
            when(account.productionContext(ticket)).thenReturn(production);
            assertFalse(MekResourceCentrifuge.suspended(tile, 0));
            assertTrue(MekResourceCentrifuge.suspended(tile, 1));
            assertFalse(MekResourceCentrifuge.suspended(tile, 2));
            production.putInt("lane", 3);
            assertFalse(MekResourceCentrifuge.suspended(tile, 0));
            production.putString("lane", "1");
            assertFalse(MekResourceCentrifuge.suspended(tile, 0));
            production.putString("host", "unknown");
            assertFalse(MekResourceCentrifuge.suspended(tile, 0));
        }
    }

    @Test void returnedOriginalInputLeavesSlotAndSurvivesPendingReloadWithoutConversion() {
        var context = context();
        var input = new ItemStack(Items.HONEYCOMB, 64);
        input.set(DataComponents.CUSTOM_NAME, Component.literal("original returned comb"));
        context.inputSlot(0).setStack(input.copy());
        var owner = new PbRecipeCompleter(context);
        assertTrue(owner.returnInput(0));
        assertTrue(context.inputSlot(0).isEmpty());
        var saved = owner.saveCommittedPending(REGISTRIES);
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertEquals(64, restored.pendingItemCount());
        assertEquals(1, restored.getPendingOutputs().size());
        assertTrue(ItemStack.isSameItemSameComponents(input, restored.getPendingOutputs().keySet().iterator().next()));
        assertTrue(restored.flushPendingPbOutputs(0));
        int returned = context.primaryOutputSlot(0).getCount()
            + context.secondaryOutputSlot(0).getCount() + context.tertiaryOutputSlot(0).getCount();
        assertEquals(64, returned);
        assertTrue(context.inputSlot(0).isEmpty());
        assertFalse(restored.hasCommittedPendingOutputs());
    }
}
