package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeFinder;
import net.minecraft.world.level.Level;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class RecipeGenerationMinecraftTest {
    private static final ResourceLocation BEE = ResourceLocation.parse("test:reload_bee");
    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private long originalVersion;
    private BeeIngredient previousMyriad;

    @BeforeAll
    static void config() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @BeforeEach
    void begin() {
        originalVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
        previousMyriad = BeeIngredientFactory.getOrCreateList().put(PBConstants.MYRIADCREATIONS_TYPE_STRING,
                new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), PBConstants.MYRIADCREATIONS_TYPE));
        CentrifugeRecipeIndex.clear();
        RecipeReloadRetryManager.clearPendingRetryContext();
    }

    @AfterEach
    void end() {
        CentrifugeRecipeIndex.clear();
        RecipeReloadRetryManager.clearPendingRetryContext();
        SharedPbRecipeCache.invalidate();
        ProductiveBeesGenesis.RECIPE_VERSION.set(originalVersion);
        if (previousMyriad == null) {
            BeeIngredientFactory.getOrCreateList().remove(PBConstants.MYRIADCREATIONS_TYPE_STRING);
        } else {
            BeeIngredientFactory.getOrCreateList().put(PBConstants.MYRIADCREATIONS_TYPE_STRING, previousMyriad);
        }
    }

    @Test
    void candidateFailureLeavesAcceptedRecipesAndSuccessPublishesOnce() {
        var a = manager(Items.DIAMOND);
        var b = manager(Items.EMERALD);
        ProductiveBeesGenesis.acceptRecipeManager(a, false);
        var level = mock(Level.class);
        when(level.getRecipeManager()).thenReturn(a);
        var context = mock(PbRecipeContext.class);
        when(context.level()).thenReturn(level);
        var input = new ItemStack(PbCombItemRefs.honeycomb());
        input.set(PbDataComponents.beeType(), BEE);
        long acceptedA = ProductiveBeesGenesis.RECIPE_VERSION.get();
        assertSame(a.getRecipes().iterator().next(), CentrifugeRecipeIndex.get(BEE));

        var resources = mock(ResourceManager.class);
        when(resources.getResource(ResourceLocation.parse("productivebeesgenesis:bee_designer/index.json")))
                .thenReturn(Optional.empty());
        var barrier = new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <T> CompletableFuture<T> wait(T prepared) {
                return CompletableFuture.completedFuture(prepared);
            }
        };
        var candidate = new BeeRecipeReloader(b, REGISTRIES).reload(barrier, resources,
                InactiveProfiler.INSTANCE, InactiveProfiler.INSTANCE, Runnable::run, Runnable::run);
        candidate.join();
        var lateFailure = candidate.thenRun(() -> {
            throw new IllegalStateException("later reload listener");
        });
        assertThrows(CompletionException.class, lateFailure::join);
        assertTrue(CentrifugeRecipeIndex.isCurrent(a));
        assertSame(a.getRecipes().iterator().next(), CentrifugeRecipeIndex.get(BEE));
        assertEquals(acceptedA, ProductiveBeesGenesis.RECIPE_VERSION.get());
        assertSame(CentrifugeRecipeIndex.get(BEE), new PbRecipeFinder(context).findPbRecipe(input));

        when(level.getRecipeManager()).thenReturn(b);
        ProductiveBeesGenesis.acceptRecipeManager(b, false);
        assertTrue(CentrifugeRecipeIndex.isCurrent(b));
        assertSame(b.getRecipes().iterator().next(), CentrifugeRecipeIndex.get(BEE));
        assertSame(CentrifugeRecipeIndex.get(BEE), new PbRecipeFinder(context).findPbRecipe(input));
        assertEquals(acceptedA + 1, ProductiveBeesGenesis.RECIPE_VERSION.get());
        ProductiveBeesGenesis.acceptRecipeManager(b, false);
        assertEquals(acceptedA + 1, ProductiveBeesGenesis.RECIPE_VERSION.get());
    }

    @Test
    void staleRetryCannotReplaceCurrentManagerAndCurrentRetryIsConsumedOnce() {
        var a = manager(Items.DIAMOND);
        var b = manager(Items.EMERALD);
        var server = mock(MinecraftServer.class);
        when(server.getRecipeManager()).thenReturn(b);
        ProductiveBeesGenesis.acceptRecipeManager(a, false);
        RecipeReloadRetryManager.scheduleRetry(a, REGISTRIES);
        ProductiveBeesGenesis.acceptRecipeManager(b, false);
        assertFalse(RecipeReloadRetryManager.hasPendingRetry());
        var calls = new AtomicInteger();
        RecipeReloadRetryManager.scheduleRetry(a, REGISTRIES);
        RecipeReloadRetryManager.onServerTick(server, context -> calls.incrementAndGet());
        assertEquals(0, calls.get());
        assertFalse(RecipeReloadRetryManager.hasPendingRetry());

        RecipeReloadRetryManager.scheduleRetry(b, REGISTRIES);
        ResourceRuntime.beginReload(server);
        RecipeReloadRetryManager.onServerTick(server, context -> calls.incrementAndGet());
        assertEquals(0, calls.get());
        assertTrue(RecipeReloadRetryManager.hasPendingRetry());
        ResourceRuntime.finishReload(server, true);
        RecipeReloadRetryManager.onServerTick(server, context -> {
            assertSame(b, context.recipeManager());
            calls.incrementAndGet();
            ProductiveBeesGenesis.acceptRecipeManager(b, true);
        });
        RecipeReloadRetryManager.onServerTick(server, context -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        assertFalse(RecipeReloadRetryManager.hasPendingRetry());
        ResourceRuntime.stopped(new net.neoforged.neoforge.event.server.ServerStoppedEvent(server));
    }

    @Test
    void failedRebuildOfSameManagerDoesNotPublishANewGeneration() {
        var a = spy(manager(Items.DIAMOND));
        ProductiveBeesGenesis.acceptRecipeManager(a, false);
        long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
        var accepted = CentrifugeRecipeIndex.get(BEE);
        doThrow(new IllegalStateException("recipe index input failure"))
                .when(a).getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get());
        assertThrows(IllegalStateException.class, () -> ProductiveBeesGenesis.acceptRecipeManager(a, true));
        assertTrue(CentrifugeRecipeIndex.isCurrent(a));
        assertSame(accepted, CentrifugeRecipeIndex.get(BEE));
        assertEquals(version, ProductiveBeesGenesis.RECIPE_VERSION.get());
    }

    private static RecipeManager manager(Item output) {
        var input = new ItemStack(PbCombItemRefs.honeycomb());
        input.set(PbDataComponents.beeType(), BEE);
        var recipe = new CentrifugeRecipe(Ingredient.of(input),
                List.of(new ChancedOutput(Ingredient.of(output), 1, 1, 1)), null, 20);
        var manager = new RecipeManager(REGISTRIES);
        manager.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:reload_recipe"), recipe)));
        return manager;
    }
}
