package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import mekanism.api.Action;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
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
        Ae2OutputCommitter.settleOutputLedger(host, ledger, registries);
        assertEquals(13, content[0].getCount());
        assertEquals(0, ledger.size());
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

    @Test void fluidKeysDistinguishEqualHashesAcrossAllThreeCaches() throws Exception {
        var first = collisionFluid(0L);
        var second = collisionFluid(4_294_967_297L);
        assertEquals(first.getComponents().hashCode(), second.getComponents().hashCode());
        assertFalse(FluidStack.isSameFluidSameComponents(first, second));
        var holder = new Ae2OutputStateHolder();
        var slotCache = Ae2FluidPusher.class.getDeclaredMethod("getCachedFluidKey",
                Ae2OutputStateHolder.class, int.class, FluidStack.class);
        slotCache.setAccessible(true);
        var generatedCache = Ae2FluidPusher.class.getDeclaredMethod("getCachedGeneratedFluidKey",
                Ae2OutputStateHolder.class, FluidStack.class);
        generatedCache.setAccessible(true);
        var oldSlot = (AEFluidKey) slotCache.invoke(null, holder, 0, first);
        var newSlot = (AEFluidKey) slotCache.invoke(null, holder, 0, second);
        assertTrue(oldSlot.matches(first));
        assertTrue(newSlot.matches(second));
        assertNotEquals(oldSlot, newSlot);
        var oldGenerated = (AEFluidKey) generatedCache.invoke(null, holder, first);
        var newGenerated = (AEFluidKey) generatedCache.invoke(null, holder, second);
        assertNotEquals(oldGenerated, newGenerated);
        assertTrue(newGenerated.matches(second));
        assertSame(newGenerated, generatedCache.invoke(null, holder, second.copyWithAmount(3)));

        var sideHost = mock(ISideConfiguration.class);
        var host = mock(IAe2OutputHostBase.class);
        when(host.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
        var storage = CentrifugeExternalAeStorage.getOrCreate(host,
                mock(IMekCentrifugeTile.class), sideHost, Direction.UP);
        var externalCache = CentrifugeExternalAeStorage.class.getDeclaredMethod("getCachedFluidKey",
                int.class, FluidStack.class);
        externalCache.setAccessible(true);
        var oldExternal = (AEFluidKey) externalCache.invoke(storage, 0, first);
        var newExternal = (AEFluidKey) externalCache.invoke(storage, 0, second);
        assertNotEquals(oldExternal, newExternal);
        assertTrue(newExternal.matches(second));
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
