package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("minecraft")
class Ae2OutputCustodyMinecraftTest {
    @org.junit.jupiter.api.BeforeAll
    static void configs() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @org.junit.jupiter.api.AfterAll
    static void unload() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
    }

    @Test void generatedKeysKeepFullComponentsWhenTemplateAndQuantityChange() {
        var holder = new Ae2OutputStateHolder();
        var network = mock(MEStorage.class);
        var received = new java.util.ArrayList<AEItemKey>();
        when(network.insert(any(), anyLong(), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
            received.add(call.getArgument(0));
            return call.getArgument(1);
        });
        var session = new Ae2DirectItemPushSession(holder, network, null, 10, null);
        session.custody = new OutputCustody(() -> {});
        ItemStack source = new ItemStack(Items.DIAMOND, 5);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("first"));
        assertEquals(5, session.push(source, 5));
        assertEquals(50, session.push(source.copyWithCount(50), 50));
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("second"));
        assertEquals(3, session.push(source, 3));
        assertEquals(received.get(0), received.get(1));
        assertNotEquals(received.get(0), received.get(2));
        session.reset(holder, network, null, 11, null);
        assertEquals(7, session.push(source, 7));
        assertEquals(received.get(2), received.get(3));
        verify(network, times(4)).insert(any(), anyLong(), any(), any());
    }

    @Test void unknownDirectReceiptRemainsInCustodyAndNeverRetries() {
        var network = mock(MEStorage.class);
        when(network.insert(any(), anyLong(), any(), any())).thenThrow(new IllegalStateException("unknown outcome"));
        var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), network, null, 10, null);
        session.custody = new OutputCustody(() -> {});
        ItemStack source = new ItemStack(Items.DIAMOND, 5);
        assertEquals(5, session.push(source, 5));
        assertTrue(session.custody.blocked());
        assertEquals(5, session.custody.amount());
        assertEquals(0, session.push(source, 5));
        verify(network, times(1)).insert(any(), anyLong(), any(), any());
    }

    @Test void sourceListenerThrowAfterDebitDoesNotDebitTwice() {
        ItemStack[] content = {new ItemStack(Items.IRON_INGOT, 20)};
        IInventorySlot slot = mock(IInventorySlot.class);
        when(slot.getStack()).thenAnswer(c -> content[0]);
        doAnswer(c -> { content[0] = c.getArgument(0); throw new IllegalStateException("after debit"); }).when(slot).setStack(any());
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var key = AEItemKey.of(content[0]);
        var entry = new Ae2SlotEntry(); entry.set(slot, content[0], key, 20, 0, 0, Ae2ItemFingerprint.encode(key, registries));
        var ledger = new Ae2OutputLedger();
        var network = mock(MEStorage.class);
        when(network.insert(eq(key), eq(20L), eq(Actionable.MODULATE), any())).thenReturn(7L);
        Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(), ledger, new OutputCustody(() -> {}));
        assertEquals(13, content[0].getCount());
        assertEquals(1, ledger.size());
        var host = mock(IAe2OutputHostBase.class);
        when(host.primaryOutputSlot(0)).thenReturn(slot);
        assertEquals(7, Ae2OutputCommitter.settleOutputLedger(host, ledger, registries));
        assertEquals(13, content[0].getCount());
        assertEquals(0, ledger.size());
    }

    @Test void savedOutputLedgerProtectsOnlyItsPhysicalSlotAndResumesAfterConfirmation() {
        var block = ModBlocks.MEK_CENTRIFUGE.get();
        var host = (TileEntityMekCentrifuge) block.newBlockEntity(
                net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
        var held = host.primaryOutputSlot(0);
        var healthy = host.secondaryOutputSlot(0);
        held.setStack(new ItemStack(Items.IRON_INGOT, 20));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var key = AEItemKey.of(held.getStack());
        var ledger = host.productivebeesgenesis$getAe2StateHolder().getOutputLedger();
        assertTrue(ledger.reserve(0, 0, Ae2ItemFingerprint.encode(key, registries), 20));
        ledger.commitAccepted(0, 0, 7);
        var saved = new CompoundTag();
        ledger.save(saved);
        ledger.load(saved);

        var offered = new ItemStack(Items.IRON_INGOT, 6);
        for (Action action : Action.values()) {
            assertEquals(6, held.insertItem(offered, action, AutomationType.INTERNAL).getCount());
            assertTrue(held.extractItem(1, action, AutomationType.EXTERNAL).isEmpty());
        }
        var menuSlot = ((mekanism.common.inventory.slot.BasicInventorySlot) held).createContainerSlot();
        assertNotNull(menuSlot);
        assertFalse(menuSlot.mayPickup(null));
        assertTrue(menuSlot.remove(1).isEmpty());
        assertEquals(20, held.getCount());
        assertTrue(healthy.insertItem(offered, Action.EXECUTE, AutomationType.INTERNAL).isEmpty());
        assertEquals(2, healthy.extractItem(2, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
        assertEquals(4, healthy.getCount());

        held.setStack(new ItemStack(Items.IRON_INGOT, 17));
        assertEquals(0, Ae2OutputCommitter.settleOutputLedger(host, ledger, registries));
        var conflict = new CompoundTag();
        ledger.save(conflict);
        assertEquals(saved, conflict);
        assertEquals(17, held.getCount());
        held.setStack(new ItemStack(Items.IRON_INGOT, 20));
        assertEquals(7, Ae2OutputCommitter.settleOutputLedger(host, ledger, registries));
        assertEquals(13, held.getCount());
        assertEquals(0, ledger.size());
        assertTrue(held.insertItem(offered, Action.EXECUTE, AutomationType.INTERNAL).isEmpty());
        assertTrue(menuSlot.mayPickup(null));
        assertEquals(1, menuSlot.remove(1).getCount());
        assertEquals(18, held.getCount());
        assertEquals(4, healthy.getCount());
    }

    @Test void knownMergedReceiptSettlesHealthySlotsAfterOneSourceListenerFails() {
        var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var key = AEItemKey.of(Items.IRON_INGOT);
        var ledger = new Ae2OutputLedger();
        var entries = new java.util.ArrayList<Ae2SlotEntry>();
        ItemStack[] held = {new ItemStack(Items.IRON_INGOT, 8)};
        var first = mock(IInventorySlot.class);
        when(first.getStack()).thenAnswer(call -> held[0]);
        doAnswer(call -> {
            held[0] = call.getArgument(0);
            throw new IllegalStateException("first source listener failed after debit");
        }).when(first).setStack(any());
        var second = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        second.setStack(new ItemStack(Items.IRON_INGOT, 7));
        var third = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        third.setStack(new ItemStack(Items.IRON_INGOT, 5));
        var slots = List.of(first, second, third);
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            var entry = new Ae2SlotEntry();
            entry.set(slot, slot.getStack(), key, slot.getStack().getCount(), 0, index,
                    Ae2ItemFingerprint.encode(key, registry));
            entries.add(entry);
        }
        var network = mock(MEStorage.class);
        when(network.insert(eq(key), eq(20L), eq(Actionable.MODULATE), any())).thenReturn(20L);
        assertEquals(12, Ae2OutputCommitter.pushBatchKey(key, 20, entries, network,
                IActionSource.empty(), ledger, new OutputCustody(() -> {})));
        assertTrue(held[0].isEmpty());
        assertTrue(second.isEmpty());
        assertTrue(third.isEmpty());
        assertEquals(1, ledger.size());
        assertTrue(ledger.hasSlot(0, 0));
        var host = mock(IAe2OutputHostBase.class);
        when(host.primaryOutputSlot(0)).thenReturn(first);
        assertEquals(8, Ae2OutputCommitter.settleOutputLedger(host, ledger, registry));
        assertEquals(0, ledger.size());
        verify(network, times(1)).insert(any(), anyLong(), any(), any());
    }

    @Test void mergedPreparationFailureCancelsEveryUnsubmittedReservation() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var first = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        first.setStack(new ItemStack(Items.IRON_INGOT, 8));
        var second = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
        second.setStack(new ItemStack(Items.IRON_INGOT, 7));
        var firstEntry = new Ae2SlotEntry();
        firstEntry.set(first, first.getStack(), key, 8, 0, 0, "first-source");
        var secondEntry = new Ae2SlotEntry();
        var fingerprint = mock(Ae2FingerprintCache.class);
        when(fingerprint.get(eq(key), any())).thenThrow(new IllegalStateException("failed second fingerprint"));
        secondEntry.set(second, second.getStack(), key, 7, 0, 1, fingerprint, null);
        var ledger = new Ae2OutputLedger();
        var network = mock(MEStorage.class);
        assertEquals(0, Ae2OutputCommitter.pushBatchKey(key, 15, List.of(firstEntry, secondEntry), network,
                IActionSource.empty(), ledger, new OutputCustody(() -> {})));
        assertEquals(0, ledger.size());
        assertEquals(8, first.getCount());
        assertEquals(7, second.getCount());
        verifyNoInteractions(network);
    }

    @Test void innerModulateExceptionCreatesOneUncertainRequestAndDebitsSource() {
        ItemStack[] content = {new ItemStack(Items.DIAMOND, 20)};
        IInventorySlot slot = mock(IInventorySlot.class);
        when(slot.getStack()).thenAnswer(c -> content[0]);
        doAnswer(c -> { content[0] = c.getArgument(0); return null; }).when(slot).setStack(any());
        var key = AEItemKey.of(content[0]);
        var entry = new Ae2SlotEntry(); entry.set(slot, content[0], key, 20, 0, 0, "diamond");
        var ledger = new Ae2OutputLedger();
        var network = mock(MEStorage.class);
        when(network.insert(any(), anyLong(), eq(Actionable.MODULATE), any())).thenThrow(new IllegalStateException("unknown accepted prefix"));
        var custody = new OutputCustody(() -> {});
        assertEquals(20, Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(), ledger, custody));
        assertTrue(content[0].isEmpty());
        assertEquals(20, custody.amount());
        assertEquals(0, ledger.size());
        assertEquals(0, Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(), ledger, custody));
        verify(network, times(1)).insert(any(), anyLong(), any(), any());
    }

    @Test void fluidDebitKeepsKnownDifferenceAndUnknownPrefixWithoutReoffering() {
        var template = collisionFluid(0);
        var key = AEFluidKey.of(template);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (boolean throwsAfterDebit : new boolean[]{false, true}) {
            var custody = new OutputCustody(() -> {});
            var tank = mock(IExtendedFluidTank.class);
            var content = template.copyWithAmount(10);
            when(tank.isEmpty()).thenAnswer(call -> content.isEmpty());
            when(tank.getFluid()).thenAnswer(call -> content);
            when(tank.getFluidAmount()).thenAnswer(call -> content.getAmount());
            when(tank.shrinkStack(7, Action.EXECUTE)).thenAnswer(call -> {
                content.shrink(3);
                if (throwsAfterDebit) throw new IllegalStateException("known external receipt, unknown source debit");
                return 3;
            });
            assertEquals(7, custody.handoffFluidFromTank(template, 7, "ae2-tank", () -> 7));
            Ae2FluidPusher.settleFluidSource(mock(IAe2OutputHostBase.class), List.of(tank),
                    key, 7, 1, custody);
            assertEquals(7, content.getAmount());
            assertTrue(custody.blocked());
            assertEquals(throwsAfterDebit ? 7 : 4, custody.sourceDebitPending());
            assertEquals(throwsAfterDebit, custody.sourceDebitUnknown());
            var restored = new OutputCustody(() -> {});
            restored.load(registries, custody.save(registries));
            assertEquals(custody.sourceDebitPending(), restored.sourceDebitPending());
            assertEquals(custody.sourceDebitUnknown(), restored.sourceDebitUnknown());
            assertEquals(0, restored.handoffFluidFromTank(template, 7, "ae2-tank", () -> {
                fail("unsettled fluid must never be offered again");
                return 0;
            }));
        }
    }

    @Test void localOutputVerificationProtectsItsSlotAndAllowsHealthyAeHandoffs() {
        var custody = new OutputCustody(() -> {});
        var local = custody.localItem(0);
        var heldTemplate = new ItemStack(Items.DIAMOND);
        assertEquals(3, local.handoffLocalItem(heldTemplate, 3, ItemStack.EMPTY, "machine-slot:0",
                () -> { throw new IllegalStateException("slot temporarily unreadable"); },
                () -> { throw new IllegalStateException("slot listener failed"); }));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var before = local.save(registries);
        ItemStack[] held = {new ItemStack(Items.DIAMOND, 3)};
        var heldSlot = mock(IInventorySlot.class);
        when(heldSlot.getStack()).thenAnswer(call -> held[0]);
        doAnswer(call -> { held[0] = call.getArgument(0); return null; }).when(heldSlot).setStack(any());
        var heldKey = AEItemKey.of(held[0]);
        var heldEntry = new Ae2SlotEntry();
        heldEntry.set(heldSlot, held[0], heldKey, 3, 0, 0, "held");
        ItemStack[] healthy = {new ItemStack(Items.IRON_INGOT, 7)};
        var healthySlot = mock(IInventorySlot.class);
        when(healthySlot.getStack()).thenAnswer(call -> healthy[0]);
        doAnswer(call -> { healthy[0] = call.getArgument(0); return null; }).when(healthySlot).setStack(any());
        var healthyKey = AEItemKey.of(healthy[0]);
        var healthyEntry = new Ae2SlotEntry();
        healthyEntry.set(healthySlot, healthy[0], healthyKey, 7, 0, 1, "healthy");
        var ledger = new Ae2OutputLedger();
        var network = mock(MEStorage.class);
        when(network.insert(eq(healthyKey), eq(7L), eq(Actionable.MODULATE), any())).thenReturn(7L);
        assertTrue(custody.blocked());
        assertFalse(custody.externalOutputBlocked());
        assertEquals(0, Ae2OutputCommitter.tryPushSlotDirect(heldEntry, network, IActionSource.empty(), ledger, custody));
        assertEquals(7, Ae2OutputCommitter.tryPushSlotDirect(healthyEntry, network, IActionSource.empty(), ledger, custody));
        assertEquals(3, held[0].getCount());
        assertTrue(healthy[0].isEmpty());
        assertEquals(0, ledger.size());
        assertEquals(before, local.save(registries));
        verify(network, times(1)).insert(any(), anyLong(), any(), any());
    }

    @Test void fluidSourceSettlementSkipsTheTankAwaitingLocalVerification() {
        var custody = new OutputCustody(() -> {});
        var template = collisionFluid(0);
        var local = custody.localFluid(0);
        assertEquals(3, local.handoffLocalFluid(template, 3, FluidStack.EMPTY, "machine-tank:0",
                () -> { throw new IllegalStateException("tank temporarily unreadable"); },
                () -> { throw new IllegalStateException("tank listener failed"); }));
        var heldTank = mock(IExtendedFluidTank.class);
        var healthyTank = mock(IExtendedFluidTank.class);
        var content = template.copyWithAmount(7);
        when(healthyTank.isEmpty()).thenAnswer(call -> content.isEmpty());
        when(healthyTank.getFluid()).thenAnswer(call -> content);
        when(healthyTank.getFluidAmount()).thenAnswer(call -> content.getAmount());
        when(healthyTank.shrinkStack(7, Action.EXECUTE)).thenAnswer(call -> {
            content.shrink(7);
            return 7;
        });
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var before = local.save(registries);
        assertEquals(7, custody.handoffFluidFromTank(template, 7, "ae2-tank", () -> 7));
        Ae2FluidPusher.settleFluidSource(mock(IAe2OutputHostBase.class), List.of(heldTank, healthyTank),
                AEFluidKey.of(template), 7, 2, custody);
        assertTrue(content.isEmpty());
        assertEquals(0, custody.sourceDebitPending());
        assertFalse(custody.externalOutputBlocked());
        assertEquals(before, local.save(registries));
        verifyNoInteractions(heldTank);
        verify(healthyTank, times(1)).shrinkStack(7, Action.EXECUTE);
    }

    @Test void damagedLedgerRetainsEveryOriginalTagAndRejectsNewReservations() {
        var parent = new CompoundTag();
        var valid = new Ae2OutputLedger();
        assertTrue(valid.reserve(0, 0, "iron", 10));
        valid.commitAccepted(0, 0, 7);
        valid.save(parent);
        var baseline = parent.getList(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER, net.minecraft.nbt.Tag.TAG_COMPOUND);
        var duplicate = baseline.copy();
        duplicate.add(baseline.getCompound(0).copy());
        var invalidAmount = baseline.copy();
        invalidAmount.getCompound(0).putInt("remaining", 11);
        var wrongField = baseline.copy();
        wrongField.getCompound(0).putLong("slot", 0);
        var wrongElements = new ListTag();
        wrongElements.add(StringTag.valueOf("not a settlement"));
        var tooMany = new ListTag();
        for (int i = 0; i <= Ae2OutputLedger.MAX_ENTRIES; i++) {
            var entry = baseline.getCompound(0).copy();
            entry.putInt("process", i);
            tooMany.add(entry);
        }
        for (var raw : List.of(StringTag.valueOf("raw ledger"),
                duplicate, invalidAmount, wrongField, wrongElements, tooMany)) {
            var damaged = new CompoundTag();
            damaged.put(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER, raw);
            var ledger = new Ae2OutputLedger();
            ledger.load(damaged);
            assertTrue(ledger.hasInvalidData());
            assertFalse(ledger.reserve(1, 0, "new-output", 1));
            assertTrue(ledger.hasSlot(10, 2));
            assertFalse(ledger.retryDecode());
            var saved = new CompoundTag();
            ledger.save(saved);
            assertEquals(raw, saved.get(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER));
        }
        var restored = new Ae2OutputLedger();
        restored.load(parent);
        assertFalse(restored.hasInvalidData());
        assertEquals(7, restored.snapshot().getFirst().remaining());
    }

    private static FluidStack collisionFluid(long value) {
        var fluid = new FluidStack(
                Fluids.WATER, 10);
        var data = new CompoundTag();
        data.putLong("same-hash", value);
        fluid.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                CustomData.of(data));
        return fluid;
    }
}
