package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccount;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccounts;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsSavedData;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2ResourceSchedulingMinecraftTest {
    @Test
    void resourceInitializationFailureCannotAbortOrdinaryOutputAndRetainsBackoff() {
        var fixture = new InitializationFixture();
        try (var runtime = mockStatic(ResourceRuntime.class);
             var hosts = mockStatic(ResourceHiveHost.class)) {
            runtime.when(() -> ResourceRuntime.accounts(fixture.server)).thenReturn(fixture.data);
            hosts.when(() -> ResourceHiveHost.existing(fixture.machine)).thenReturn(fixture.state);
            hosts.when(() -> ResourceHiveHost.ensureMechanical(fixture.machine))
                    .thenThrow(new IllegalStateException("invalid account during initialization"));
            when(fixture.level.getGameTime()).thenReturn(1L);
            assertDoesNotThrow(() -> Ae2OutputPusher.pushOutputs(fixture.host));
            verify(fixture.host).productivebeesgenesis$isOutputPushEnabled();
            when(fixture.level.getGameTime()).thenReturn(2L);
            assertDoesNotThrow(() -> Ae2OutputPusher.pushOutputs(fixture.host));
            hosts.verify(() -> ResourceHiveHost.ensureMechanical(fixture.machine), times(1));
            when(fixture.level.getGameTime()).thenReturn(41L);
            assertDoesNotThrow(() -> Ae2OutputPusher.pushOutputs(fixture.host));
            hosts.verify(() -> ResourceHiveHost.ensureMechanical(fixture.machine), times(2));
            assertEquals(fixture.saved, fixture.accounts.save());
            Ae2ResourceOutput.closeMachine(fixture.host);
        }
    }

    @Test
    void legacyResourceSwitchMigrationFailureCannotAbortOrdinaryOutput() {
        var fixture = new InitializationFixture();
        doThrow(new IllegalStateException("stale migration reference"))
                .when(fixture.state).migrateMeOutput(fixture.machine);
        try (var runtime = mockStatic(ResourceRuntime.class);
             var hosts = mockStatic(ResourceHiveHost.class)) {
            runtime.when(() -> ResourceRuntime.accounts(fixture.server)).thenReturn(fixture.data);
            hosts.when(() -> ResourceHiveHost.existing(fixture.machine)).thenReturn(fixture.state);
            hosts.when(() -> ResourceHiveHost.ensureMechanical(fixture.machine)).thenReturn(fixture.state);
            when(fixture.level.getGameTime()).thenReturn(1L);
            assertDoesNotThrow(() -> Ae2OutputPusher.pushOutputs(fixture.host));
            verify(fixture.host).productivebeesgenesis$isOutputPushEnabled();
            when(fixture.level.getGameTime()).thenReturn(2L);
            assertDoesNotThrow(() -> Ae2OutputPusher.pushOutputs(fixture.host));
            verify(fixture.state, times(1)).migrateMeOutput(fixture.machine);
            verify(fixture.state).meStatus("unavailable");
            assertEquals(fixture.saved, fixture.accounts.save());
            Ae2ResourceOutput.closeMachine(fixture.host);
        }
    }

    private static final class InitializationFixture {
        final ServerLevel level = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final BlockEntity machine = mock(BlockEntity.class, withSettings().extraInterfaces(IAe2OutputHostBase.class));
        final IAe2OutputHostBase host = (IAe2OutputHostBase) machine;
        final Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
        final ResourceHiveHost state = mock(ResourceHiveHost.class);
        final ResourceAccounts accounts = new ResourceAccounts(() -> {});
        final ResourceAccountsSavedData data = mock(ResourceAccountsSavedData.class);
        final CompoundTag saved;

        InitializationFixture() {
            when(machine.getLevel()).thenReturn(level);
            when(machine.getBlockPos()).thenReturn(BlockPos.ZERO);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(machine);
            when(level.getServer()).thenReturn(server);
            when(server.isSameThread()).thenReturn(true);
            when(server.overworld()).thenReturn(level);
            when(host.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
            when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
            var binding = new ResourceAccount.Binding(Level.OVERWORLD.location(), 0, ResourceLocation.parse("minecraft:furnace"));
            var account = accounts.create(java.util.UUID.randomUUID(), binding);
            when(state.reference()).thenReturn(account.reference());
            when(data.accounts()).thenReturn(accounts);
            saved = accounts.save();
        }
    }


    @Test
    void transientBindingFailureRecoversOnTheSameOutputInstanceBeforeRetryDeadline() {
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var machine = mock(net.minecraft.world.level.block.entity.BlockEntity.class,
                withSettings().extraInterfaces(IAe2OutputHostBase.class));
        var holder = new Ae2OutputStateHolder();
        when(machine.getLevel()).thenReturn(level);
        when(machine.getBlockPos()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(level.getBlockEntity(net.minecraft.core.BlockPos.ZERO)).thenReturn(machine);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(server.overworld()).thenReturn(level);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        doReturn(net.minecraft.world.level.block.entity.BlockEntityType.FURNACE).when(machine).getType();
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var state = new com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost();
        var raw = new net.minecraft.nbt.CompoundTag();
        raw.putInt("version", 1);
        raw.putUUID("source", java.util.UUID.randomUUID());
        raw.putLong("generation", 5);
        raw.putLong("capacity", 1000);
        raw.putBoolean("me_output", true);
        state.deserializeNBT(registries, raw);
        when(machine.getExistingDataOrNull(com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost.TYPE)).thenReturn(state);
        var accounts = new com.ayoshiko.productivebeesgenesis.resource.ResourceAccounts(() -> {});
        var data = mock(com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsSavedData.class);
        when(data.accounts()).thenReturn(accounts);
        var managed = mock(appeng.api.networking.IManagedGridNode.class);
        var node = mock(appeng.api.networking.IGridNode.class);
        var grid = mock(appeng.api.networking.IGrid.class);
        var storage = mock(appeng.api.networking.storage.IStorageService.class);
        when(managed.getNode()).thenReturn(node);
        when(node.isActive()).thenReturn(true);
        when(node.getGrid()).thenReturn(grid);
        when(grid.getStorageService()).thenReturn(storage);
        when(storage.getInventory()).thenReturn(mock(MEStorage.class));
        var host = (IAe2OutputHostBase) machine;
        when(host.productivebeesgenesis$getAe2GridNode()).thenReturn(managed);
        when(host.productivebeesgenesis$isOutputPushEnabled()).thenReturn(true);
        try (var runtime = mockStatic(com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime.class);
             var hosts = mockStatic(com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost.class, CALLS_REAL_METHODS)) {
            runtime.when(() -> com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime.accounts(server)).thenReturn(data);
            hosts.when(() -> com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost.ready(machine)).thenReturn(true);
            var output = new Ae2ResourceOutput(machine, holder);
            when(level.getGameTime()).thenReturn(1L);
            output.tick();
            var binding = new com.ayoshiko.productivebeesgenesis.resource.ResourceAccount.Binding(
                    net.minecraft.world.level.Level.OVERWORLD.location(), net.minecraft.core.BlockPos.ZERO.asLong(),
                    net.minecraft.resources.ResourceLocation.parse("minecraft:furnace"));
            var account = accounts.create(java.util.UUID.randomUUID(), binding);
            raw.putUUID("source", account.reference().sourceId());
            raw.putLong("generation", account.reference().generation());
            state.deserializeNBT(registries, raw);
            when(level.getGameTime()).thenReturn(2L);
            output.tick();
            assertEquals("online", state.meStatus());
            assertTrue(account.availableExact().isEmpty());
            output.close();
        }
    }

    @AfterEach
    void reset() {
        Ae2NetworkWorkCoordinator.clearAll();
    }

    @Test
    void machineOutputsShareOneTurnAndResourceDisconnectDoesNotReleaseOrdinaryOutput() {
        var network = mock(MEStorage.class);
        var holder = new Ae2OutputStateHolder();
        var resources = new Ae2ResourceOutput(null, holder);
        var other = new Ae2OutputStateHolder();
        resources.changeNetwork(network);
        assertTrue(holder.tryAcquireNetworkWork(network, 0));
        assertTrue(resources.tryAcquireNetworkWork(0));
        assertTrue(other.tryAcquireNetworkWork(network, 0));
        assertEquals(2, Ae2NetworkWorkCoordinator.workerCountForTest(network));
        Ae2NetworkWorkCoordinator.recordCost(network, 0, 1_000_000, 150_000);
        assertTrue(holder.tryAcquireNetworkWork(network, 1));
        assertTrue(resources.tryAcquireNetworkWork(1));
        assertFalse(other.tryAcquireNetworkWork(network, 1));
        resources.changeNetwork(null);
        assertEquals(2, Ae2NetworkWorkCoordinator.workerCountForTest(network));
        assertFalse(resources.tryAcquireNetworkWork(1));
        assertTrue(holder.tryAcquireNetworkWork(network, 1));
        assertTrue(other.tryAcquireNetworkWork(network, 2));
        assertFalse(holder.tryAcquireNetworkWork(network, 2));

        var replacement = mock(MEStorage.class);
        resources.changeNetwork(replacement);
        assertTrue(resources.tryAcquireNetworkWork(3));
        assertEquals(1, Ae2NetworkWorkCoordinator.workerCountForTest(network));
        assertTrue(holder.tryAcquireNetworkWork(replacement, 3));
        assertEquals(1, Ae2NetworkWorkCoordinator.workerCountForTest(replacement));
        resources.close();
        assertEquals(1, Ae2NetworkWorkCoordinator.workerCountForTest(replacement));
    }
}
