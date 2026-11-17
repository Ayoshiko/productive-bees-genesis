package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccount;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import mekanism.api.IContentsListener;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
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

    private static Map<String, Long> counts(PbRecipeCompleter batch) {
        return batch.getPendingOutputs().entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().getItem().toString() + e.getKey().getComponentsPatch(), Map.Entry::getValue));
    }

    private static final class PaymentEnergy extends MachineEnergyContainer<TileEntityMekCentrifuge> {
        boolean unreadable;

        PaymentEnergy(IContentsListener listener) {
            super(1_000, 10, automation -> true, automation -> true, null, listener);
        }

        @Override
        public long getEnergy() {
            if (unreadable) throw new IllegalStateException("public payment receipt unavailable");
            return super.getEnergy();
        }

        long stored() {
            return ((BasicEnergyContainerAccessor) (Object) this).productivebeesgenesis$getStored();
        }
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
            when(context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong())).thenAnswer(call -> call.getArgument(1));
            var output = new ChancedOutput(Ingredient.of(Items.IRON_INGOT), 1, 1, 1);
            var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
                @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
            };
            var owner = new PbRecipeCompleter(context);
            owner.accumulatePbRecipeOutputsBatch(recipe, 0, 2, 6);
            assertTrue(owner.flushPendingPbOutputs(0));
            assertEquals(2, context.inputSlot(0).getCount());
            assertFalse(owner.hasPendingOutputs());
            verify(context).productivebeesgenesis$pushGeneratedItemToAe(any(), eq(12L));
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

    @Test void overflowedMaximumShrinksToAnExactUnpaidBatch() {
        var context = context();
        when(context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
        var outputs = List.of(Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT, Items.GOLD_INGOT).stream()
                .map(item -> new ChancedOutput(Ingredient.of(item), Integer.MAX_VALUE, Integer.MAX_VALUE, 1))
                .toList();
        var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), outputs, null, 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
        var owner = new PbRecipeCompleter(context);
        assertFalse(owner.fitsMaximum(recipe, 0, Integer.MAX_VALUE));
        assertEquals(1, new PbCentrifugeOutputPlan(owner, recipe, 0).maximumInputs(Integer.MAX_VALUE));
        var prepared = owner.prepare(recipe, 1, 19, false);
        assertEquals(4, prepared.getPendingOutputs().size());
        assertTrue(prepared.getPendingOutputs().values().stream().allMatch(amount -> amount == 2L * Integer.MAX_VALUE));
        assertEquals(8L * Integer.MAX_VALUE, prepared.pendingItemCount());
        assertFalse(owner.hasPendingOutputs());
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

    @Test void directOutputFailureRetainsOneSourceAndContinuesHealthyLocalOutputs() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
        int[] calls = {0};
        var tile = new TileEntityMekCentrifuge(net.minecraft.core.Holder.direct(block), net.minecraft.core.BlockPos.ZERO, block.defaultBlockState()) {
            @Override public boolean productivebeesgenesis$isDirectAeOutputEnabled() { return true; }
            @Override public long productivebeesgenesis$pushGeneratedItemToAe(ItemStack template, long amount) {
                return OutputCustody.of(this).handoffItem(template, amount, "test-ae-output", () -> {
                    calls[0]++;
                    throw new IllegalStateException("receiver wrote before failing");
                });
            }
        };
        var owner = new PbRecipeCompleter(tile);
        owner.acceptPrepared(owner.prepare(recipe(1), 1, 0, false));
        var expected = counts(owner);
        assertEquals(4, expected.values().stream().mapToLong(Long::longValue).sum());
        assertTrue(owner.flushPendingPbOutputs(0));
        assertEquals(1, calls[0]);
        var custody = OutputCustody.of(tile);
        assertTrue(custody.blocked());
        assertFalse(custody.localOutputBlocked());
        assertEquals(1, custody.amount());
        var saved = custody.save(REGISTRIES);
        var held = ItemStack.parse(REGISTRIES, saved.getCompound("item")).orElseThrow();
        String heldKey = held.getItem().toString() + held.getComponentsPatch();
        assertEquals(1L, expected.remove(heldKey));
        var slots = List.of(tile.primaryOutputSlot(0), tile.secondaryOutputSlot(0), tile.tertiaryOutputSlot(0));
        Map<String, Long> received = new java.util.HashMap<>();
        for (var slot : slots) {
            var stack = slot.getStack();
            assertFalse(stack.isEmpty());
            received.merge(stack.getItem().toString() + stack.getComponentsPatch(), (long) stack.getCount(), Long::sum);
        }
        assertEquals(expected, received);
        assertEquals(3, received.values().stream().mapToLong(Long::longValue).sum());
        assertFalse(owner.hasPendingOutputs());
        assertNull(owner.saveCommittedPending(REGISTRIES));
        var written = slots.stream().map(slot -> slot.getStack().copy()).toList();
        assertTrue(owner.flushPendingPbOutputs(0));
        assertEquals(saved, custody.save(REGISTRIES));
        for (int index = 0; index < slots.size(); index++) {
            assertTrue(ItemStack.matches(written.get(index), slots.get(index).getStack()));
        }
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

    @Test void transientFailureRemainsUntilActualPreparationRecovers() throws ReflectiveOperationException {
        var context = context();
        var level = mock(Level.class);
        var random = RandomSource.create(91);
        var randomField = Level.class.getField("random");
        randomField.setAccessible(true);
        randomField.set(level, random);
        when(level.registryAccess()).thenReturn(REGISTRIES);
        long[] tick = {100};
        when(level.getGameTime()).thenAnswer(call -> tick[0]);
        when(context.level()).thenReturn(level);
        when(context.processes()).thenReturn(1);
        when(context.canFunction()).thenReturn(true);
        when(context.productivityModifier()).thenReturn(1);
        when(context.productivityParallelModifier()).thenReturn(1);
        when(context.operationsPerTick()).thenReturn(1);
        when(context.getTicksForBase(anyInt())).thenReturn(2);
        var energy = mock(MachineEnergyContainer.class);
        when(context.energyContainer()).thenReturn(energy);
        when(energy.getEnergy()).thenReturn(1_000L);
        when(energy.getEnergyPerTick()).thenReturn(5L);
        when(energy.extract(anyLong(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL)))
                .thenAnswer(call -> call.getArgument(0));
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB),
                List.of(new ChancedOutput(Ingredient.of(Items.GOLD_INGOT), 1, 1, 1)), null, 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
        RecipeHolder<CentrifugeRecipe> selected = new RecipeHolder<>(ResourceLocation.parse("test:lane_recovery"), recipe);
        var processor = new PbRecipeProcessor(context, "lane-recovery-test");
        processor.refreshEnergyAndOpsCache(context);
        when(context.productivebeesgenesis$hasEssenceConversionUpgrade())
                .thenThrow(new IllegalStateException("output preparation unavailable"));
        processor.setTickMultiplier(2);
        assertFalse(processor.tryProcessPbRecipe(0, selected, 1_000));
        assertEquals(PbRecipeProcessor.LaneState.PROCESSING_FAILED, processor.getLaneState(0));
        assertEquals(2, context.inputSlot(0).getCount());
        assertTrue(context.primaryOutputSlot(0).isEmpty());

        doReturn(false).when(context).productivebeesgenesis$hasEssenceConversionUpgrade();
        when(context.canFunction()).thenReturn(false);
        tick[0]++;
        assertFalse(processor.tryProcessPbRecipe(0, selected, 1_000));
        assertEquals(PbRecipeProcessor.LaneState.PROCESSING_FAILED, processor.getLaneState(0));
        when(context.canFunction()).thenReturn(true);
        tick[0]++;
        assertFalse(processor.tryProcessPbRecipe(0, selected, 0));
        assertEquals(PbRecipeProcessor.LaneState.PROCESSING_FAILED, processor.getLaneState(0));
        tick[0]++;
        processor.setTickMultiplier(1);
        assertTrue(processor.tryProcessPbRecipe(0, selected, 1_000));
        assertEquals(PbRecipeProcessor.LaneState.PROCESSING_FAILED, processor.getLaneState(0));
        assertEquals(2, context.inputSlot(0).getCount());
        tick[0]++;
        assertTrue(processor.tryProcessPbRecipe(0, selected, 1_000));
        assertEquals(PbRecipeProcessor.LaneState.READY, processor.getLaneState(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertEquals(Items.GOLD_INGOT, context.primaryOutputSlot(0).getStack().getItem());
        assertEquals(1, context.primaryOutputSlot(0).getCount());
    }

    @Test void failedPreparationCannotOwnUnpaidOutputsAndRetryDoesNotAccumulateThem() {
        var context = context();
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        var owner = new PbRecipeCompleter(context);
        var selected = recipe(1);
        when(context.productivebeesgenesis$hasEssenceConversionUpgrade())
                .thenThrow(new IllegalStateException("output conversion unavailable"));
        assertThrows(IllegalStateException.class, () -> owner.prepareCompleted(selected, 1, 91));
        assertFalse(owner.hasPaidPendingOutputs());
        assertFalse(owner.hasPendingOutputs());
        assertEquals(0, owner.pendingInputShrink());
        assertEquals(2, context.inputSlot(0).getCount());

        doReturn(false).when(context).productivebeesgenesis$hasEssenceConversionUpgrade();
        var prepared = owner.prepareCompleted(selected, 1, 91);
        assertEquals(8, prepared.pendingItemCount());
        assertTrue(owner.payForPrepared(prepared, context.inputSlot(0).getStack(), 1,
                new PbVirtualTickPlan(1, 0, 1, 0), () -> {}));
        assertEquals(8, owner.pendingItemCount());
        assertFalse(prepared.hasPendingOutputs());
        assertTrue(owner.commitCompletedInput(0));
        assertEquals(1, context.inputSlot(0).getCount());
    }

    @Test void paymentNotificationsKeepOriginalOutputAndDebitEnergyOnce() {
        var context = context();
        boolean[] failEnergy = {false};
        PaymentEnergy[] reference = new PaymentEnergy[1];
        var energy = new PaymentEnergy(() -> {
            if (failEnergy[0]) {
                failEnergy[0] = false;
                reference[0].unreadable = true;
                throw new IllegalStateException("balance changed before payment notification failed");
            }
        });
        reference[0] = energy;
        energy.setEnergy(100);
        failEnergy[0] = true;
        doReturn(energy).when(context).energyContainer();
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        var owner = new PbRecipeCompleter(context);
        var prepared = owner.prepareCompleted(recipe(1), 1, 91);
        doThrow(new IllegalStateException("payment save callback failed"))
                .when(context).productivebeesgenesis$markForSave();
        int[] progress = {0};
        assertTrue(owner.payForPrepared(prepared,
                context.inputSlot(0).getStack(), 1, new PbVirtualTickPlan(1, 3, 1, 10),
                () -> progress[0] = 3));
        assertFalse(owner.paymentPending());
        assertEquals(3, progress[0]);
        assertEquals(90, energy.stored());
        assertEquals(8, owner.pendingItemCount());
        assertFalse(prepared.hasPendingOutputs());
        assertEquals(2, context.inputSlot(0).getCount());

        var saved = owner.saveCommittedPending(REGISTRIES);
        doNothing().when(context).productivebeesgenesis$markForSave();
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertEquals(counts(owner), counts(restored));
        assertTrue(restored.commitCompletedInput(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertTrue(restored.commitCompletedInput(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertFalse(restored.recoverKnownPayment(0, ignored -> fail("Completed payment must not advance twice")));
        assertEquals(90, energy.stored());
    }

    @Test void zeroEnergyPaymentReloadAutomaticallyKeepsProgressAndDeliversOriginalOutputOnce() {
        var context = context();
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(REGISTRIES);
        when(context.level()).thenReturn(level);
        when(context.processes()).thenReturn(1);
        when(context.canFunction()).thenReturn(true);
        var energy = mock(MachineEnergyContainer.class);
        when(context.energyContainer()).thenReturn(energy);
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 1));
        var original = new PbRecipeCompleter(context);
        var output = new ChancedOutput(Ingredient.of(Items.GOLD_INGOT), 1, 1, 1);
        var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(output), null, 20) {
            @Override public FluidStack getFluidOutputs() { return FluidStack.EMPTY; }
        };
        var prepared = original.prepareCompleted(recipe, 1, 91);
        assertTrue(original.payForPrepared(prepared, context.inputSlot(0).getStack(), 1,
                new PbVirtualTickPlan(1, 3, 1, 0), () -> {}));
        assertEquals(2, original.pendingItemCount());
        assertEquals(1, context.inputSlot(0).getCount());
        var savedPending = original.saveCommittedPending(REGISTRIES);
        var request = java.util.UUID.randomUUID();
        savedPending.putUUID("payment_request", request);
        savedPending.putLong("payment_energy", 0);
        savedPending.putInt("payment_progress", 3);
        savedPending.putInt("process", 0);
        var saved = new CompoundTag();
        var entries = new net.minecraft.nbt.ListTag();
        entries.add(savedPending);
        saved.put("productivebeesgenesis_pb_committed_pending", entries);
        doNothing().when(context).productivebeesgenesis$markForSave();
        for (var slot : List.of(context.primaryOutputSlot(0), context.secondaryOutputSlot(0), context.tertiaryOutputSlot(0))) {
            slot.setStack(new ItemStack(Items.STONE, 64));
        }
        var processor = new PbRecipeProcessor(context, "zero-energy-recovery-test");
        processor.loadAdditional(saved, REGISTRIES);
        assertEquals(request, processor.paymentStatus().getFirst().request());
        assertFalse(processor.drainCommittedPendingOutputs(0));
        assertTrue(processor.paymentStatus().isEmpty());
        assertEquals(0, context.inputSlot(0).getCount());
        var afterDebit = new CompoundTag();
        processor.saveAdditional(afterDebit, REGISTRIES);
        assertEquals(3, afterDebit.getIntArray("productivebeesgenesis_pb_progress")[0]);
        var retained = afterDebit.getList("productivebeesgenesis_pb_committed_pending", 10).getCompound(0);
        assertFalse(retained.contains("payment_request"));
        assertFalse(retained.contains("input_count"));
        assertEquals(2, retained.getList("items", 10).getCompound(0).getLong("productivebeesgenesis_count"));
        var restored = new PbRecipeProcessor(context, "zero-energy-recovery-reload-test");
        restored.loadAdditional(afterDebit, REGISTRIES);
        context.primaryOutputSlot(0).setStack(ItemStack.EMPTY);
        assertTrue(restored.drainCommittedPendingOutputs(0));
        assertEquals(Items.GOLD_INGOT, context.primaryOutputSlot(0).getStack().getItem());
        assertEquals(2, context.primaryOutputSlot(0).getCount());
        assertFalse(restored.drainCommittedPendingOutputs(0));
        assertEquals(2, context.primaryOutputSlot(0).getCount());
        assertTrue(context.inputSlot(0).isEmpty());
        assertTrue(restored.paymentStatus().isEmpty());
        verifyNoInteractions(energy);
    }

    @Test void nativeInputNotificationSettlesActualDebitWhileUnknownWrapperStaysHeld() {
        var nativeContext = context();
        boolean[] failNative = {false};
        boolean[] nativeUnreadable = {false};
        var nativeSlot = spy(BasicInventorySlot.at(() -> {
            if (failNative[0]) {
                failNative[0] = false;
                nativeUnreadable[0] = true;
                throw new IllegalStateException("input changed before local notification failed");
            }
        }, 0, 0));
        doAnswer(call -> {
            if (nativeUnreadable[0]) throw new IllegalStateException("public input receipt unavailable");
            return call.callRealMethod();
        }).when(nativeSlot).getStack();
        nativeSlot.setStack(new ItemStack(Items.HONEYCOMB, 2));
        when(nativeContext.inputSlot(0)).thenReturn(nativeSlot);
        var nativeOwner = new PbRecipeCompleter(nativeContext);
        var nativePrepared = nativeOwner.prepareCompleted(recipe(1), 1, 91);
        assertTrue(nativeOwner.payForPrepared(nativePrepared, nativeSlot.getStack(), 1,
                new PbVirtualTickPlan(1, 0, 1, 0), () -> {}));
        failNative[0] = true;
        assertTrue(nativeOwner.commitCompletedInput(0));
        assertEquals(1, ((BasicInventorySlotAccessor) (Object) nativeSlot).productivebeesgenesis$getCurrent().getCount());
        assertFalse(nativeOwner.invalidPending());
        assertEquals(8, nativeOwner.pendingItemCount());
        nativeUnreadable[0] = false;
        var nativeSaved = nativeOwner.saveCommittedPending(REGISTRIES);
        assertFalse(nativeSaved.contains("input_failure"));
        assertFalse(nativeSaved.contains("input_count"));
        var nativeRestored = new PbRecipeCompleter(nativeContext);
        nativeRestored.loadCommittedPending(nativeSaved, REGISTRIES);
        assertTrue(nativeRestored.commitCompletedInput(0));
        assertEquals(1, nativeSlot.getCount());
        assertEquals(8, nativeRestored.pendingItemCount());

        var context = context();
        var source = BasicInventorySlot.at(null, 0, 0);
        source.setStack(new ItemStack(Items.HONEYCOMB, 2));
        var slot = mock(mekanism.api.inventory.IInventorySlot.class);
        boolean[] unreadable = {false};
        when(context.inputSlot(0)).thenReturn(slot);
        when(slot.getStack()).thenAnswer(call -> {
            if (unreadable[0]) throw new IllegalStateException("input receipt unavailable");
            return source.getStack();
        });
        when(slot.shrinkStack(1, Action.EXECUTE)).thenAnswer(call -> {
            int consumed = source.shrinkStack(1, Action.EXECUTE);
            unreadable[0] = true;
            return consumed;
        });
        var owner = new PbRecipeCompleter(context);
        var prepared = owner.prepareCompleted(recipe(1), 1, 91);
        assertTrue(owner.payForPrepared(prepared, source.getStack(), 1,
                new PbVirtualTickPlan(1, 0, 1, 0), () -> {}));
        assertThrows(IllegalStateException.class, () -> owner.commitCompletedInput(0));
        assertEquals(1, source.getCount());
        assertTrue(owner.invalidPending());
        assertFalse(owner.outputUncertainPending());
        assertFalse(owner.commitCompletedInput(0));
        assertFalse(owner.flushPendingPbOutputs(0));
        var saved = owner.saveCommittedPending(REGISTRIES);
        assertEquals(2, saved.getCompound("input_failure").getInt("input_before"));
        assertEquals(1, saved.getCompound("input_failure").getInt("requested"));

        unreadable[0] = false;
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertFalse(restored.commitCompletedInput(0));
        assertFalse(restored.flushPendingPbOutputs(0));
        assertEquals(saved, restored.saveCommittedPending(REGISTRIES));
        assertEquals(1, source.getCount());
        verify(slot, times(1)).shrinkStack(1, Action.EXECUTE);
    }

    @Test void knownPartialPaymentReloadChargesOnlyRemainderAndLegacyUnknownStaysHeld() {
        var context = context();
        var energy = new PaymentEnergy(null);
        energy.setEnergy(4);
        doReturn(energy).when(context).energyContainer();
        context.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 2));
        var owner = new PbRecipeCompleter(context);
        owner.accumulatePbRecipeOutputsBatch(recipe(1), 0, 2, 1);
        owner.freezeCompletedBatch(context.inputSlot(0).getStack());
        assertFalse(owner.payForPlan(new PbVirtualTickPlan(1, 3, 1, 10),
                () -> fail("Partial payment must not advance progress")));
        assertEquals(0, energy.stored());
        assertTrue(owner.knownPaymentPending());
        assertEquals(6, owner.paymentEnergy());
        assertFalse(owner.commitCompletedInput(0));
        assertFalse(owner.flushPendingPbOutputs(0));
        var saved = owner.saveCommittedPending(REGISTRIES);
        var request = owner.paymentRequest();
        energy.setEnergy(100);
        var legacy = new PbRecipeCompleter(context);
        var withoutReceipt = saved.copy();
        withoutReceipt.remove("payment_known");
        legacy.loadCommittedPending(withoutReceipt, REGISTRIES);
        assertFalse(legacy.recoverKnownPayment(0, ignored -> fail("Legacy unknown payment must stay held")));
        assertEquals(100, energy.stored());
        var restored = new PbRecipeCompleter(context);
        restored.loadCommittedPending(saved, REGISTRIES);
        assertFalse(restored.commitCompletedInput(0));
        assertEquals(2, context.inputSlot(0).getCount());
        int[] progress = {0};
        assertTrue(restored.recoverKnownPayment(0, paidProgress -> progress[0] = paidProgress));
        assertEquals(3, progress[0]);
        assertEquals(94, energy.stored());
        assertTrue(restored.commitCompletedInput(0));
        assertEquals(1, context.inputSlot(0).getCount());
        assertThrows(IllegalStateException.class, () -> restored.confirmPayment(request));
        assertFalse(restored.recoverKnownPayment(0, ignored -> fail("Payment must not advance twice")));
        assertEquals(94, energy.stored());
    }

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
        when(context.productivebeesgenesis$pushGeneratedItemToAe(any(), anyLong()))
                .thenAnswer(call -> Math.min(2_500_000_000L, (long) call.getArgument(1)));
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
        assertEquals(500_000_000L, restored.pendingItemCount());
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
