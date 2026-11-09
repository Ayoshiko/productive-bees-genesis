package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.init.ModFluids;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
