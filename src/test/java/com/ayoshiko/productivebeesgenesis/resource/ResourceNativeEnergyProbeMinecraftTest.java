package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.common.integration.energy.forgeenergy.ForgeEnergyIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceNativeEnergyProbeMinecraftTest {
    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void actualMekanismConversionDoesNotTurnAWorkingReceiverIntoAFullOutput() {
        var handler = mock(IStrictEnergyHandler.class);
        var receiver = new ForgeEnergyIntegration(handler);
        when(handler.insertEnergy(anyLong(), eq(Action.SIMULATE))).thenReturn(0L);
        assertEquals(0, receiver.receiveEnergy(1, true));
        assertTrue(receiver.receiveEnergy(100, true) > 0);
        clearInvocations(handler);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        var host = mock(BlockEntity.class);
        var state = mock(ResourceHiveHost.class);
        when(host.getLevel()).thenReturn(level);
        when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(level.getServer()).thenReturn(server);
        when(level.getChunkSource()).thenReturn(mock(net.minecraft.server.level.ServerChunkCache.class));
        when(level.getChunkSource().hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(state.nativeOutput(host, ResourceNativeType.ENERGY, Direction.NORTH)).thenReturn(true);
        when(level.getCapability(Capabilities.EnergyStorage.BLOCK, BlockPos.ZERO.north(), Direction.SOUTH)).thenReturn(receiver);
        try (var hosts = mockStatic(ResourceHiveHost.class);
             var runtime = mockStatic(ResourceRuntime.class)) {
            hosts.when(() -> ResourceHiveHost.existing(host)).thenReturn(state);
            runtime.when(() -> ResourceRuntime.available(server, ResourceTypes.FE)).thenReturn(true);
            assertTrue(ResourceNativeOutput.canReceive(host, ResourceTypes.FE, Direction.NORTH));
            verify(handler, never()).insertEnergy(anyLong(), eq(Action.EXECUTE));
            when(handler.insertEnergy(anyLong(), eq(Action.SIMULATE))).thenAnswer(invocation -> invocation.getArgument(0));
            assertFalse(ResourceNativeOutput.canReceive(host, ResourceTypes.FE, Direction.NORTH));
            verify(handler, never()).insertEnergy(anyLong(), eq(Action.EXECUTE));
        }
    }
}
