package com.ayoshiko.productivebeesgenesis.logistics;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("minecraft")
class OutputCustodyMinecraftTest {
    private static OutputCustody custody() { return new OutputCustody(() -> {}); }

    @Test void successfulTemplateReuseKeepsLaterComponentChangesAndUnknownRequestIndependent() {
        var custody = custody();
        ItemStack source = new ItemStack(Items.DIAMOND, 6);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("first"));
        assertEquals(6, custody.handoffItem(source, "target", () -> 6));
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("second"));
        assertEquals(6, custody.handoffItem(source, "target", () -> {
            source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("receiver mutation"));
            throw new IllegalStateException("unknown acceptance");
        }));
        custody.resolve(custody.sequence(), 2);
        ItemStack recovered = custody.recoveredItem();
        assertEquals(4, recovered.getCount());
        assertEquals("second", recovered.getHoverName().getString());
        recovered.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("changed recovery copy"));
        assertEquals("second", custody.recoveredItem().getHoverName().getString());
    }

    @Test void cachedItemTemplateStillSavesTheFullRequestBeforeReceiverChangesSource() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var holder = new OutputCustody[1];
        var snapshots = new java.util.ArrayList<net.minecraft.nbt.CompoundTag>();
        holder[0] = new OutputCustody(() -> snapshots.add(holder[0].save(registries)));
        ItemStack source = new ItemStack(Items.IRON_INGOT, 7);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("original identity"));
        assertEquals(7, holder[0].handoffItem(source, "target", () -> 7));
        snapshots.clear();
        assertEquals(7, holder[0].handoffItem(source, "target", () -> {
            source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("changed source"));
            throw new IllegalStateException("saved unknown request");
        }));
        var restored = custody();
        restored.load(registries, snapshots.getFirst());
        restored.resolve(restored.sequence(), 3);
        assertEquals(4, restored.recoveredItem().getCount());
        assertEquals("original identity", restored.recoveredItem().getHoverName().getString());
        assertEquals(snapshots.getFirst().getUUID("sequence"), holder[0].sequence());
    }

    @Test void knownPrefixAndUnknownSuffixHaveSeparateOwnersAndSurviveRestart() {
        var custody = custody();
        var handler = new ItemStackHandler(2) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (slot == 0) { setStackInSlot(0, stack.copyWithCount(3)); return stack.copyWithCount(stack.getCount() - 3); }
                setStackInSlot(1, stack.copyWithCount(2));
                throw new IllegalStateException("receiver wrote before throwing");
            }
        };
        ItemStack rest = ItemPushHelper.insert(handler, new ItemStack(Items.IRON_INGOT, 10), custody, "test");
        assertTrue(rest.isEmpty());
        assertEquals(3, handler.getStackInSlot(0).getCount());
        assertEquals(7, custody.amount());
        assertTrue(custody.blocked());
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = custody.save(registries);
        var recovered = custody();
        recovered.load(registries, saved);
        assertEquals(saved, recovered.save(registries));
        assertEquals(0, recovered.handoffItem(new ItemStack(Items.IRON_INGOT), "other", () -> fail("must not retry")));
        var id = recovered.sequence();
        recovered.resolve(id, 2);
        assertEquals(5, recovered.recoveredItem().getCount());
        recovered.consumeRecovered(5);
        assertFalse(recovered.blocked());
        assertThrows(IllegalStateException.class, () -> recovered.resolve(id, 0));
    }

    @Test void localReceiverMayMutateItsCopyWithoutChangingTheOwnedTemplate() {
        var source = new ItemStack(Items.IRON_INGOT, 10);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("完整回执"));
        var slot = mock(mekanism.api.inventory.IInventorySlot.class);
        when(slot.getStack()).thenReturn(ItemStack.EMPTY);
        when(slot.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
            ItemStack offered = call.getArgument(0);
            assertNotSame(source, offered);
            offered.setCount(7);
            return offered;
        });
        var retained = custody();
        assertEquals(3, LocalOutputTransfer.insert(java.util.List.of(slot), source, retained));
        assertEquals(10, source.getCount());
        assertFalse(retained.blocked());
        assertThrows(IllegalStateException.class,
                () -> ItemPushHelper.accepted(source, 7, source.copyWithCount(8)));
    }

    @Test void localKnownPrefixAndWrongComponentSuffixRemainSeparatelyOwned() {
        var source = new ItemStack(Items.IRON_INGOT, 10);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("原始物品"));
        var first = mock(mekanism.api.inventory.IInventorySlot.class);
        var second = mock(mekanism.api.inventory.IInventorySlot.class);
        when(first.getStack()).thenReturn(ItemStack.EMPTY);
        when(second.getStack()).thenReturn(ItemStack.EMPTY);
        when(first.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL)))
                .thenAnswer(call -> ((ItemStack) call.getArgument(0)).copyWithCount(7));
        when(second.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL)))
                .thenReturn(new ItemStack(Items.GOLD_INGOT, 4));
        var retained = custody();
        assertEquals(10, LocalOutputTransfer.insert(java.util.List.of(first, second), source, retained));
        assertEquals(7, retained.amount());
        assertEquals(10, source.getCount());
        assertTrue(retained.blocked());
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("外部后续修改"));
        retained.resolve(retained.sequence(), 0);
        assertEquals("原始物品", retained.recoveredItem()
                .get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString());
        assertEquals(7, retained.recoveredItem().getCount());
    }

    @Test void executionThrowBeforeObservableAcceptanceStillRequiresResolution() {
        var custody = custody();
        assertEquals(6, custody.handoffItem(new ItemStack(Items.DIAMOND, 6), "target", () -> { throw new IllegalStateException(); }));
        assertEquals(6, custody.amount());
        assertTrue(custody.recoveredItem().isEmpty());
        custody.resolve(custody.sequence(), 0);
        assertEquals(6, custody.recoveredItem().getCount());
    }

    @Test void successfulUnobservedTransfersClearTheExecutionGuardAndLeaveNoReceipt() {
        var custody = custody();
        for (int i = 0; i < 32; i++) {
            assertEquals(6, custody.handoffItem(new ItemStack(Items.DIAMOND, 6), "target", () -> {
                assertTrue(custody.blocked());
                assertEquals(0, custody.handoffItem(new ItemStack(Items.DIAMOND), "reentry", () -> fail("reentry")));
                return 6;
            }));
            assertFalse(custody.blocked());
            assertNull(custody.sequence());
        }
        assertTrue(custody.save(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)).isEmpty());
    }

    @Test void saveDuringItemOrFluidExecutionKeepsOneIdentityThroughFailureAndRestart() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (boolean fluid : new boolean[]{false, true}) {
            var custody = custody();
            var snapshot = new java.util.concurrent.atomic.AtomicReference<net.minecraft.nbt.CompoundTag>();
            java.util.function.LongSupplier receiver = () -> {
                var saved = custody.save(registries);
                snapshot.set(saved);
                assertEquals(saved.getUUID("sequence"), custody.sequence());
                assertEquals(saved, custody.save(registries));
                assertThrows(IllegalStateException.class, () -> custody.resolve(custody.sequence(), 0));
                throw new IllegalStateException("saved during external insertion");
            };
            long handedOff = fluid
                    ? custody.handoffFluid(new FluidStack(Fluids.WATER, 6), 6, "target", receiver)
                    : custody.handoffItem(new ItemStack(Items.DIAMOND, 6), "target", receiver);
            assertEquals(6, handedOff);
            assertEquals(snapshot.get().getUUID("sequence"), custody.sequence());
            var restored = custody();
            restored.load(registries, snapshot.get());
            assertTrue(restored.blocked());
            assertEquals(custody.sequence(), restored.sequence());
            restored.resolve(restored.sequence(), 2);
            assertEquals(4, restored.amount());
        }
    }

    @Test void synchronousSaveOnChangeSeesTheRequestThenAnEmptySuccessfulReceipt() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var snapshots = new java.util.ArrayList<net.minecraft.nbt.CompoundTag>();
        var holder = new OutputCustody[1];
        holder[0] = new OutputCustody(() -> snapshots.add(holder[0].save(registries)));
        assertEquals(6, holder[0].handoffItem(new ItemStack(Items.DIAMOND, 6), "target", () -> 6));
        assertEquals(2, snapshots.size());
        assertTrue(snapshots.getFirst().hasUUID("sequence"));
        assertEquals(6, snapshots.getFirst().getLong("amount"));
        assertTrue(snapshots.getLast().isEmpty());
        assertFalse(holder[0].blocked());
        assertNull(holder[0].sequence());
    }

    @Test void fluidExecutionUnknownNeverReturnsEntireRequestAndKnownRemainderIsRetained() {
        for (boolean throwsAfterWrite : new boolean[]{false, true}) {
            var custody = custody();
            var tank = mock(IExtendedFluidTank.class);
            when(tank.extract(anyInt(), eq(Action.SIMULATE), any())).thenReturn(new FluidStack(Fluids.WATER, 100));
            when(tank.extract(anyInt(), eq(Action.EXECUTE), any())).thenReturn(new FluidStack(Fluids.WATER, 100));
            when(tank.insert(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(c -> c.getArgument(0));
            var target = mock(IFluidHandler.class);
            when(target.fill(any(), eq(IFluidHandler.FluidAction.SIMULATE))).thenReturn(100);
            when(target.fill(any(), eq(IFluidHandler.FluidAction.EXECUTE))).thenAnswer(c -> {
                if (throwsAfterWrite) throw new IllegalStateException("accepted 40 then failed");
                return 40;
            });
            assertEquals(throwsAfterWrite ? 100 : 40, FastFluidEjector.transfer(tank, target, custody));
            assertEquals(throwsAfterWrite ? 100 : 60, custody.amount());
            if (throwsAfterWrite) {
                verify(tank, never()).insert(any(), any(), any());
                assertTrue(custody.recoveredFluid().isEmpty());
                custody.resolve(custody.sequence(), 40);
            }
            assertEquals(60, custody.recoveredFluid().getAmount());
        }
    }

    @Test void simulationFailureDoesNotExtract() {
        var tank = mock(IExtendedFluidTank.class);
        when(tank.extract(anyInt(), eq(Action.SIMULATE), any())).thenReturn(new FluidStack(Fluids.WATER, 100));
        var target = mock(IFluidHandler.class);
        when(target.fill(any(), any())).thenThrow(new IllegalStateException());
        var custody = custody();
        assertEquals(0, FastFluidEjector.transfer(tank, target, custody));
        verify(tank, never()).extract(anyInt(), eq(Action.EXECUTE), any());
        assertFalse(custody.blocked());
    }

    @Test void corruptCustodyIsKeptVerbatim() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("version", 99);
        tag.putString("evidence", "原文保管");
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var custody = custody();
        custody.load(registries, tag);
        assertTrue(custody.blocked());
        assertEquals(tag, custody.save(registries));
    }

    @Test void malformedResolvedReceiptCannotBeRecoveredOrRewritten() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var original = custody();
        original.retainItem(new ItemStack(Items.DIAMOND, 6), "known remainder");
        var valid = original.save(registries);
        java.util.List<java.util.function.Consumer<net.minecraft.nbt.CompoundTag>> corruptions = java.util.List.of(
                tag -> tag.putDouble("amount", 6.75),
                tag -> tag.putString("resolved", "true"),
                tag -> tag.putString("fluid", "unreadable second asset"),
                tag -> tag.putString("future_asset", "保留未知版本字段"));
        for (var corrupt : corruptions) {
            var damaged = valid.copy(); corrupt.accept(damaged);
            var restored = custody(); restored.load(registries, damaged);
            assertTrue(restored.blocked());
            assertTrue(restored.recoveredItem().isEmpty());
            assertEquals(damaged, restored.save(registries));
            restored.recoverItem(stack -> fail("invalid receipt must stay isolated"));
            restored.retryDecode(registries);
            assertEquals(damaged, restored.save(registries));
        }
    }
}
