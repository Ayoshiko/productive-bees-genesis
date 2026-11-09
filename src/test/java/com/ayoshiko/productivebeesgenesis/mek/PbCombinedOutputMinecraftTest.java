package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.PbVirtualTickPlan;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetSection;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.SharedPbRecipeCache;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccount;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
        return recipe(new ItemStack(Items.HONEYCOMB), chance);
    }

    private static CentrifugeRecipe recipe(ItemStack input, float chance) {
        var named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("frozen component"));
        var outputs = List.of(named, new ItemStack(Items.EMERALD), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT))
                .stream().map(stack -> new ChancedOutput(Ingredient.of(stack), 1, 1, chance)).toList();
        return new CentrifugeRecipe(Ingredient.of(input), outputs, SizedFluidIngredient.of(Fluids.WATER, 1), 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
    }

    private static Map<String, Long> counts(PbRecipeCompleter batch) {
        return batch.getPendingOutputs().entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().getItem().toString() + e.getKey().getComponentsPatch(), Map.Entry::getValue));
    }

    @Test void directAeKeepsReceiptBasedCommitWithEmptyOrFullLocalOutputs() {
        for (boolean full : List.of(false, true)) {
            var context = mock(com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase.class);
            when(context.productivityModifier()).thenReturn(2);
            when(context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
            when(context.primaryOutputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
            when(context.secondaryOutputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
            when(context.tertiaryOutputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
            when(context.inputSlot(0)).thenReturn(BasicInventorySlot.at(null, 0, 0));
            context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 8));
            if (full) {
                for (var slot : List.of(context.primaryOutputSlot(0), context.secondaryOutputSlot(0), context.tertiaryOutputSlot(0))) {
                    slot.setStack(new ItemStack(Items.DIAMOND, 64));
                }
            }
            when(context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt())).thenAnswer(call -> call.getArgument(1));
            var output = new ChancedOutput(Ingredient.of(Items.IRON_INGOT), 1, 1, 1);
            var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
                @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
            };
            var owner = new PbRecipeCompleter(context);
            owner.accumulatePbRecipeOutputsBatch(recipe, 0, 2, 6);
            assertTrue(owner.flushPendingPbOutputs(0));
            assertEquals(2, context.inputSlot(0).getCount());
            assertFalse(owner.hasPendingOutputs());
            verify(context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(12));
            verify(context, never()).productivebeesgenesis$pushGeneratedItemToNeighbors(any(), anyInt());
            assertEquals(full ? 64 : 0, context.primaryOutputSlot(0).getCount());
        }
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
            conversions.when(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(
                    context.level(), batch.getPendingOutputs())).thenReturn(false);
            batch.prepareOutputUpgrades(false);
            var first = counts(batch);
            for (int i = 0; i < 256; i++) batch.prepareOutputUpgrades(false);
            assertEquals(first, counts(batch));
            assertEquals(8, batch.pendingInputShrink());
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(
                    context.level(), batch.getPendingOutputs()), times(1));
            batch.accumulatePbRecipeOutputsBatch(recipe(1), 0, 2, 1);
            batch.prepareOutputUpgrades(false);
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(
                    context.level(), batch.getPendingOutputs()), times(2));
            when(context.suppressesUselessByproducts()).thenReturn(true);
            batch.prepareOutputUpgrades(false);
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(
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

    @Test void legacyPlacedItemBindsOnlyItsRestoredInputComponent() {
        var block = (MekCentrifugeBlock<?, ?>) BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
        var position = net.minecraft.core.BlockPos.ZERO;
        var restored = new TileEntityMekCentrifuge(net.minecraft.core.Holder.direct(block), position, block.defaultBlockState()) {
            @Override public void onAdded() {
                // 本测试只覆盖物品库存与旧进度的恢复；节点和世界注册不参与身份绑定。
            }
        };
        var originalInput = new ItemStack(Items.HONEYCOMB, 3);
        originalInput.set(DataComponents.CUSTOM_NAME, Component.literal("saved input"));
        var source = new ItemStack(block.asItem());
        var custom = new CompoundTag();
        custom.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(restored.getType()).toString());
        custom.putIntArray("productivebeesgenesis_pb_progress", new int[]{7});
        custom.putIntArray("productivebeesgenesis_pb_processing_time", new int[]{20});
        source.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(custom));
        var sourceInventory = mekanism.common.attachments.containers.ContainerType.ITEM.createHandler(source);
        assertNotNull(sourceInventory);
        int inputIndex = restored.getInventorySlots(null).indexOf(restored.inputSlot(0));
        assertTrue(inputIndex >= 0);
        sourceInventory.setStackInSlot(inputIndex, originalInput.copy());

        restored.loadCustomOnly(custom, REGISTRIES);
        var processor = restored.productivebeesgenesis$getPbProcessor();
        processor.bindLegacyInputsFromPlacedItem(source);
        var beforeComponents = new CompoundTag();
        processor.saveAdditional(beforeComponents, REGISTRIES);
        assertFalse(beforeComponents.getCompound("productivebeesgenesis_pb_paid_work")
                .getList("entries", 10).getCompound(0).contains("input"));

        mekanism.common.attachments.containers.ContainerType.ITEM.copyFromStack(
                REGISTRIES, source, restored.getInventorySlots(null));
        var level = mock(net.minecraft.world.level.Level.class);
        when(level.getBlockEntity(position)).thenReturn(restored);
        block.setPlacedBy(level, position, block.defaultBlockState(), null, source);
        var bound = new CompoundTag();
        processor.saveAdditional(bound, REGISTRIES);
        assertArrayEquals(new int[]{7}, bound.getIntArray("productivebeesgenesis_pb_progress"));
        var held = bound.getCompound("productivebeesgenesis_pb_paid_work").getList("entries", 10).getCompound(0);
        var boundInput = ItemStack.parse(REGISTRIES, held.getCompound("input")).orElseThrow();
        assertTrue(ItemStack.matches(originalInput.copyWithCount(1), boundInput));

        var replacement = source.copy();
        var replacementInput = originalInput.copy();
        replacementInput.set(DataComponents.CUSTOM_NAME, Component.literal("later input"));
        mekanism.common.attachments.containers.ContainerType.ITEM.createHandler(replacement)
                .setStackInSlot(inputIndex, replacementInput);
        mekanism.common.attachments.containers.ContainerType.ITEM.copyFromStack(
                REGISTRIES, replacement, restored.getInventorySlots(null));
        assertTrue(ItemStack.matches(replacementInput, restored.inputSlot(0).getStack()));
        processor.bindLegacyInputsFromPlacedItem(replacement);
        var unchanged = new CompoundTag();
        processor.saveAdditional(unchanged, REGISTRIES);
        assertEquals(held, unchanged.getCompound("productivebeesgenesis_pb_paid_work")
                .getList("entries", 10).getCompound(0));

        replacement.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(beforeComponents));
        processor.loadAdditional(beforeComponents, REGISTRIES);
        processor.bindLegacyInputsFromPlacedItem(replacement);
        var unproven = new CompoundTag();
        processor.saveAdditional(unproven, REGISTRIES);
        assertFalse(unproven.getCompound("productivebeesgenesis_pb_paid_work")
                .getList("entries", 10).getCompound(0).contains("input"));
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

    @Test void cachedMaximumRechecksLiveSlotsAndAllUpgradeRevisions() {
        var context = context();
        var owner = new PbRecipeCompleter(context);
        var recipe = recipe(1);
        assertTrue(owner.fitsMaximum(recipe, 0, 16));
        for (var slot : List.of(context.primaryOutputSlot(0), context.secondaryOutputSlot(0), context.tertiaryOutputSlot(0))) {
            slot.setStack(new ItemStack(Items.STONE, 64));
        }
        assertFalse(owner.fitsMaximum(recipe, 0, 16));
        for (var slot : List.of(context.primaryOutputSlot(0), context.secondaryOutputSlot(0), context.tertiaryOutputSlot(0))) {
            slot.setStack(ItemStack.EMPTY);
        }
        assertTrue(owner.fitsMaximum(recipe, 0, 16));
        var level = mock(net.minecraft.world.level.Level.class);
        when(context.level()).thenReturn(level);
        when(level.registryAccess()).thenReturn(REGISTRIES);
        when(context.productivebeesgenesis$hasRawOreSmeltingUpgrade()).thenReturn(true);
        try (var conversions = mockStatic(com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.class)) {
            assertTrue(owner.fitsMaximum(recipe, 0, 16));
            assertTrue(owner.fitsMaximum(recipe, 0, 16));
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.maximumPendingLongOutputs(
                    eq(level), any()), times(1));
            when(context.stabilityBonus()).thenReturn(.5f);
            assertTrue(owner.fitsMaximum(recipe, 0, 16));
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.maximumPendingLongOutputs(
                    eq(level), any()), times(2));
            com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
            assertTrue(owner.fitsMaximum(recipe, 0, 16));
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.maximumPendingLongOutputs(
                    eq(level), any()), times(3));
            var replacement = mock(net.minecraft.world.level.Level.class);
            when(replacement.registryAccess()).thenReturn(REGISTRIES);
            when(context.level()).thenReturn(replacement);
            assertTrue(owner.fitsMaximum(recipe, 0, 16));
            conversions.verify(() -> com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper.maximumPendingLongOutputs(
                    eq(replacement), any()), times(1));
        }
    }

    @Test void capacityPreparationRetainsRecipeIdentityOrderAcrossIndependentBatches() {
        var context = context();
        var owner = new PbRecipeCompleter(context);
        var recipe = recipe(1);
        var expected = recipe.getRecipeOutputs().keySet().stream().map(ItemStack::getItem).toList();
        assertEquals(expected, owner.prepare(recipe, 8, 0, true).getPendingOutputs().keySet().stream().map(ItemStack::getItem).toList());
        assertEquals(expected, owner.prepare(recipe, 8, 0, false).getPendingOutputs().keySet().stream().map(ItemStack::getItem).toList());
        var overflow = owner.prepare(recipe, 8, 0, false);
        overflow.getPendingOutputs().clear();
        var first = new ItemStack(Items.DIAMOND);
        var sameIdentity = first.copy();
        overflow.getPendingOutputs().put(first, 4L);
        overflow.getPendingOutputs().put(sameIdentity, 5L);
        assertEquals(2, overflow.getPendingOutputs().size());
        assertEquals(List.of(first, sameIdentity), List.copyOf(overflow.getPendingOutputs().keySet()));
    }


    @Test void encodedPendingTemplatesKeepIndependentReceiptsAndRefreshChangedComponents() {
        var owner = new PbRecipeCompleter(context());
        var batch = owner.prepare(recipe(1), 4, 91, false);
        var first = batch.saveCommittedPending(REGISTRIES);
        var expected = first.copy();
        first.getList("items", 10).getCompound(0).putString("external", "mutation");
        assertEquals(expected, batch.saveCommittedPending(REGISTRIES));
        var equivalentBatch = owner.prepare(recipe(1), 4, 91, false);
        var equivalentReceipt = equivalentBatch.saveCommittedPending(REGISTRIES);
        assertEquals(expected, equivalentReceipt);
        equivalentReceipt.getList("items", 10).getCompound(0).putInt("count", 31);
        assertEquals(expected, batch.saveCommittedPending(REGISTRIES));
        ItemStack template = batch.getPendingOutputs().keySet().iterator().next();
        template.set(DataComponents.CUSTOM_NAME, Component.literal("changed after caching"));
        var changed = batch.saveCommittedPending(REGISTRIES);
        assertNotEquals(expected, changed);
        var restored = new PbRecipeCompleter(context());
        restored.loadCommittedPending(changed, REGISTRIES);
        assertEquals(changed, restored.saveCommittedPending(REGISTRIES));
        assertTrue(restored.getPendingOutputs().keySet().stream()
                .anyMatch(stack -> stack.getHoverName().getString().equals("changed after caching")));
        owner.acceptPrepared(batch);
        assertNull(batch.saveCommittedPending(REGISTRIES));
        assertEquals(changed, owner.saveCommittedPending(REGISTRIES));
    }

    @Test void emptyFlushKeepsFluidOnlyAndInputOnlyCommitSemantics() {
        var context = context();
        var owner = new PbRecipeCompleter(context);
        assertTrue(owner.flushPendingPbOutputs(0));
        var fluidRecipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(), null, 20) {
            @Override public FluidStack getFluidOutputs() { return new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 10); }
        };
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        owner.accumulatePbRecipeOutputs(fluidRecipe, 0, 1);
        assertFalse(owner.flushPendingPbOutputs(0));
        assertEquals(10, owner.getPendingFluidAmount());
        assertEquals(2, context.inputSlot(0).getCount());
        owner.resetPendingRecipe();
        var noOutputs = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(), null, 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
        owner.accumulatePbRecipeOutputs(noOutputs, 0, 1);
        assertTrue(owner.flushPendingPbOutputs(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertEquals(0, owner.pendingInputShrink());
    }

    @Test void paymentFailureSurvivesReloadAndCannotReleaseOutputBeforeSettlement() {
        var context = context();
        var input = ownershipInput();
        var recipe = recipe(input, 1);
        var energy = mock(mekanism.common.capabilities.energy.MachineEnergyContainer.class);
        when(context.energyContainer()).thenReturn(energy);
        when(energy.extract(10, Action.EXECUTE, AutomationType.INTERNAL)).thenThrow(new IllegalStateException("paid callback failed"));
        context.inputSlot(0).setStack(input.copyWithCount(2));
        var owner = new PbRecipeCompleter(context);
        owner.accumulatePbRecipeOutputsBatch(recipe, 0, 2, 1);
        owner.freezeCompletedBatch(context.inputSlot(0).getStack());
        assertThrows(IllegalStateException.class, () -> owner.payForPlan(new PbVirtualTickPlan(1, 3, 1, 10),
                () -> fail("Unconfirmed payment must not advance progress")));
        assertFalse(owner.commitCompletedInput(0));
        assertFalse(owner.flushPendingPbOutputs(0));
        var saved = owner.saveCommittedPending(REGISTRIES);
        var request = owner.paymentRequest();
        var config = ModConfig.SERVER.mekCentrifugeMultiFluidTank;
        boolean previous = config.get();
        var server = mock(MinecraftServer.class);
        try {
            config.set(true);
            var entry = saved.copy();
            entry.putInt("process", 0);
            var entries = new ListTag();
            entries.add(entry);
            var pending = new CompoundTag();
            pending.put("productivebeesgenesis_pb_committed_pending", entries);
            for (var machine : ownershipMachines(recipe, server)) {
                machine.host().inputSlot(0).setStack(input.copyWithCount(2));
                machine.processor().loadAdditional(pending, REGISTRIES);
                assertTrue(machine.processor().hasUnresolvedWork());
                assertFalse(machine.processor().ownershipStateReadable());
                assertFalse(machine.assets().prepared());
                assertFalse(machine.assets().empty());
                assertEquals(List.of(new PbRecipeProcessor.PaymentStatus(0, request, 10)),
                        machine.processor().paymentStatus());
                var held = new CompoundTag();
                machine.processor().saveAdditional(held, REGISTRIES);
                assertEquals(entries, held.get("productivebeesgenesis_pb_committed_pending"));
                assertEquals(2, machine.host().inputSlot(0).getCount());
                assertTrue(ItemStack.isSameItemSameComponents(input, machine.host().inputSlot(0).getStack()));
                assertTrue(machine.host().primaryOutputSlot(0).isEmpty());
                assertTrue(machine.host().secondaryOutputSlot(0).isEmpty());
                assertTrue(machine.host().tertiaryOutputSlot(0).isEmpty());

                machine.processor().confirmPaymentSettled(request);
                assertTrue(machine.processor().paymentStatus().isEmpty());
                assertTrue(machine.processor().ownershipStateReadable());
                assertEquals(2, machine.host().inputSlot(0).getCount());
                assertTrue(ItemStack.isSameItemSameComponents(input, machine.host().inputSlot(0).getStack()));
                assertThrows(IllegalStateException.class, () -> machine.processor().confirmPaymentSettled(request));
                var settled = new CompoundTag();
                machine.processor().saveAdditional(settled, REGISTRIES);
                var expected = entries.copy();
                var settledEntry = expected.getCompound(0);
                settledEntry.remove("payment_request");
                settledEntry.remove("payment_energy");
                settledEntry.remove("payment_progress");
                assertEquals(expected, settled.get("productivebeesgenesis_pb_committed_pending"));
            }
        } finally {
            config.set(previous);
            CentrifugeRecipeIndex.clear();
            SharedPbRecipeCache.invalidate();
            ResourceRuntime.stopped(new ServerStoppedEvent(server));
        }
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertFalse(restored.commitCompletedInput(0));
        assertEquals(2, context.inputSlot(0).getCount());
        assertEquals(3, restored.confirmPayment(request));
        assertTrue(restored.commitCompletedInput(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertThrows(IllegalStateException.class, () -> restored.confirmPayment(request));
        verify(energy).extract(10, Action.EXECUTE, AutomationType.INTERNAL);
    }

    @Test void unreadablePaidWorkBlocksOwnershipAndPreservesRawState() {
        var config = ModConfig.SERVER.mekCentrifugeMultiFluidTank;
        boolean previous = config.get();
        var server = mock(MinecraftServer.class);
        try {
            config.set(true);
            var input = ownershipInput();
            var raw = StringTag.valueOf("unreadable paid work");
            var saved = new CompoundTag();
            saved.put("productivebeesgenesis_pb_paid_work", raw);
            for (var machine : ownershipMachines(recipe(input, 1), server)) {
                machine.host().inputSlot(0).setStack(input.copyWithCount(2));
                machine.processor().loadAdditional(saved, REGISTRIES);
                assertTrue(machine.processor().hasUnresolvedWork());
                assertFalse(machine.processor().ownershipStateReadable());
                assertFalse(machine.assets().prepared());
                assertFalse(machine.assets().empty());
                var preserved = new CompoundTag();
                machine.processor().saveAdditional(preserved, REGISTRIES);
                assertEquals(raw, preserved.get("productivebeesgenesis_pb_paid_work"));
                assertEquals(2, machine.host().inputSlot(0).getCount());
                assertTrue(ItemStack.isSameItemSameComponents(input, machine.host().inputSlot(0).getStack()));
            }
        } finally {
            config.set(previous);
            CentrifugeRecipeIndex.clear();
            SharedPbRecipeCache.invalidate();
            ResourceRuntime.stopped(new ServerStoppedEvent(server));
        }
    }

    private static ItemStack ownershipInput() {
        var input = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
        input.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
        return input;
    }

    private static List<OwnershipMachine> ownershipMachines(CentrifugeRecipe recipe, MinecraftServer server) {
        var manager = mock(RecipeManager.class);
        var holder = new RecipeHolder<>(ResourceLocation.parse("productivebeesgenesis:ownership_payment_fixture"), recipe);
        when(manager.getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get())).thenReturn(List.of(holder));
        when(manager.getAllRecipesFor(ResourceProductionRecipe.TYPE)).thenReturn(List.of());
        when(manager.getAllRecipesFor(ResourceCentrifugeRecipe.TYPE)).thenReturn(List.of());
        when(server.isSameThread()).thenReturn(true);
        when(server.getRecipeManager()).thenReturn(manager);
        when(server.registryAccess()).thenReturn(REGISTRIES.freeze());
        var level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.registryAccess()).thenReturn(REGISTRIES);
        assertEquals(ResourceRuntime.CentrifugeAdmission.UNMANAGED,
                ResourceRuntime.centrifugeAdmission(server, ownershipInput()));
        SharedPbRecipeCache.invalidate();
        assertTrue(CentrifugeRecipeIndex.rebuild(manager));
        var basicBlock = ModBlocks.MEK_CENTRIFUGE.get();
        var basic = (TileEntityMekCentrifuge) basicBlock.newBlockEntity(BlockPos.ZERO, basicBlock.defaultBlockState());
        basic.setLevel(level);
        var factoryBlock = ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get();
        var factory = (AbstractMekCentrifugeFactory) factoryBlock.newBlockEntity(BlockPos.ZERO, factoryBlock.defaultBlockState());
        factory.setLevel(level);
        return List.of(new OwnershipMachine(basic, basic.productivebeesgenesis$getPbProcessor(), basic.ownershipAssets()),
                new OwnershipMachine(factory, factory.productivebeesgenesis$getPbProcessor(), factory.ownershipAssets()));
    }

    private record OwnershipMachine(PbRecipeContext host, PbRecipeProcessor processor, MachineAssetSection assets) {}

    @Test void frozenUncommittedBatchSurvivesReloadAndConsumesMatchingInputOnce() {
        var context = context();
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        var owner = new PbRecipeCompleter(context);
        owner.accumulatePbRecipeOutputsBatch(recipe(1), 0, 2, 1);
        owner.freezeCompletedBatch(context.inputSlot(0).getStack());
        var frozen = owner.saveCommittedPending(REGISTRIES);
        assertEquals(1, frozen.getInt("input_count"));
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(frozen, REGISTRIES);
        assertEquals(counts(owner), counts(restored));
        context.inputSlot(0).setStack(new ItemStack(Items.STONE, 2));
        assertFalse(restored.commitCompletedInput(0));
        assertEquals(frozen, restored.saveCommittedPending(REGISTRIES));
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        assertTrue(restored.commitCompletedInput(0));
        assertTrue(restored.commitCompletedInput(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertTrue(restored.hasCommittedPendingOutputs());
    }

    @Test void largePaidOutputKeepsExactLongAmountAcrossSaveAndPartialDrain() {
        var context = context();
        when(context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
        when(context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyInt()))
                .thenAnswer(call -> call.getArgument(1));
        for (var slot : List.of(context.primaryOutputSlot(0), context.secondaryOutputSlot(0), context.tertiaryOutputSlot(0))) {
            slot.setStack(new ItemStack(Items.STONE, 64));
        }
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        var output = new ChancedOutput(Ingredient.of(Items.DIAMOND), 1_500_000_000, 1_500_000_000, 1);
        var selected = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
        var owner = new PbRecipeCompleter(context);
        owner.accumulatePbRecipeOutputsBatch(selected, 0, 1, 2);
        owner.freezeCompletedBatch(context.inputSlot(0).getStack());
        assertTrue(owner.commitCompletedInput(0));
        assertEquals(3_000_000_000L, owner.pendingItemCount());
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(owner.saveCommittedPending(REGISTRIES), REGISTRIES);
        assertFalse(restored.flushPendingPbOutputs(0));
        assertEquals(3_000_000_000L - Integer.MAX_VALUE, restored.pendingItemCount());
        assertTrue(restored.flushPendingPbOutputs(0));
        assertEquals(0, restored.pendingItemCount());
        assertTrue(context.inputSlot(0).isEmpty());
    }

    @Test void fragmentedFluidRestorePreservesRemainderAndRebuildsBothIndexes() {
        var holder = new com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder(5, 100, null, 2, () -> 0L);
        var saved = new CompoundTag();
        var root = new CompoundTag();
        var tanks = new net.minecraft.nbt.ListTag();
        for (int amount : new int[]{99, 2}) {
            var entry = new CompoundTag();
            entry.put("fluidStack", new FluidStack(net.minecraft.world.level.material.Fluids.WATER, amount).save(REGISTRIES));
            tanks.add(entry);
        }
        root.putInt("count", 5);
        root.put("tanks", tanks);
        saved.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, root);
        holder.readFromNBT(saved, REGISTRIES);
        assertEquals(101, holder.getTanks().stream().mapToInt(mekanism.api.fluid.IExtendedFluidTank::getFluidAmount).sum());
        assertEquals(3, holder.getEmptyTankCount());
        holder.readFromNBT(saved, REGISTRIES);
        assertEquals(101, holder.getTanks().stream().mapToInt(mekanism.api.fluid.IExtendedFluidTank::getFluidAmount).sum());
        var damaged = new CompoundTag();
        damaged.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, net.minecraft.nbt.StringTag.valueOf("unreadable"));
        holder.readFromNBT(damaged, REGISTRIES);
        assertTrue(holder.hasUnresolvedSavedData());
        var written = new CompoundTag();
        holder.writeToNBT(written, REGISTRIES);
        assertEquals(damaged, written);
    }


    @Test void tierPreflightRejectsChangedModeCapacityBeforeTouchingSourceFluid() {
        var config = com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER;
        boolean previous = config.mekCentrifugeMultiFluidTank.get();
        try {
            config.mekCentrifugeMultiFluidTank.set(true);
            var sourceBlock = com.ayoshiko.productivebeesgenesis.init.ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get();
            var targetBlock = com.ayoshiko.productivebeesgenesis.init.ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY.get();
            var source = (AbstractMekCentrifugeFactory) sourceBlock.newBlockEntity(
                    net.minecraft.core.BlockPos.ZERO, sourceBlock.defaultBlockState());
            var fluid = new FluidStack(net.minecraft.world.level.material.Fluids.WATER, Integer.MAX_VALUE);
            source.getFluidTank(0).setStack(fluid);
            assertTrue(com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeDataHelper.canInstallTier(
                    source, targetBlock.defaultBlockState()));
            config.mekCentrifugeMultiFluidTank.set(false);
            assertFalse(com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeDataHelper.canInstallTier(
                    source, targetBlock.defaultBlockState()));
            assertEquals(Integer.MAX_VALUE, source.getFluidTank(0).getFluidAmount());
            assertNull(source.getLevel());
        } finally {
            config.mekCentrifugeMultiFluidTank.set(previous);
        }
    }

    @Test void realTierInstallerRejectsUnknownUpgradeBeforeExtractingDataOrReplacingBlock() {
        var block = com.ayoshiko.productivebeesgenesis.init.ModBlocks.MEK_CENTRIFUGE.get();
        var source = spy((TileEntityMekCentrifuge) block.newBlockEntity(
                net.minecraft.core.BlockPos.ZERO, block.defaultBlockState()));
        var raw = new CompoundTag();
        raw.putInt("future_upgrade", 7);
        var saved = new CompoundTag();
        saved.put(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS, raw);
        source.pbUpgradeHandler().loadCounts(saved);
        var restored = new CompoundTag();
        source.pbUpgradeHandler().saveCounts(restored);
        assertEquals(saved, restored);
        assertFalse(source.productivebeesgenesis$canUpgradeProduction());
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.getBlockState(net.minecraft.core.BlockPos.ZERO)).thenReturn(block.defaultBlockState());
        when(level.registryAccess()).thenReturn(REGISTRIES);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        var player = mock(net.minecraft.world.entity.player.Player.class);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(new java.util.UUID(0, 1));
        var use = mock(net.minecraft.world.item.context.UseOnContext.class);
        when(use.getPlayer()).thenReturn(player);
        when(use.getLevel()).thenReturn(level);
        when(use.getClickedPos()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        var installer = mekanism.common.registries.MekanismItems.BASIC_TIER_INSTALLER.get();
        when(use.getItemInHand()).thenReturn(new ItemStack(installer));
        clearInvocations(source);
        try (var world = mockStatic(mekanism.common.util.WorldUtils.class)) {
            world.when(() -> mekanism.common.util.WorldUtils.getTileEntity(
                    level, net.minecraft.core.BlockPos.ZERO)).thenReturn(source);
            assertFalse(installer.useOn(use).consumesAction());
        }
        verify(source).productivebeesgenesis$canUpgradeProduction();
        verify(source, never()).getUpgradeData(any());
        verify(level, never()).setBlockAndUpdate(any(), any());
        assertEquals(1, use.getItemInHand().getCount());
    }

}
