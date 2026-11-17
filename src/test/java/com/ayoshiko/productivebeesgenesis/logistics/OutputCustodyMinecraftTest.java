package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.inventory.OutputFluidTank;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("minecraft")
class OutputCustodyMinecraftTest {
    @BeforeAll static void configs() { net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs(); }
    @AfterAll static void unload() { net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER); }
    private static OutputCustody custody() { return new OutputCustody(() -> {}); }

    @Test
    void sharedItemExecutionSnapshotKeepsTheContributionAndBlocksReplay() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var request = UUID.randomUUID();
        var first = custody();
        var second = custody();
        ItemStack template = new ItemStack(Items.DIAMOND);
        template.set(DataComponents.CUSTOM_NAME, Component.literal("原身份"));
        assertTrue(first.beginSharedItem(request, template, 5, "ae2-shared-item"));
        assertTrue(second.beginSharedItem(request, template, 7, "ae2-shared-item"));
        template.set(DataComponents.CUSTOM_NAME, Component.literal("后续修改"));
        assertFalse(first.beginSharedItem(UUID.randomUUID(), template, 1, "reentry"));
        assertThrows(IllegalStateException.class, () -> first.confirmSourceDebit(5));
        assertThrows(IllegalStateException.class, () -> first.confirmSourceSettled(request));
        var during = first.save(registries);
        assertEquals(2, during.getInt("version"));
        assertEquals(5, during.getLong("source_debit_pending"));
        var restored = custody();
        restored.load(registries, during);
        assertEquals(during, restored.save(registries));
        assertTrue(restored.blocked());
        assertTrue(restored.recoveredItem().isEmpty());
        assertEquals(0, restored.handoffItem(new ItemStack(Items.DIAMOND), "retry", () -> fail("unknown replay")));

        first.sharedItemReceipt(5);
        second.sharedItemReceipt(2);
        assertEquals(request, first.sequence());
        assertEquals(request, second.sequence());
        assertEquals(2, second.amount());
        assertEquals(2, second.sourceDebitPending());
        var knownSnapshot = second.save(registries);
        var knownRestored = custody();
        knownRestored.load(registries, knownSnapshot);
        assertEquals(knownSnapshot, knownRestored.save(registries));
        assertTrue(knownRestored.blocked());
        first.confirmSourceDebit(5);
        second.confirmSourceDebit(2);
        knownRestored.confirmSourceSettled(request);
        assertFalse(first.blocked());
        assertFalse(second.blocked());
        assertFalse(knownRestored.blocked());

        restored.confirmSourceSettled(request);
        restored.resolve(request, 1);
        assertEquals(4, restored.recoveredItem().getCount());
        assertEquals("原身份", restored.recoveredItem().getHoverName().getString());
    }

    @Test
    void sharedUnknownContributionsRemainSeparateUntilEachSourceIsSettled() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var request = UUID.randomUUID();
        var first = custody();
        var second = custody();
        assertTrue(first.beginSharedItem(request, new ItemStack(Items.IRON_INGOT), 4, "ae2-shared-item"));
        assertTrue(second.beginSharedItem(request, new ItemStack(Items.IRON_INGOT), 6, "ae2-shared-item"));
        var failure = new IllegalStateException("mount accepted a prefix then failed");
        first.sharedItemUncertain(failure);
        second.sharedItemUncertain(failure);
        first.confirmSourceDebit(4);
        second.markSourceDebitUnknown(new IllegalStateException("source debit callback failed"));
        assertTrue(first.blocked());
        assertTrue(second.blocked());
        assertEquals(4, first.amount());
        assertEquals(6, second.amount());
        assertThrows(IllegalStateException.class, () -> second.confirmSourceDebit(6));
        assertThrows(IllegalStateException.class, () -> second.resolve(request, 0));
        var saved = second.save(registries);
        var restored = custody();
        restored.load(registries, saved);
        assertEquals(saved, restored.save(registries));
        assertTrue(restored.sourceDebitUnknown());
        restored.confirmSourceSettled(request);
        first.resolve(request, 3);
        restored.resolve(request, 0);
        assertEquals(1, first.recoveredItem().getCount());
        assertEquals(6, restored.recoveredItem().getCount());
    }

    @Test
    void rejectedSharedRequestReleasesGuardsWithoutAStoredAsset() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var retained = custody();
        assertTrue(retained.beginSharedItem(UUID.randomUUID(), new ItemStack(Items.DIAMOND), 12, "ae2-shared-item"));
        retained.sharedItemReceipt(0);
        assertFalse(retained.blocked());
        assertEquals(0, retained.sourceDebitPending());
        assertTrue(retained.save(registries).isEmpty());
        assertEquals(2, retained.handoffItem(new ItemStack(Items.DIAMOND, 2), "normal", () -> 2));
    }

    @Test
    void sharedReceiptNotificationsCannotReplaceOrSettleTheExecutingSource() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var owner = new OutputCustody[1];
        var check = new boolean[1];
        var snapshots = new ArrayList<CompoundTag>();
        owner[0] = new OutputCustody(() -> {
            if (!check[0]) return;
            snapshots.add(owner[0].save(registries));
            assertThrows(IllegalStateException.class, () -> owner[0].load(registries, new CompoundTag()));
            assertThrows(IllegalStateException.class, () -> owner[0].confirmSourceDebit(1));
            assertThrows(IllegalStateException.class, () -> owner[0].confirmSourceSettled(owner[0].sequence()));
            throw new IllegalStateException("notification failed");
        });
        assertTrue(owner[0].beginSharedItem(UUID.randomUUID(), new ItemStack(Items.DIAMOND), 5, "shared"));
        check[0] = true;
        assertThrows(IllegalStateException.class, () -> owner[0].sharedItemReceipt(3));
        assertEquals(3, owner[0].sourceDebitPending());
        assertEquals(3, snapshots.getFirst().getLong("source_debit_pending"));
        check[0] = false;
        owner[0].confirmSourceDebit(3);
        assertFalse(owner[0].blocked());

        assertTrue(owner[0].beginSharedItem(UUID.randomUUID(), new ItemStack(Items.DIAMOND), 7, "shared"));
        check[0] = true;
        assertThrows(IllegalStateException.class,
                () -> owner[0].sharedItemUncertain(new IllegalStateException("external unknown")));
        assertEquals(7, owner[0].sourceDebitPending());
        check[0] = false;
        owner[0].confirmSourceDebit(7);
        assertTrue(owner[0].blocked());
        assertEquals(7, owner[0].amount());
        assertTrue(owner[0].recoveredItem().isEmpty());
    }

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
                .thenAnswer(call -> {
                    when(second.getStack()).thenReturn(new ItemStack(Items.GOLD_INGOT, 4));
                    return new ItemStack(Items.GOLD_INGOT, 4);
                });
        var retained = custody();
        assertEquals(10, LocalOutputTransfer.insert(java.util.List.of(first, second), source, retained));
        assertEquals(7, retained.localItem(1).amount());
        assertEquals(10, source.getCount());
        assertTrue(retained.blocked());
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("外部后续修改"));
        retained.localItem(1).resolve(retained.localItem(1).sequence(), 0);
        assertEquals("原始物品", retained.localItem(1).recoveredItem()
                .get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString());
        assertEquals(7, retained.localItem(1).recoveredItem().getCount());
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
            assertEquals(throwsAfterWrite ? 100 : 40, FastFluidEjector.transfer(tank, target, custody, 0));
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
        for (boolean sourceFailure : new boolean[]{false, true}) {
            var tank = mock(IExtendedFluidTank.class);
            var target = mock(IFluidHandler.class);
            boolean[] failed = {true};
            when(tank.extract(anyInt(), eq(Action.SIMULATE), any())).thenAnswer(call -> {
                if (sourceFailure && failed[0]) throw new IllegalStateException("source simulation failed");
                return new FluidStack(Fluids.WATER, 100);
            });
            when(tank.extract(anyInt(), eq(Action.EXECUTE), any())).thenReturn(new FluidStack(Fluids.WATER, 100));
            when(target.fill(any(), any())).thenAnswer(call -> {
                if (!sourceFailure && failed[0]) throw new IllegalStateException("target simulation failed");
                return 100;
            });
            var custody = custody();
            assertEquals(0, FastFluidEjector.transfer(tank, target, custody, 0));
            verify(tank, never()).extract(anyInt(), eq(Action.EXECUTE), any());
            assertFalse(custody.blocked());
            failed[0] = false;
            assertEquals(100, FastFluidEjector.transfer(tank, target, custody, 0));
            assertFalse(custody.blocked());
        }
    }

    @Test
    void localCallbackFailuresUseObservedZeroPartialAndFullReceipts() {
        for (int accepted : new int[]{0, 3, 6}) {
            var items = new ItemStack[]{new ItemStack(Items.DIAMOND, 5)};
            var itemOwner = custody();
            assertEquals(accepted, itemOwner.localItem(0).handoffLocalItem(
                    new ItemStack(Items.DIAMOND), 6, items[0], "local-item", () -> items[0], () -> {
                        items[0] = new ItemStack(Items.DIAMOND, 5 + accepted);
                        throw new IllegalStateException("listener failed after local item write");
                    }));
            assertEquals(5 + accepted, items[0].getCount());
            assertFalse(itemOwner.blocked());
            var fluids = new FluidStack[]{new FluidStack(Fluids.WATER, 5)};
            var fluidOwner = custody();
            assertEquals(accepted, fluidOwner.localFluid(0).handoffLocalFluid(
                    new FluidStack(Fluids.WATER, 1), 6, fluids[0], "local-fluid", () -> fluids[0], () -> {
                        fluids[0] = new FluidStack(Fluids.WATER, 5 + accepted);
                        throw new IllegalStateException("listener failed after local fluid write");
                    }));
            assertEquals(5 + accepted, fluids[0].getAmount());
            assertFalse(fluidOwner.blocked());
        }
    }

    @Test
    void unreadableLocalReceiptsVerifyAndRefillThroughTheComponentTickAfterReload() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var state = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(
                "productivebeesgenesis:elite_mek_apiary_factory")).defaultBlockState();
        var tile = new com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory(
                net.minecraft.core.Holder.direct(state.getBlock()), net.minecraft.core.BlockPos.ZERO, state);
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.registryAccess()).thenReturn(registries);
        when(level.getBlockState(any())).thenReturn(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        tile.setLevel(level);
        var owner = OutputCustody.of(tile);
        var slot = tile.getOutputSlots().getFirst();
        var tank = tile.getFluidTank();
        assertEquals(256, slot.getLimit(new ItemStack(Items.DIAMOND)));
        slot.setStack(new ItemStack(Items.DIAMOND, 128));
        assertEquals(128, slot.getCount());
        assertEquals(10, owner.localItem(0).handoffLocalItem(new ItemStack(Items.DIAMOND), 10,
                slot.getStack(), "machine-slot:0", () -> { throw new IllegalStateException("cannot read receipt"); }, () -> {
                    slot.growStack(3, Action.EXECUTE);
                    throw new IllegalStateException("local item callback failed");
                }));
        assertEquals(10, owner.localFluid(0).handoffLocalFluid(new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 1), 10,
                FluidStack.EMPTY, "apiary-tank", () -> { throw new IllegalStateException("cannot read receipt"); }, () -> {
                    tank.setStack(new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 4));
                    throw new IllegalStateException("local fluid callback failed");
                }));
        assertTrue(owner.localItemBlocked(0));
        assertTrue(owner.localFluidBlocked(0));
        assertTrue(owner.blocked());
        assertFalse(owner.externalOutputBlocked());
        assertTrue(slot.extractItem(131, Action.SIMULATE, AutomationType.EXTERNAL).isEmpty());
        assertTrue(slot.extractItem(131, Action.EXECUTE, AutomationType.EXTERNAL).isEmpty());
        assertTrue(tank.extract(4, Action.SIMULATE, AutomationType.EXTERNAL).isEmpty());
        assertTrue(tank.extract(4, Action.EXECUTE, AutomationType.EXTERNAL).isEmpty());
        assertEquals(2, owner.handoffItem(new ItemStack(Items.EMERALD, 2), "healthy-external", () -> 2));
        var healthyTank = mock(IExtendedFluidTank.class);
        when(healthyTank.extract(anyInt(), eq(Action.SIMULATE), any())).thenReturn(new FluidStack(Fluids.WATER, 6));
        when(healthyTank.extract(anyInt(), eq(Action.EXECUTE), any())).thenReturn(new FluidStack(Fluids.WATER, 6));
        var healthyTarget = mock(IFluidHandler.class);
        when(healthyTarget.fill(any(), any())).thenReturn(6);
        assertEquals(6, FastFluidEjector.transfer(healthyTank, healthyTarget, owner, 1));
        assertFalse(owner.externalOutputBlocked());
        var saved = owner.save(registries);
        var itemEvidence = saved.getList("local", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getCompound("request");
        assertEquals(128, itemEvidence.getInt("local_before_count"));
        assertEquals(1, itemEvidence.getCompound("local_before").getInt("count"));
        for (boolean wrongType : new boolean[]{false, true}) {
            var invalidCount = saved.copy();
            var invalidEvidence = invalidCount.getList("local", net.minecraft.nbt.Tag.TAG_COMPOUND)
                    .getCompound(0).getCompound("request");
            if (wrongType) invalidEvidence.putLong("local_before_count", 128);
            else invalidEvidence.putInt("local_before_count", 0);
            var invalidOwner = custody();
            invalidOwner.load(registries, invalidCount);
            assertTrue(invalidOwner.localItemBlocked(0));
            assertEquals(invalidCount, invalidOwner.save(registries));
        }
        owner.load(registries, saved);
        assertEquals(saved, owner.save(registries));
        var component = tile.getComponents().stream()
                .filter(GenesisTileComponentEjector.class::isInstance)
                .map(GenesisTileComponentEjector.class::cast).findFirst().orElseThrow();
        component.tickServer();
        assertFalse(owner.blocked());
        assertEquals(138, slot.getCount());
        assertEquals(10, tank.getFluidAmount());
        assertTrue(owner.save(registries).isEmpty());
        component.tickServer();
        assertEquals(138, slot.getCount());
        assertEquals(10, tank.getFluidAmount());
        var smallBefore = custody();
        assertEquals(4, smallBefore.localItem(1).handoffLocalItem(new ItemStack(Items.EMERALD), 4,
                new ItemStack(Items.EMERALD, 5), "old-v4-local",
                () -> { throw new IllegalStateException("old v4 receipt unreadable"); },
                () -> { throw new IllegalStateException("old v4 callback failed"); }));
        var oldV4 = smallBefore.save(registries);
        var oldEvidence = oldV4.getList("local", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getCompound("request");
        oldEvidence.remove("local_before_count");
        oldEvidence.getCompound("local_before").putInt("count", 5);
        var oldRestored = custody();
        oldRestored.load(registries, oldV4);
        oldRestored.localItem(1).recoverLocalItem(() -> new ItemStack(Items.EMERALD, 9),
                stack -> fail("legacy v4 receipt already accepted"));
        assertFalse(oldRestored.blocked());
        assertTrue(oldRestored.save(registries).isEmpty());
        var legacy = owner.localItem(1);
        assertEquals(5, legacy.handoffItem(new ItemStack(Items.EMERALD, 5), "old-local-unknown",
                () -> { throw new IllegalStateException("old request has no inventory evidence"); }));
        legacy.recoverLocalItem(() -> ItemStack.EMPTY, stack -> fail("old unknown cannot be retried"));
        assertEquals(5, legacy.amount());
        assertTrue(legacy.recoveredItem().isEmpty());
    }

    @Test
    void notificationFailuresBeforeAndAfterReceiptDoNotLeaveMachineBlockedOrReplay() {
        boolean[] extractNotificationFails = {false};
        for (boolean bulk : new boolean[]{false, true}) {
            BasicInventorySlot slot = bulk ? TieredOutputInventorySlot.at(() -> 4, () -> {
                if (extractNotificationFails[0]) throw new IllegalStateException("bulk extraction listener failed");
            }, 0, 0) : BasicInventorySlot.at(() -> {
                if (extractNotificationFails[0]) throw new IllegalStateException("extraction listener failed");
            }, 0, 0);
            ((TieredInputSlot) slot).productivebeesgenesis$markOwnSlot();
            int total = bulk ? 130 : 6;
            extractNotificationFails[0] = false;
            slot.setStack(new ItemStack(Items.DIAMOND, total));
            extractNotificationFails[0] = true;
            ItemStack extracted = slot.extractItem(total - 2, Action.EXECUTE, AutomationType.EXTERNAL);
            assertEquals(total - 2, extracted.getCount());
            assertEquals(2, slot.getCount());
            assertEquals(2, slot.extractItem(2, Action.SIMULATE, AutomationType.EXTERNAL).getCount());
            assertEquals(2, slot.getCount());
            extractNotificationFails[0] = false;
            assertEquals(2, slot.extractItem(2, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
            assertTrue(slot.isEmpty());
        }

        var outputTank = new OutputFluidTank(1000, (stack, automation) -> true,
                (stack, automation) -> automation == AutomationType.INTERNAL, stack -> true, () -> {
                    if (extractNotificationFails[0]) throw new IllegalStateException("fluid extraction listener failed");
                });
        outputTank.setStack(new FluidStack(Fluids.WATER, 100));
        extractNotificationFails[0] = true;
        assertEquals(40, outputTank.extract(40, Action.EXECUTE, AutomationType.EXTERNAL).getAmount());
        assertEquals(60, outputTank.getFluidAmount());
        assertEquals(20, outputTank.extractAmount(20));
        assertEquals(40, outputTank.getFluidAmount());
        var target = mock(IFluidHandler.class);
        when(target.fill(any(), eq(IFluidHandler.FluidAction.SIMULATE))).thenReturn(40);
        when(target.fill(any(), eq(IFluidHandler.FluidAction.EXECUTE))).thenReturn(40);
        var sourceOwner = custody();
        assertEquals(40, FastFluidEjector.transfer(outputTank, target, sourceOwner, 0));
        assertTrue(outputTank.isEmpty());
        assertFalse(sourceOwner.blocked());
        verify(target, times(1)).fill(any(), eq(IFluidHandler.FluidAction.EXECUTE));
        extractNotificationFails[0] = false;
        outputTank.setStack(new FluidStack(Fluids.WATER, 10));
        assertEquals(10, outputTank.extract(10, Action.EXECUTE, AutomationType.EXTERNAL).getAmount());
        assertTrue(outputTank.isEmpty());

        boolean[] preparationFails = {true};
        var preparationOwner = new OutputCustody(() -> {
            if (preparationFails[0]) throw new IllegalStateException("handoff preparation notification failed");
        });
        outputTank.setStack(new FluidStack(Fluids.WATER, 40));
        assertEquals(0, FastFluidEjector.transfer(outputTank, target, preparationOwner, 0));
        assertTrue(outputTank.isEmpty());
        assertEquals(40, preparationOwner.recoveredFluid().getAmount());
        verify(target, times(1)).fill(any(), eq(IFluidHandler.FluidAction.EXECUTE));
        preparationFails[0] = false;
        preparationOwner.recoverFluid(stack -> stack.getAmount()
                - outputTank.insert(stack, Action.EXECUTE, AutomationType.INTERNAL).getAmount());
        assertEquals(40, outputTank.getFluidAmount());
        assertFalse(preparationOwner.blocked());
        assertEquals(40, FastFluidEjector.transfer(outputTank, target, preparationOwner, 0));
        assertTrue(outputTank.isEmpty());
        assertFalse(preparationOwner.blocked());
        verify(target, times(2)).fill(any(), eq(IFluidHandler.FluidAction.EXECUTE));

        int[] preparationCalls = {0};
        var prefixOwner = new OutputCustody(() -> {
            if (++preparationCalls[0] == 3) throw new IllegalStateException("second handoff preparation failed");
        });
        var prefixTarget = mock(net.neoforged.neoforge.items.IItemHandler.class);
        when(prefixTarget.getSlots()).thenReturn(2);
        when(prefixTarget.getStackInSlot(anyInt())).thenReturn(ItemStack.EMPTY);
        when(prefixTarget.insertItem(eq(0), any(), eq(false))).thenAnswer(call -> {
            ItemStack offered = call.getArgument(1);
            return offered.copyWithCount(offered.getCount() - 2);
        });
        assertEquals(4, ItemPushHelper.insert(prefixTarget, new ItemStack(Items.DIAMOND, 6), prefixOwner,
                "prepared-prefix").getCount());
        verify(prefixTarget, times(1)).insertItem(eq(0), any(), eq(false));
        verify(prefixTarget, never()).insertItem(eq(1), any(), eq(false));
        assertFalse(prefixOwner.blocked());

        boolean[] failNotification = {true};
        var owner = new OutputCustody(() -> {
            if (failNotification[0]) throw new IllegalStateException("save notification failed");
        });
        assertThrows(IllegalStateException.class, () -> owner.handoffItem(
                new ItemStack(Items.DIAMOND, 6), "before-insert", () -> fail("receiver must not execute")));
        assertFalse(owner.blocked());
        assertThrows(IllegalStateException.class, () -> owner.handoffFluidFromTank(
                new FluidStack(Fluids.WATER, 1), 6, "before-fluid", () -> fail("fluid receiver must not execute")));
        assertFalse(owner.blocked());
        assertThrows(IllegalStateException.class, () -> owner.beginSharedItem(UUID.randomUUID(),
                new ItemStack(Items.DIAMOND), 6, "before-shared"));
        assertFalse(owner.blocked());
        failNotification[0] = false;
        assertEquals(0, owner.handoffFluidFromTank(new FluidStack(Fluids.WATER, 1), 6, "fluid-zero", () -> {
            failNotification[0] = true;
            return 0;
        }));
        assertFalse(owner.blocked());
        assertEquals(0, owner.sourceDebitPending());
        failNotification[0] = false;
        assertEquals(6, owner.handoffItem(new ItemStack(Items.DIAMOND, 6), "accepted", () -> {
            failNotification[0] = true;
            return 6;
        }));
        assertFalse(owner.blocked());
        failNotification[0] = false;
        assertEquals(2, owner.handoffItem(new ItemStack(Items.IRON_INGOT, 2), "next", () -> 2));
        assertFalse(owner.blocked());
        assertEquals(3, owner.handoffItem(new ItemStack(Items.EMERALD, 3), "unknown", () -> {
            failNotification[0] = true;
            throw new IllegalStateException("receiver changed before failure");
        }));
        assertTrue(owner.blocked());
        assertEquals(3, owner.amount());
        assertEquals(0, owner.handoffItem(new ItemStack(Items.EMERALD, 3), "replay", () -> fail("no replay")));
    }

    @Test
    void externalUnknownStillAllowsIndependentLocalItemsAndFluidAfterReload() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var owner = custody();
        assertEquals(6, owner.handoffItem(new ItemStack(Items.DIAMOND, 6), "external", () -> {
            throw new IllegalStateException("receiver result unknown");
        }));
        var request = owner.sequence();
        var first = mock(mekanism.api.inventory.IInventorySlot.class);
        when(first.getStack()).thenReturn(ItemStack.EMPTY);
        when(first.insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenReturn(ItemStack.EMPTY);
        assertEquals(4, LocalOutputTransfer.insert(java.util.List.of(first), new ItemStack(Items.IRON_INGOT, 4), owner));
        assertEquals(8, owner.localFluid(0).handoffFluid(new FluidStack(Fluids.WATER, 8), 8, "local-tank", () -> 8));
        assertEquals(6, owner.amount());
        assertEquals(request, owner.sequence());
        assertFalse(owner.localOutputBlocked());
        var restored = custody();
        restored.load(registries, owner.save(registries));
        assertFalse(restored.localOutputBlocked());
        assertEquals(2, LocalOutputTransfer.insert(java.util.List.of(first), new ItemStack(Items.IRON_INGOT, 2), restored));
        assertEquals(0, restored.handoffItem(new ItemStack(Items.DIAMOND, 6), "retry", () -> fail("external replay")));
        assertEquals(request, restored.sequence());
        assertEquals(6, restored.amount());
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
