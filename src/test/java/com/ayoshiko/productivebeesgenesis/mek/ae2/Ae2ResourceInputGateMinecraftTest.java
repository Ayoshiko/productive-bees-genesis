package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceManualOutput;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeIndex;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2ResourceInputGateMinecraftTest {
    @AfterEach
    void clearNetworkState() {
        Ae2NetworkWorkCoordinator.clearAll();
    }

    @Test
    void missingResourceIsSkippedBeforeAnyReceiverAndOrdinaryInputStillWorks() {
        var recipe = recipe(ResourceTypes.SOURCE);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        var host = host(level);
        var storage = mock(MEStorage.class);
        var gate = new Ae2ResourceInputGate();
        var index = ResourceRecipeIndex.build(1, List.of(), List.of(new RecipeHolder<>(id("source"), recipe)));
        try (var runtime = mockStatic(ResourceRuntime.class)) {
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            runtime.when(() -> ResourceRuntime.available(server, ResourceTypes.SOURCE)).thenReturn(false);
            gate.prepare((IAe2InputHost) host, new Ae2OutputStateHolder(), new Ae2PushBuffers(), storage, 1);
            assertFalse(gate.resourcesAvailable(AEItemKey.of(recipe.input(false))));
            assertFalse(gate.canReceive(AEItemKey.of(recipe.input(false))));
            assertTrue(gate.canReceive(AEItemKey.of(new ItemStack(Items.HONEYCOMB))));
            verifyNoInteractions(storage);
        }
    }

    @Test
    void rejectedMeOutputRechecksAndNativeReceiverCanSubstituteWithoutExecuting() {
        var resource = ResourceKey.chemical(id("test_chemical"));
        var recipe = recipe(resource);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        var host = host(level);
        var storage = mock(MEStorage.class);
        var key = AEItemKey.of(new ItemStack(Items.DIAMOND));
        var gate = new Ae2ResourceInputGate();
        var holder = new Ae2OutputStateHolder();
        var buffers = new Ae2PushBuffers();
        var index = ResourceRecipeIndex.build(1, List.of(), List.of(new RecipeHolder<>(id("output"), recipe)));
        var receiverCalls = new AtomicInteger();
        try (var runtime = mockStatic(ResourceRuntime.class);
             var bridge = mockStatic(Ae2ResourceKeyBridge.class);
             var nativeOutput = mockStatic(ResourceManualOutput.class)) {
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            runtime.when(() -> ResourceRuntime.available(server, resource)).thenReturn(true);
            bridge.when(() -> Ae2ResourceKeyBridge.resolve(level.registryAccess(), resource)).thenReturn(key);
            when(storage.insert(eq(key), eq(1L), eq(Actionable.SIMULATE), any(IActionSource.class)))
                    .thenAnswer(call -> receiverCalls.incrementAndGet() == 1 ? 0L : 1L);
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 1);
            assertFalse(gate.canReceive(AEItemKey.of(recipe.input(false))));
            for (int i = 0; i < 1024; i++) assertFalse(gate.canReceive(AEItemKey.of(recipe.input(false))));
            assertEquals(1, receiverCalls.get());
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 41);
            assertTrue(gate.canReceive(AEItemKey.of(recipe.input(false))));
            assertEquals(2, receiverCalls.get());
            gate.clear();
            nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, resource)).thenReturn(true);
            when(storage.insert(eq(key), eq(1L), eq(Actionable.SIMULATE), any(IActionSource.class))).thenReturn(0L);
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 42);
            assertTrue(gate.canReceive(AEItemKey.of(recipe.input(false))));
            verify(storage, never()).insert(any(), anyLong(), eq(Actionable.MODULATE), any());
            runtime.verify(() -> ResourceRuntime.accounts(server), never());
        }
    }

    @Test
    void probeAllowanceIsSharedAcrossMachinesAndResetsOnlyOnRealTick() {
        var state = Ae2NetworkWorkCoordinator.resolve(mock(MEStorage.class));
        for (int i = 0; i < 4; i++) assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResourceProbe(state, 1, 7));
        for (int i = 0; i < 1024; i++) assertFalse(Ae2NetworkWorkCoordinator.tryAcquireResourceProbe(state, 1, 7));
        assertFalse(Ae2NetworkWorkCoordinator.tryAcquireResourceProbe(state, 2, 7));
        assertFalse(Ae2NetworkWorkCoordinator.tryAcquireResourceProbe(state, 3, 7));
        for (long tick = 8; tick < 17; tick++) {
            long expected = (tick - 7) % 3 + 1;
            for (long worker = 1; worker <= 3; worker++) {
                assertEquals(worker == expected, Ae2NetworkWorkCoordinator.tryAcquireResourceProbe(state, worker, tick));
            }
        }
    }

    @Test
    void emcAdmissionUsesPlayerRouteAndNeverDependsOnMeStorageAcceptance() {
        var recipe = recipe(ResourceTypes.EMC);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        var host = host(level);
        var storage = mock(MEStorage.class);
        var key = AEItemKey.of(new ItemStack(Items.DIAMOND));
        var input = AEItemKey.of(recipe.input(false));
        var gate = new Ae2ResourceInputGate();
        var holder = new Ae2OutputStateHolder();
        var buffers = new Ae2PushBuffers();
        var index = ResourceRecipeIndex.build(1, List.of(), List.of(new RecipeHolder<>(id("emc"), recipe)));
        try (var runtime = mockStatic(ResourceRuntime.class);
             var bridge = mockStatic(Ae2ResourceKeyBridge.class);
             var nativeOutput = mockStatic(ResourceManualOutput.class)) {
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            runtime.when(() -> ResourceRuntime.available(server, ResourceTypes.EMC)).thenReturn(true);
            bridge.when(() -> Ae2ResourceKeyBridge.resolve(level.registryAccess(), ResourceTypes.EMC)).thenReturn(key);
            nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, ResourceTypes.EMC)).thenReturn(true);
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 1);
            assertTrue(gate.canReceive(input));
            nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, ResourceTypes.EMC)).thenReturn(false);
            gate.clear();
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 41);
            assertFalse(gate.canReceive(input));
            verifyNoInteractions(storage);
            runtime.verify(() -> ResourceRuntime.accounts(server), never());
        }
    }

    @Test
    void changingNetworkReleasesWaitingWorker() {
        var storage = mock(MEStorage.class);
        var first = new Ae2OutputStateHolder();
        var second = new Ae2OutputStateHolder();
        assertTrue(first.tryAcquireResourceProbe(storage, 1));
        assertFalse(second.tryAcquireResourceProbe(storage, 1));
        assertTrue(second.tryAcquireResourceProbe(mock(MEStorage.class), 1));
        assertTrue(first.tryAcquireResourceProbe(storage, 2));
    }

    @Test
    void mixedRecipeRequiresAReceiverForEveryResourceAndReloadDropsCachedAvailability() {
        var resource = ResourceKey.chemical(id("mixed_chemical"));
        var recipe = new ResourceCentrifugeRecipe(id("bee"), List.of(
                new ResourceProductionRecipe.Output(ResourceTypes.FE, 10, 1),
                new ResourceProductionRecipe.Output(resource, 10, 1)), 20);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        var host = host(level);
        var gate = new Ae2ResourceInputGate();
        var holder = new Ae2OutputStateHolder();
        var buffers = new Ae2PushBuffers();
        var storage = mock(MEStorage.class);
        var input = AEItemKey.of(recipe.input(false));
        var index = ResourceRecipeIndex.build(1, List.of(), List.of(new RecipeHolder<>(id("mixed"), recipe)));
        try (var runtime = mockStatic(ResourceRuntime.class);
             var bridge = mockStatic(Ae2ResourceKeyBridge.class);
             var nativeOutput = mockStatic(ResourceManualOutput.class)) {
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            runtime.when(() -> ResourceRuntime.available(server, ResourceTypes.FE)).thenReturn(true);
            runtime.when(() -> ResourceRuntime.available(server, resource)).thenReturn(true);
            nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, ResourceTypes.FE)).thenReturn(true);
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 1);
            assertTrue(gate.resourcesAvailable(input));
            assertFalse(gate.canReceive(input));
            nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, resource)).thenReturn(true);
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 41);
            assertTrue(gate.canReceive(input));
            runtime.when(() -> ResourceRuntime.available(server, resource)).thenReturn(false);
            gate.clear();
            gate.prepare((IAe2InputHost) host, holder, buffers, storage, 42);
            assertFalse(gate.resourcesAvailable(input));
            assertFalse(gate.canReceive(input));
            verifyNoInteractions(storage);
            runtime.verify(() -> ResourceRuntime.accounts(server), never());
        }
    }

    @Test
    void sixteenOutputsProgressAcrossSlowPullWindowsWithoutRestartingTheProbePrefix() {
        var outputs = new java.util.ArrayList<ResourceProductionRecipe.Output>();
        for (int i = 0; i < ResourceProductionRecipe.MAX_OUTPUTS; i++) {
            outputs.add(new ResourceProductionRecipe.Output(ResourceKey.chemical(id("chemical_" + i)), 10, 1));
        }
        var recipe = new ResourceCentrifugeRecipe(id("bee"), outputs, 20);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        var host = host(level);
        var gate = new Ae2ResourceInputGate();
        var holder = new Ae2OutputStateHolder();
        var buffers = new Ae2PushBuffers();
        var storage = mock(MEStorage.class);
        var input = AEItemKey.of(recipe.input(false));
        var index = ResourceRecipeIndex.build(1, List.of(), List.of(new RecipeHolder<>(id("sixteen"), recipe)));
        var calls = new AtomicInteger();
        try (var runtime = mockStatic(ResourceRuntime.class);
             var bridge = mockStatic(Ae2ResourceKeyBridge.class);
             var nativeOutput = mockStatic(ResourceManualOutput.class)) {
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            for (var output : outputs) {
                runtime.when(() -> ResourceRuntime.available(server, output.key())).thenReturn(true);
                nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, output.key()))
                        .thenAnswer(call -> {
                            calls.incrementAndGet();
                            return true;
                        });
            }
            for (long tick = 1; tick <= 121; tick += 40) {
                gate.prepare((IAe2InputHost) host, holder, buffers, storage, tick);
                var expected = tick == 121 ? Ae2ResourceInputGate.Decision.ACCEPTED : Ae2ResourceInputGate.Decision.DEFERRED;
                assertEquals(expected, gate.decision(input));
                assertEquals(expected, gate.decision(input));
            }
            assertEquals(16, calls.get());
            verifyNoInteractions(storage);
        }
    }

    @Test
    void nodeRecoveryInvalidatesRejectionAndIdleProbeDoesNotExecuteOrScanInventory() {
        var recipe = recipe(ResourceTypes.FE);
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        var host = host(level);
        var holder = new Ae2OutputStateHolder();
        var buffers = new Ae2PushBuffers();
        holder.setReusableBuffers(buffers);
        var storage = mock(MEStorage.class);
        var key = AEItemKey.of(recipe.input(false));
        var index = ResourceRecipeIndex.build(1, List.of(), List.of(new RecipeHolder<>(id("recovery"), recipe)));
        try (var runtime = mockStatic(ResourceRuntime.class);
             var nativeOutput = mockStatic(ResourceManualOutput.class);
             var bridge = mockStatic(Ae2ResourceKeyBridge.class)) {
            runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
            runtime.when(() -> ResourceRuntime.available(server, ResourceTypes.FE)).thenReturn(true);
            buffers.resourceInputGate.prepare((IAe2InputHost) host, holder, buffers, storage, 1);
            assertEquals(Ae2ResourceInputGate.Decision.REJECTED, buffers.resourceInputGate.decision(key));
            assertTrue(buffers.resourceInputGate.hasWaitingRecipes());
            nativeOutput.when(() -> ResourceManualOutput.canReceiveNeighbor(host, ResourceTypes.FE)).thenReturn(true);
            holder.onNodeStateChanged();
            buffers.resourceInputGate.prepare((IAe2InputHost) host, holder, buffers, storage, 2);
            buffers.resourceInputGate.refreshWaitingRecipes();
            assertFalse(buffers.resourceInputGate.hasWaitingRecipes());
            assertEquals(Ae2ResourceInputGate.Decision.ACCEPTED, buffers.resourceInputGate.decision(key));
            verifyNoInteractions(storage);
        }
    }

    private static BlockEntity host(ServerLevel level) {
        var host = mock(BlockEntity.class, withSettings().extraInterfaces(IAe2InputHost.class, IMekCentrifugeTile.class));
        when(host.getLevel()).thenReturn(level);
        when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(((IAe2InputHost) host).productivebeesgenesis$isOutputPushEnabled()).thenReturn(true);
        return host;
    }

    private static ResourceCentrifugeRecipe recipe(ResourceKey resource) {
        return new ResourceCentrifugeRecipe(id("bee"), List.of(new ResourceProductionRecipe.Output(resource, 10, 1)), 20);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("input_gate_test", path);
    }
}
