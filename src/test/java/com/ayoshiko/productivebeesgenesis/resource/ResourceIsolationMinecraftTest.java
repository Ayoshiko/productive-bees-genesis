package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.RegisterResourceRecipesEvent;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.ItemStack;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import mekanism.common.lib.security.ISecurityTile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceIsolationMinecraftTest {
    private static final ResourceLocation RESOURCE_BEE = ResourceLocation.parse("isolation:resource_bee");
    private static final ResourceLocation ORDINARY_BEE = ResourceLocation.parse("productivebees:amethyst");
    private static final ResourceLocation WANNA_BEE = ResourceLocation.parse("productivebees:wanna");
    private static final ResourceLocation RECIPE = ResourceLocation.parse("isolation:production");

    @Test
    void registrationFailureRetainsDeclaredIdentityEvenWhenDefinitionIsInvalid() {
        Consumer<RegisterResourceRecipesEvent> listener = event -> event.register(RECIPE, RESOURCE_BEE, List.of());
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            var failure = assertThrows(ResourceProductionApi.RegistrationFailure.class,
                    () -> ResourceProductionApi.collect(1, List.of()));
            assertEquals(Set.of(RESOURCE_BEE), failure.beeTypes());
            assertNotNull(failure.getCause());
            assertThrows(UnsupportedOperationException.class, () -> failure.beeTypes().add(ORDINARY_BEE));
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
    }

    @Test
    void failedCatalogLeavesOrdinaryAndSpecialBeesOutAndRebuildsOnlyAfterReload() {
        var server = mock(MinecraftServer.class);
        var manager = mock(RecipeManager.class);
        var level = mock(ServerLevel.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.getRecipeManager()).thenReturn(manager);
        when(level.getServer()).thenReturn(server);
        var calls = new AtomicInteger();
        Consumer<RegisterResourceRecipesEvent> failing = event -> {
            calls.incrementAndGet();
            event.register(RECIPE, RESOURCE_BEE, List.of(new ResourceProductionRecipe.Output(ResourceTypes.FE, 100, 1)));
            event.registerCentrifuging(ResourceLocation.parse("isolation:centrifuging"),
                    new ResourceCentrifugeRecipe(RESOURCE_BEE,
                            List.of(new ResourceProductionRecipe.Output(ResourceTypes.FE, 100, 1)), 20));
            throw new IllegalStateException("Injected resource registration failure");
        };
        long originalVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
        NeoForge.EVENT_BUS.addListener(failing);
        try {
            var catalog = ResourceRuntime.pbCatalog(level);
            assertNotNull(catalog.get(RESOURCE_BEE));
            assertFalse(catalog.get(RESOURCE_BEE).validForMechanical());
            assertNull(catalog.get(ORDINARY_BEE));
            assertNull(catalog.get(WANNA_BEE));
            var ordinary = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
            ordinary.set(PbDataComponents.beeType(), ORDINARY_BEE);
            var resource = ordinary.copy();
            resource.set(PbDataComponents.beeType(), RESOURCE_BEE);
            assertTrue(ResourceInputAdmission.allows(server, ordinary));
            assertFalse(ResourceInputAdmission.allows(server, resource));
            assertEquals(ResourceRuntime.CentrifugeAdmission.UNMANAGED, ResourceRuntime.centrifugeAdmission(server, ordinary));
            assertEquals(ResourceRuntime.CentrifugeAdmission.UNAVAILABLE, ResourceRuntime.centrifugeAdmission(server, resource));
            for (int i = 0; i < 32; i++) assertSame(catalog, ResourceRuntime.pbCatalog(level));
            assertEquals(1, calls.get());
            NeoForge.EVENT_BUS.unregister(failing);
            ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
            var recovered = ResourceRuntime.pbCatalog(level);
            assertNotSame(catalog, recovered);
            assertNull(recovered.get(RESOURCE_BEE));
            assertNull(recovered.get(ORDINARY_BEE));
            assertNull(recovered.get(WANNA_BEE));
        } finally {
            NeoForge.EVENT_BUS.unregister(failing);
            ResourceRuntime.stopped(new ServerStoppedEvent(server));
            ProductiveBeesGenesis.RECIPE_VERSION.set(originalVersion);
        }
    }

    @Test
    void failedReloadBlocksExtendedRouteButDoesNotLeaveOrdinaryPublicationGateClosed() {
        var server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        try {
            ResourceRuntime.beginReload(server);
            assertTrue(ResourceRuntime.reloadInProgress(server));
            ResourceRuntime.finishReload(server, false);
            assertFalse(ResourceRuntime.reloadInProgress(server));
            assertTrue(ResourceRuntime.reloadBlocked(server));
            ResourceRuntime.beginReload(server);
            ResourceRuntime.finishReload(server, true);
            assertFalse(ResourceRuntime.reloadBlocked(server));
            assertFalse(ResourceRuntime.reloadInProgress(server));
        } finally {
            ResourceRuntime.stopped(new ServerStoppedEvent(server));
        }
    }

    @Test
    void staleBindingKeepsOnlyTheOriginalResourceLaneAndFullInputIdentityIsolated() {
        var server = mock(MinecraftServer.class);
        var level = mock(ServerLevel.class);
        var entity = mock(BlockEntity.class, withSettings().extraInterfaces(ISecurityTile.class));
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(entity.getLevel()).thenReturn(level);
        when(entity.getBlockPos()).thenReturn(BlockPos.ZERO);
        var owner = java.util.UUID.randomUUID();
        when(((ISecurityTile) entity).getOwnerUUID()).thenReturn(owner);
        var accounts = new ResourceAccounts(() -> {});
        var binding = new ResourceAccount.Binding(Level.OVERWORLD.location(), 0,
                ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
        var account = accounts.create(owner, binding);
        var context = new CompoundTag();
        context.putString("host", "resource_centrifuging");
        context.putInt("lane", 2);
        var ticket = account.reserve(account.reference(), 1, context, java.util.Map.of(ResourceTypes.FE, 100L));
        account.start(ticket);
        account.freeze(ticket, java.util.Map.of(ResourceTypes.FE, 100L));
        account.suspendProduction(ticket, "Uncertain original lane");
        account.retire(account.reference(), binding);
        var host = mock(ResourceHiveHost.class);
        when(host.reference()).thenReturn(ticket.source());
        when(host.account(entity)).thenThrow(new IllegalStateException("Stale binding"));
        var data = mock(ResourceAccountsSavedData.class);
        when(data.accounts()).thenReturn(accounts);
        var original = accounts.save();
        try (var hosts = mockStatic(ResourceHiveHost.class);
             var runtime = mockStatic(ResourceRuntime.class)) {
            hosts.when(() -> ResourceHiveHost.existing(entity)).thenReturn(host);
            runtime.when(() -> ResourceRuntime.accounts(server)).thenReturn(data);
            assertTrue(ResourceCentrifugeProcessor.hasWork(entity, 2));
            assertFalse(ResourceCentrifugeProcessor.hasWork(entity, 0));
            assertEquals(ItemStack.EMPTY, ResourceCentrifugeProcessor.requiredLaneInput(entity, 2));
            assertNull(ResourceCentrifugeProcessor.requiredLaneInput(entity, 0));
            assertEquals(original, accounts.save());
            assertTrue(account.laneSuspended(2));
            verify(host, atLeastOnce()).account(entity);
        }
    }
}
