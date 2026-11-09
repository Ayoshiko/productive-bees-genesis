package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class ApiaryParentRecipeIsolationMinecraftTest {
    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legalCageSmeltingCannotConsumeTheRealParentInput(boolean sturdy) throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var cage = new ItemStack(sturdy ? ModItems.STURDY_BEE_CAGE.get() : ModItems.BEE_CAGE.get(), 2);
        var bee = new CompoundTag();
        bee.putString("entity", "minecraft:bee");
        bee.putInt("Age", -24000);
        cage.set(DataComponents.CUSTOM_DATA, CustomData.of(bee));

        var recipe = new SmeltingRecipe("finite_boundary", CookingBookCategory.MISC,
                Ingredient.of(cage.getItem()), new ItemStack(Items.IRON_INGOT), 0, 1);
        var manager = new RecipeManager(registries);
        manager.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("example:cage_smelting"), recipe)));
        var level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(registries);
        when(level.getRecipeManager()).thenReturn(manager);
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary"))
                .defaultBlockState();
        var tile = new ProbeApiary(state);
        tile.setLevel(level);
        var input = tile.slotManager().getCageInSlot();
        assertTrue(input.isItemValid(cage));
        input.setStack(cage);
        var parentInput = TileEntityElectricMachine.class.getDeclaredField("inputSlot");
        parentInput.setAccessible(true);
        assertSame(input, parentInput.get(tile));

        MekanismRecipeType.clearCache();
        try {
            var parentRecipe = tile.parentLookup();
            assertNotNull(parentRecipe, "Vanilla custom smelting reaches the actual parent input handler");
            assertTrue(parentRecipe.test(input.getStack()));
            assertNull(tile.getRecipe(0));
            ItemStack before = input.getStack().copy();
            tile.getEnergyContainer().setEnergy(tile.getEnergyContainer().getMaxEnergy());
            long energy = tile.getEnergyContainer().getEnergy();
            for (int change = 0; change < 4; change++) {
                tile.refreshParentRecipe();
                assertFalse(tile.processParentRecipe());
            }
            assertTrue(ItemStack.matches(before, input.getStack()));
            assertEquals(energy, tile.getEnergyContainer().getEnergy());
            assertTrue(tile.slotManager().getCageOutSlot().isEmpty());
        } finally {
            MekanismRecipeType.clearCache();
        }
    }

    private static final class ProbeApiary extends TileEntityMekApiary {
        ProbeApiary(BlockState state) {
            super(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
        }

        ItemStackToItemStackRecipe parentLookup() {
            return findFirstRecipe(inputHandler);
        }

        void refreshParentRecipe() {
            recipeCacheLookupMonitor.onChange();
        }

        boolean processParentRecipe() {
            return recipeCacheLookupMonitor.updateAndProcess();
        }
    }
}
