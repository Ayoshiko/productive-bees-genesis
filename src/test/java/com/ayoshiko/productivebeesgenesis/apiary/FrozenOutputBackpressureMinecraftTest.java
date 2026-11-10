package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FluidPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import cy.jdkdigital.productivebees.init.ModFluids;
import mekanism.api.Action;
import mekanism.api.IContentsListener;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class FrozenOutputBackpressureMinecraftTest {
    private static final ApiaryBatchUpgradeSnapshot LOCAL = new ApiaryBatchUpgradeSnapshot(
            0, false, false, 1, false, false, false, false, false, false);

    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    private record Fixture(TileEntityMekApiary tile, BeeProduceProcessor processor, BasicInventorySlot output) {}

    private static Fixture fixture() {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
        var tile = spy(new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state));
        var output = spy(BasicInventorySlot.at((IContentsListener) tile::onOutputSlotContentsChanged, 0, 0));
        doReturn(List.of(output)).when(tile).getOutputSlots();
        doReturn(false).when(tile).shouldHoldForCentrifuge(any());
        for (int index = 0; index < ApiaryOutputBuffer.MAX_BUFFER_GROUPS; index++) {
            var held = new ItemStack(Items.IRON_INGOT, 1);
            held.set(DataComponents.CUSTOM_NAME, Component.literal("held-" + index));
            assertEquals(1, tile.getOutputBuffer().accept(held));
        }
        output.setStack(new ItemStack(Items.IRON_INGOT, 64));
        return new Fixture(tile, new BeeProduceProcessor(mock(ApiaryUpgradeHandler.class), tile), output);
    }

    @Test
    void rejectionDoesNotDirtyTheOwnerAndSpaceChangeResumesImmediately() {
        var fixture = fixture();
        var processor = fixture.processor();
        processor.production.freeze(new BeeProduceProcessor.Generated(
                List.of(new ItemStack(Items.DIAMOND, 4)), FluidStack.EMPTY, 0));
        clearInvocations(fixture.tile());
        assertFalse(processor.flushFrozen(LOCAL));
        verify(fixture.tile(), never()).setChanged();
        fixture.output().setStack(ItemStack.EMPTY);
        assertTrue(processor.flushFrozen(LOCAL));
        assertEquals(4, fixture.output().getCount());
        assertEquals(Items.DIAMOND, fixture.output().getStack().getItem());
        assertFalse(processor.production.hasFrozen());
    }

    @Test
    void knownPartialReceiptRemainsOwnedAndDoesNotDelayTheNextAttempt() {
        var fixture = fixture();
        fixture.output().setStack(new ItemStack(Items.DIAMOND, 62));
        fixture.processor().production.freeze(new BeeProduceProcessor.Generated(
                List.of(new ItemStack(Items.DIAMOND, 4)), FluidStack.EMPTY, 0));
        assertFalse(fixture.processor().flushFrozen(LOCAL));
        assertEquals(64, fixture.output().getCount());
        assertEquals(2, fixture.processor().production.items().getFirst().getCount());
        clearInvocations(fixture.output());
        assertFalse(fixture.processor().flushFrozen(LOCAL));
        verify(fixture.output(), atLeastOnce()).getStack();
        assertEquals(2, fixture.processor().production.items().getFirst().getCount());
        fixture.output().setStack(ItemStack.EMPTY);
        assertTrue(fixture.processor().flushFrozen(LOCAL));
        assertEquals(2, fixture.output().getCount());
    }

    @Test
    void loadingOrFreezingANewBatchDoesNotReuseThePreviousRejection() {
        var fixture = fixture();
        var production = fixture.processor().production;
        production.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.DIAMOND, 4)), FluidStack.EMPTY, 0));
        assertFalse(fixture.processor().flushFrozen(LOCAL));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var snapshot = production.save(registries);
        long version = production.frozenVersion();
        production.load(registries, snapshot);
        assertNotEquals(version, production.frozenVersion());
        clearInvocations(fixture.output());
        assertFalse(fixture.processor().flushFrozen(LOCAL));
        verify(fixture.output(), atLeastOnce()).getStack();
        production.load(registries, new CompoundTag());
        production.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.EMERALD, 3)), FluidStack.EMPTY, 0));
        fixture.output().setStack(ItemStack.EMPTY);
        assertTrue(fixture.processor().flushFrozen(LOCAL));
        assertEquals(Items.EMERALD, fixture.output().getStack().getItem());
        assertEquals(3, fixture.output().getCount());
    }

    @Test
    void laterRoutingFailurePreservesAndMarksTheAlreadyAcceptedPrefix() {
        var fixture = fixture();
        fixture.output().setStack(ItemStack.EMPTY);
        fixture.processor().production.freeze(new BeeProduceProcessor.Generated(
                List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.EMERALD, 3)), FluidStack.EMPTY, 0));
        doThrow(new IllegalStateException("later route unavailable")).when(fixture.tile())
                .shouldHoldForCentrifuge(argThat(stack -> stack.getItem() == Items.EMERALD));
        clearInvocations(fixture.tile());
        var failure = assertThrows(IllegalStateException.class, () -> fixture.processor().flushFrozen(LOCAL));
        assertEquals("later route unavailable", failure.getMessage());
        assertEquals(Items.DIAMOND, fixture.output().getStack().getItem());
        assertEquals(4, fixture.output().getCount());
        assertEquals(1, fixture.processor().production.items().size());
        assertEquals(Items.EMERALD, fixture.processor().production.items().getFirst().getItem());
        assertEquals(3, fixture.processor().production.items().getFirst().getCount());
        verify(fixture.tile()).setChanged();
    }

    @Test
    void fluidSpaceChangeResumesWithoutChargingOrDirtyingARejectedSuffix() {
        var fixture = fixture();
        var tank = fixture.tile().getFluidTank();
        tank.setStack(new FluidStack(ModFluids.HONEY.get(), tank.getCapacity()));
        fixture.processor().production.freeze(new BeeProduceProcessor.Generated(
                List.of(), new FluidStack(ModFluids.HONEY.get(), 1), 250));
        clearInvocations(fixture.tile());
        assertFalse(fixture.processor().flushFrozen(LOCAL));
        assertEquals(250, fixture.processor().production.fluidAmount());
        verify(fixture.tile(), never()).setChanged();
        tank.setStack(FluidStack.EMPTY);
        assertTrue(fixture.processor().flushFrozen(LOCAL));
        assertEquals(250, tank.getFluidAmount());
        assertEquals(0, fixture.processor().production.fluidAmount());
    }

    @Test
    void fifteenTankBatchUsesLocalCustodyWithoutEnablingDirectOutput() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        var tank = tile.getFluidTank();
        long amount = 15L * tank.getCapacity();
        tile.productionState().freeze(new BeeProduceProcessor.Generated(List.of(), honey(), amount));
        long[] received = {0};
        try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
            receiveAllLocalFluid(output, amount, received);
            assertTrue(tile.getProduceProcessor().flushFrozen(LOCAL));
            assertEquals(0, tile.productionState().fluidAmount());
            assertEquals(tank.getCapacity(), tank.getFluidAmount());
            assertEquals(amount - tank.getCapacity(), received[0]);
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile), times(14));
            output.verify(() -> Ae2FluidPusher.pushGeneratedFluid(any(), any(), anyLong()), never());
            assertFalse(tile.isDirectAeOutputEnabled());
            assertFalse(OutputCustody.of(tile).blocked());
            tile.productivebeesgenesis$onLocalFluidOutputCommitted();
            assertEquals(amount, received[0]);
            assertTrue(tank.isEmpty());
        }
    }

    @Test
    void pressureDrainBudgetIsSharedByLegacyAndFrozenWorkWithinOneRealTick() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        int capacity = tile.getFluidTank().getCapacity();
        long amount = 38L * capacity;
        tile.setPendingHoneyFluid(35L * capacity, honey());
        tile.productionState().freeze(new BeeProduceProcessor.Generated(List.of(), honey(), 3L * capacity));
        long[] received = {0};
        try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
            receiveAllLocalFluid(output, amount, received);
            assertFalse(tile.getProduceProcessor().flushPendingFluid());
            assertEquals(16L * capacity, received[0]);
            assertEquals(18L * capacity, tile.getPendingHoneyFluidAmount());
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
            assertFalse(tile.getProduceProcessor().flushPendingFluid());
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile),
                    times(ApiaryFluidOutput.MAX_PRESSURE_DRAINS_PER_TICK));
            assertEquals(3L * capacity, tile.productionState().fluidAmount());
            when(fixture.level().getGameTime()).thenReturn(101L);
            assertTrue(tile.getProduceProcessor().flushFrozen(LOCAL));
            assertFalse(tile.getProduceProcessor().flushPendingFluid());
            assertEquals(32L * capacity, received[0]);
            assertEquals(5L * capacity, tile.getPendingHoneyFluidAmount());
            assertEquals(amount, received[0] + ownedFluid(tile));
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile),
                    times(2 * ApiaryFluidOutput.MAX_PRESSURE_DRAINS_PER_TICK));
        }
    }

    @Test
    void partialLocalDrainStopsAndPreservesTheExactSuffixAcrossRestore() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        var tank = tile.getFluidTank();
        long amount = 3L * tank.getCapacity();
        tile.productionState().freeze(new BeeProduceProcessor.Generated(List.of(), honey(), amount));
        long[] received = {0};
        try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
            output.when(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile)).thenAnswer(call -> {
                assertEquals(amount, ownedFluid(tile));
                received[0] = tank.shrinkStack(tank.getCapacity() / 2, Action.EXECUTE);
                return null;
            });
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
            assertEquals(2L * tank.getCapacity(), tile.productionState().fluidAmount());
            assertEquals(tank.getCapacity() / 2, tank.getFluidAmount());
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile), times(1));
            assertEquals(amount, received[0] + ownedFluid(tile));
            var restored = restoreFluidState(tile);
            assertEquals(amount, received[0] + ownedFluid(restored.tile()));
            receiveAllLocalFluid(output, amount, received);
            assertTrue(restored.tile().getProduceProcessor().flushFrozen(LOCAL));
            restored.tile().productivebeesgenesis$onLocalFluidOutputCommitted();
            assertEquals(amount, received[0]);
            assertEquals(0, ownedFluid(restored.tile()));
        }
    }

    @Test
    void refusedLocalDrainDoesNotRetryWithinTheSameTickOrLoseItsSuffix() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        long amount = 3L * tile.getFluidTank().getCapacity();
        tile.productionState().freeze(new BeeProduceProcessor.Generated(List.of(), honey(), amount));
        try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile), times(1));
            assertEquals(amount, ownedFluid(tile));
            assertEquals(2L * tile.getFluidTank().getCapacity(), tile.productionState().fluidAmount());
            assertFalse(OutputCustody.of(tile).blocked());
            restoreFluidState(tile);
        }
    }

    @Test
    void disabledAeFluidOutputRetainsLocalTankAndLegacySuffix() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        tile.productivebeesgenesis$setAeFluidOutputEnabled(false);
        long amount = 3L * tile.getFluidTank().getCapacity();
        tile.setPendingHoneyFluid(amount, honey());
        try (var integration = aeLoaded()) {
            assertFalse(tile.getProduceProcessor().flushPendingFluid());
            assertFalse(tile.getProduceProcessor().flushPendingFluid());
        }
        assertEquals(tile.getFluidTank().getCapacity(), tile.getFluidTank().getFluidAmount());
        assertEquals(2L * tile.getFluidTank().getCapacity(), tile.getPendingHoneyFluidAmount());
        assertFalse(OutputCustody.of(tile).blocked());
        assertEquals(amount, ownedFluid(tile));
        restoreFluidState(tile);
    }

    @Test
    void localDrainKeepsTheExistingAeFluidBackoff() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        long amount = 3L * tile.getFluidTank().getCapacity();
        tile.productionState().freeze(new BeeProduceProcessor.Generated(List.of(), honey(), amount));
        var backoff = tile.productivebeesgenesis$getAe2StateHolder().getPushState().getFluidBackoff();
        backoff.recordFailure(Long.MAX_VALUE / 2);
        long retry = backoff.getBackoffEndNanos();
        try (var integration = aeLoaded()) {
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
            assertFalse(tile.getProduceProcessor().flushFrozen(LOCAL));
        }
        assertEquals(retry, backoff.getBackoffEndNanos());
        assertEquals(1, backoff.getBackoffExponent());
        assertEquals(amount, ownedFluid(tile));
        assertEquals(tile.getFluidTank().getCapacity(), tile.getFluidTank().getFluidAmount());
    }

    @Test
    void localCommitUsesTheApiaryRouteInsteadOfTheCentrifugeHolderFlag() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        var holder = tile.productivebeesgenesis$getAe2StateHolder();
        holder.setCentrifugeDirectAeOutputEnabled(true);
        assertFalse(tile.isDirectAeOutputEnabled());
        try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
            tile.productivebeesgenesis$onLocalFluidOutputCommitted();
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile), times(1));
            holder.setCentrifugeDirectAeOutputEnabled(false);
            tile.setDirectAeOutputEnabled(true);
            tile.productivebeesgenesis$onLocalFluidOutputCommitted();
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile), times(1));
        }
    }

    @Test
    void unknownTankReceiptOwnsOnlyTheHandedPrefixAndNeverReplaysAfterRestore() {
        for (boolean legacy : new boolean[]{false, true}) {
            var fixture = fluidFixture();
            var tile = fixture.tile();
            var tank = tile.getFluidTank();
            long amount = 3L * tank.getCapacity();
            if (legacy) {
                tile.setPendingHoneyFluid(amount, honey());
            } else {
                tile.productionState().freeze(new BeeProduceProcessor.Generated(List.of(), honey(), amount));
            }
            try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
                output.when(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile)).thenAnswer(call -> {
                    assertEquals(amount, ownedFluid(tile));
                    var custody = OutputCustody.of(tile);
                    long handed = custody.handoffFluidFromTank(tank.getFluid(), tank.getFluidAmount(),
                            "test-unknown-ae", () -> {
                                throw new IllegalStateException("Unknown external receipt");
                            });
                    custody.confirmSourceDebit(tank.shrinkStack((int) handed, Action.EXECUTE));
                    return null;
                });
                boolean completed = legacy ? tile.getProduceProcessor().flushPendingFluid()
                        : tile.getProduceProcessor().flushFrozen(LOCAL);
                assertFalse(completed);
                var custody = OutputCustody.of(tile);
                assertTrue(custody.blocked());
                assertEquals(tank.getCapacity(), custody.amount());
                assertEquals(0, custody.sourceDebitPending());
                assertTrue(tank.isEmpty());
                assertEquals(amount, ownedFluid(tile) + custody.amount());
                var restored = restoreFluidState(tile);
                var restoredTile = restored.tile();
                assertTrue(OutputCustody.of(restoredTile).blocked());
                boolean resumed = legacy ? restoredTile.getProduceProcessor().flushPendingFluid()
                        : restoredTile.getProduceProcessor().flushFrozen(LOCAL);
                assertFalse(resumed);
                assertEquals(amount, ownedFluid(restoredTile) + OutputCustody.of(restoredTile).amount());
                output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(any()), times(1));
            }
        }
    }

    @Test
    void laterDrainExceptionCannotRestoreAnAlreadyCommittedLegacyPrefix() {
        var fixture = fluidFixture();
        var tile = fixture.tile();
        long amount = 3L * tile.getFluidTank().getCapacity();
        tile.setPendingHoneyFluid(amount, honey());
        try (var integration = aeLoaded(); var output = mockStatic(Ae2FluidPusher.class)) {
            output.when(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile))
                    .thenThrow(new IllegalStateException("Drain unavailable before external insertion"));
            var error = assertThrows(IllegalStateException.class, tile.getProduceProcessor()::flushPendingFluid);
            assertEquals("Drain unavailable before external insertion", error.getMessage());
            assertEquals(2L * tile.getFluidTank().getCapacity(), tile.getPendingHoneyFluidAmount());
            assertEquals(amount, ownedFluid(tile));
            assertFalse(tile.getProduceProcessor().flushPendingFluid());
            output.verify(() -> Ae2FluidPusher.pushLocalTankContentsNow(tile), times(1));
            restoreFluidState(tile);
        }
    }

    private record FluidFixture(TileEntityMekApiary tile, ServerLevel level) {}

    private static FluidFixture fluidFixture() {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                "productivebeesgenesis:infinite_multiversal_emextra_mek_apiary_factory")).defaultBlockState();
        var tile = new TileEntityEMExtraMekApiaryFactory(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
        var level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);
        tile.setLevel(level);
        assertEquals(60, tile.getBeeSlotCount());
        assertEquals(3_072_000, tile.getFluidTank().getCapacity());
        return new FluidFixture(tile, level);
    }

    private static FluidStack honey() {
        return new FluidStack(ModFluids.HONEY.get(), 1);
    }

    private static MockedStatic<Ae2IntegrationLoader> aeLoaded() {
        var integration = mockStatic(Ae2IntegrationLoader.class);
        integration.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(true);
        return integration;
    }

    private static long ownedFluid(TileEntityMekApiary tile) {
        return tile.productionState().fluidAmount() + tile.getPendingHoneyFluidAmount()
                + tile.getFluidTank().getFluidAmount();
    }

    private static void receiveAllLocalFluid(MockedStatic<Ae2FluidPusher> output, long amount, long[] received) {
        output.when(() -> Ae2FluidPusher.pushLocalTankContentsNow(any())).thenAnswer(call -> {
            TileEntityMekApiary tile = call.getArgument(0);
            var tank = tile.getFluidTank();
            assertEquals(amount, received[0] + ownedFluid(tile));
            assertFalse(OutputCustody.of(tile).blocked());
            assertFalse(tank.isEmpty());
            received[0] += tank.shrinkStack(tank.getFluidAmount(), Action.EXECUTE);
            assertEquals(amount, received[0] + ownedFluid(tile));
            return null;
        });
    }

    private static FluidFixture restoreFluidState(TileEntityMekApiary source) {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var snapshot = new CompoundTag();
        source.nbtSerializer().saveApiaryState(snapshot, registries);
        var custodySnapshot = OutputCustody.of(source).save(registries);
        var restored = fluidFixture();
        restored.tile().nbtSerializer().loadApiaryState(snapshot, registries);
        OutputCustody.of(restored.tile()).load(registries, custodySnapshot);
        var roundTrip = new CompoundTag();
        restored.tile().nbtSerializer().saveApiaryState(roundTrip, registries);
        assertEquals(snapshot, roundTrip);
        assertEquals(custodySnapshot, OutputCustody.of(restored.tile()).save(registries));
        return restored;
    }

}
