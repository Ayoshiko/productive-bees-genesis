package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeView;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceJeiLifecycleMinecraftTest {

    @Test
    void retainedAndReappearingRecipesReuseOriginalRegisteredInstances() {
        ResourceJeiRecipe previous = recipe(10, 0.5, 0, false, false);
        ResourceJeiRecipe returning = new ResourceJeiRecipe(view("returning", 12, 1, 0, false), false);
        ResourceJeiRecipe added = new ResourceJeiRecipe(view("new", 17, 1, 0, false), false);
        IRecipeManager manager = manager(List.of(previous, returning));

        var next = ResourceJeiPlugin.updateRecipes(manager, ResourceJeiPlugin.PRODUCTION, List.of(previous),
                List.of(recipe(10, 0.5, 0, false, false),
                        new ResourceJeiRecipe(view("returning", 12, 1, 0, false), false), added));
        assertSame(previous, next.get(0));
        assertSame(returning, next.get(1));
        assertSame(added, next.get(2));
        verify(manager, never()).hideRecipes(any(), anyCollection());
        verify(manager).unhideRecipes(ResourceJeiPlugin.PRODUCTION, List.of(returning));
        verify(manager).addRecipes(ResourceJeiPlugin.PRODUCTION, List.of(added));

        clearInvocations(manager);
        assertSame(next, ResourceJeiPlugin.updateRecipes(manager, ResourceJeiPlugin.PRODUCTION, next,
                List.copyOf(next)));
        verifyNoInteractions(manager);
    }

    @Test
    void sameRecipeIdDoesNotReuseChangedResourceAmountChanceTimePbModeOrBlockPortion() {
        ResourceJeiRecipe previous = recipe(10, 0.5, 20, false, false);
        var changes = List.of(recipe(11, 0.5, 20, false, false),
                recipe(10, 0.75, 20, false, false),
                recipe(10, 0.5, 21, false, false),
                recipe(10, 0.5, 20, true, false),
                recipe(10, 0.5, 20, false, true),
                new ResourceJeiRecipe(new ResourceRecipeView(ResourceLocation.parse("test:same"),
                        ResourceLocation.parse("test:bee"),
                        List.of(new ResourceProductionRecipe.Output(
                                ResourceKey.chemical(ResourceLocation.parse("mekanism:hydrogen")), 10, 0.5)),
                        20, false), false));
        for (ResourceJeiRecipe changed : changes) {
            IRecipeManager manager = manager(List.of(previous));
            var shown = ResourceJeiPlugin.updateRecipes(manager, ResourceJeiPlugin.CENTRIFUGING,
                    List.of(previous), List.of(changed));
            assertSame(changed, shown.getFirst());
            verify(manager).hideRecipes(ResourceJeiPlugin.CENTRIFUGING, List.of(previous));
            verify(manager).addRecipes(ResourceJeiPlugin.CENTRIFUGING, List.of(changed));
            verify(manager, never()).unhideRecipes(any(), anyCollection());
        }
        IRecipeManager manager = manager(List.of(previous));
        assertTrue(ResourceJeiPlugin.updateRecipes(manager, ResourceJeiPlugin.CENTRIFUGING,
                List.of(previous), List.of()).isEmpty());
        verify(manager).hideRecipes(ResourceJeiPlugin.CENTRIFUGING, List.of(previous));
        verify(manager, never()).createRecipeLookup(any());
    }

    @Test
    void resourceCatalystsOnlyAdvertiseGenesisMechanicalHosts() {
        IRecipeCatalystRegistration registration = mock(IRecipeCatalystRegistration.class);
        Set<Item> catalysts = new HashSet<>();
        List<RecipeType<?>[]> categories = new ArrayList<>();
        doAnswer(invocation -> {
            ItemStack stack = invocation.getArgument(0);
            RecipeType<?>[] types = (RecipeType<?>[]) invocation.getRawArguments()[1];
            assertEquals("productivebeesgenesis", BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
            catalysts.add(stack.getItem());
            categories.add(types);
            return null;
        }).when(registration).addRecipeCatalyst(any(ItemStack.class), any(RecipeType[].class));

        new ResourceJeiPlugin().registerRecipeCatalysts(registration);
        assertTrue(catalysts.contains(ModBlocks.MEK_APIARY.get().asItem()));
        assertTrue(catalysts.contains(ModBlocks.MEK_CENTRIFUGE.get().asItem()));
        assertTrue(categories.stream().anyMatch(types -> List.of(types).contains(ResourceJeiPlugin.PRODUCTION)));
        assertTrue(categories.stream().anyMatch(types -> List.of(types).contains(ResourceJeiPlugin.CENTRIFUGING)));
        for (String id : List.of("advanced_oak_beehive", "centrifuge", "powered_centrifuge", "heated_centrifuge")) {
            Item pb = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("productivebees", id));
            assertFalse(catalysts.contains(pb), id);
        }
    }

    private static ResourceJeiRecipe recipe(long amount, double chance, int time, boolean includePb, boolean block) {
        return new ResourceJeiRecipe(view("same", amount, chance, time, includePb), block);
    }

    private static ResourceRecipeView view(String id, long amount, double chance, int time, boolean includePb) {
        return new ResourceRecipeView(ResourceLocation.fromNamespaceAndPath("test", id),
                ResourceLocation.parse("test:bee"),
                List.of(new ResourceProductionRecipe.Output(ResourceTypes.FE, amount, chance)), time, includePb);
    }

    private static IRecipeManager manager(List<ResourceJeiRecipe> registered) {
        IRecipeManager manager = mock(IRecipeManager.class);
        @SuppressWarnings("unchecked")
        IRecipeLookup<ResourceJeiRecipe> lookup = mock(IRecipeLookup.class, RETURNS_SELF);
        when(manager.createRecipeLookup(ResourceJeiPlugin.PRODUCTION)).thenReturn(lookup);
        when(manager.createRecipeLookup(ResourceJeiPlugin.CENTRIFUGING)).thenReturn(lookup);
        when(lookup.get()).thenAnswer(invocation -> registered.stream());
        return manager;
    }
}
