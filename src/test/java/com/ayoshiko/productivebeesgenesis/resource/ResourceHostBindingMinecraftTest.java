package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceHostBindingMinecraftTest {
    @Test void missingReferenceRecoversIntoAnIndependentAccountAndKeepsOldIdentityEvidence() {
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        var host = mock(com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary.class);
        when(host.getLevel()).thenReturn(level);
        when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
        doReturn(BlockEntityType.FURNACE).when(host).getType();
        when(host.getOwnerUUID()).thenReturn(ResourceAccountsMinecraftTest.OWNER);
        when(host.getOutputSlots()).thenReturn(java.util.List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getServer()).thenReturn(server);
        var chunks = mock(net.minecraft.server.level.ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.hasChunk(0, 0)).thenReturn(true);
        when(server.isSameThread()).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(host);
        var oldId = java.util.UUID.randomUUID();
        var saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putUUID("source", oldId);
        saved.putLong("generation", 5);
        saved.putLong("capacity", 1000);
        saved.putBoolean("me_output", true);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var state = new ResourceHiveHost();
        state.deserializeNBT(registries, saved);
        when(host.getData(ResourceHiveHost.TYPE)).thenReturn(state);
        when(host.getExistingDataOrNull(ResourceHiveHost.TYPE)).thenReturn(state);
        var accounts = new ResourceAccounts(() -> {});
        var data = mock(ResourceAccountsSavedData.class);
        when(data.accounts()).thenReturn(accounts);
        try (var runtime = mockStatic(ResourceRuntime.class)) {
            runtime.when(() -> ResourceRuntime.accounts(server)).thenReturn(data);
            ResourceHiveHost.ensureMechanical(host);
            assertNotEquals(oldId, state.reference().sourceId());
            assertEquals(1, accounts.size());
            assertTrue(state.account(host).availableExact().isEmpty());
            var savedAgain = state.serializeNBT(registries);
            var evidence = savedAgain.getList("unresolved_references", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
            assertEquals(oldId, evidence.getUUID("source"));
            assertEquals(5, evidence.getLong("generation"));
            assertEquals("missing_account", evidence.getString("reason"));
            var reference = state.reference();
            ResourceHiveHost.ensureMechanical(host);
            assertEquals(reference, state.reference());
            assertEquals(1, accounts.size());
            var restored = new ResourceHiveHost();
            restored.deserializeNBT(registries, savedAgain);
            assertEquals(savedAgain, restored.serializeNBT(registries));
        }
    }

    @Test void samePositionAndSameIdentityReplacementDoesNotRetireTheResourceAccount() {
        var previous = mock(BlockEntity.class);
        var replacement = mock(BlockEntity.class);
        var level = mock(ServerLevel.class);
        for (var entity : java.util.List.of(previous, replacement)) {
            when(entity.getLevel()).thenReturn(level);
            when(entity.getBlockPos()).thenReturn(BlockPos.ZERO);
            doReturn(BlockEntityType.FURNACE).when(entity).getType();
        }
        var saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putUUID("source", java.util.UUID.randomUUID());
        saved.putLong("generation", 5);
        saved.putLong("capacity", 1000);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var before = new ResourceHiveHost();
        var after = new ResourceHiveHost();
        before.deserializeNBT(registries, saved);
        after.deserializeNBT(registries, saved);
        when(previous.getExistingDataOrNull(ResourceHiveHost.TYPE)).thenReturn(before);
        when(replacement.getExistingDataOrNull(ResourceHiveHost.TYPE)).thenReturn(after);
        assertTrue(ResourceHiveHost.sameLiveReference(previous, replacement));
        saved.putLong("generation", 6);
        after.deserializeNBT(registries, saved);
        assertFalse(ResourceHiveHost.sameLiveReference(previous, replacement));
        assertFalse(ResourceHiveHost.sameLiveReference(previous, previous));
    }
    @Test
    void resourceAndOrdinaryParallelApplyTheCapBeforeProductivity() {
        assertEquals(65_536 * 480, com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic.parallelOperations(65_536, 480, 0));
        assertEquals(64 * 480, com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic.parallelOperations(65_536, 480, 64));
        assertEquals(Integer.MAX_VALUE, com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic.parallelOperations(Integer.MAX_VALUE, 480, 0));
    }

    @Test
    void mechanicalCapacityUsesFullOutputMultiplierIncludingLargeBasicSlots() {
        var apiary = mock(com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary.class);
        var output = mock(mekanism.common.inventory.slot.BasicInventorySlot.class,
                withSettings().extraInterfaces(com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot.class));
        when(apiary.getOutputSlots()).thenReturn(java.util.List.of(output));
        var tiered = (com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot) output;
        when(tiered.productivebeesgenesis$getCachedMultiplier()).thenReturn(1, 4096);
        assertEquals(ResourceHiveHost.DEFAULT_CAPACITY, ResourceHiveHost.capacityFor(apiary));
        assertEquals(4_096_000_000L, ResourceHiveHost.capacityFor(apiary));

        var centrifuge = mock(BlockEntity.class, withSettings().extraInterfaces(
                com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile.class,
                com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext.class));
        when(((com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext) centrifuge).secondaryOutputSlot(0)).thenReturn(output);
        int base = com.ayoshiko.productivebeesgenesis.config.FactoryTierConfigService.current().centrifugeOutputStack(
                com.ayoshiko.productivebeesgenesis.config.FactoryTierKey.BASIC);
        when(tiered.productivebeesgenesis$getCachedMultiplier()).thenReturn(base, base * 5);
        assertEquals(ResourceHiveHost.DEFAULT_CAPACITY * base, ResourceHiveHost.capacityFor(centrifuge));
        assertEquals(ResourceHiveHost.DEFAULT_CAPACITY * base * 5L, ResourceHiveHost.capacityFor(centrifuge));
        when(tiered.productivebeesgenesis$getCachedMultiplier()).thenReturn(-1);
        assertEquals(ResourceHiveHost.DEFAULT_CAPACITY, ResourceHiveHost.capacityFor(centrifuge));
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        try {
            var block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
            var real = new com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge(
                    net.minecraft.core.Holder.direct(block), BlockPos.ZERO, block.defaultBlockState());
            assertEquals(ResourceHiveHost.DEFAULT_CAPACITY * base, ResourceHiveHost.capacityFor(real));
        } finally {
            net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
        }
    }

    @Test
    void cachedBindingStillRejectsReplacedRemovedMovedAndRetiredHosts() {
        var level = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        var host = mock(BlockEntity.class);
        when(host.getLevel()).thenReturn(level);
        when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
        doReturn(BlockEntityType.FURNACE).when(host).getType();
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getServer()).thenReturn(server);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(host);
        var binding = ResourceHiveHost.binding(host);
        var accounts = new ResourceAccounts(() -> {});
        var account = accounts.create(ResourceAccountsMinecraftTest.OWNER, binding);
        var saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putUUID("source", account.reference().sourceId());
        saved.putLong("generation", account.reference().generation());
        saved.putLong("capacity", ResourceHiveHost.DEFAULT_CAPACITY);
        var state = new ResourceHiveHost();
        state.deserializeNBT(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), saved);
        var data = mock(ResourceAccountsSavedData.class);
        when(data.accounts()).thenReturn(accounts);
        try (var runtime = mockStatic(ResourceRuntime.class)) {
            runtime.when(() -> ResourceRuntime.accounts(server)).thenReturn(data);
            assertSame(account, state.account(host));
            assertSame(account, state.account(host));
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
            assertThrows(IllegalStateException.class, () -> state.account(host));
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(host);
            when(host.isRemoved()).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> state.account(host));
            when(host.isRemoved()).thenReturn(false);
            when(level.dimension()).thenReturn(Level.NETHER);
            assertThrows(IllegalStateException.class, () -> state.account(host));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            doReturn(BlockEntityType.CHEST).when(host).getType();
            assertThrows(IllegalStateException.class, () -> state.account(host));
            doReturn(BlockEntityType.FURNACE).when(host).getType();
            when(host.getBlockPos()).thenReturn(BlockPos.ZERO.east());
            when(level.getBlockEntity(BlockPos.ZERO.east())).thenReturn(host);
            assertThrows(IllegalStateException.class, () -> state.account(host));
            when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
            assertSame(account, state.account(host));
            account.retire(account.reference(), binding);
            assertThrows(IllegalStateException.class, () -> state.account(host));
        }
    }
}
