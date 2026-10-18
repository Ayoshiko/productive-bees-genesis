package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.client.jei.ContainerFlowerJei;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.BeeFloweringRecipe;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import java.util.HashMap;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ContainerFlowerJeiMinecraftTest {
    @Test void customRequirementReplacesDefaultFlowerSlotAndOrdinaryBeesKeepPbLayout() {
        var original = new HashMap<>(BeeReloadListener.INSTANCE.getData());
        var id = ResourceLocation.parse("test:jei_container");
        try {
            var data = new HashMap<>(original);
            data.put(id, BeeCreator.create(id, ContainerFlowerMinecraftTest.definition(
                    "{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":\"100\"}")));
            BeeReloadListener.INSTANCE.setData(data);
            var recipe = BeeFloweringRecipe.createBlock(id, Blocks.DANDELION, new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id));
            var builder = mock(IRecipeLayoutBuilder.class, RETURNS_DEEP_STUBS);
            assertTrue(ContainerFlowerJei.setRecipe(builder, recipe));
            verify(builder).addSlot(RecipeIngredientRole.INPUT, 29, 12);
            verify(builder).addSlot(RecipeIngredientRole.RENDER_ONLY, 26, 51);
            verify(builder, never()).addSlot(RecipeIngredientRole.INPUT, 26, 51);
            assertEquals(ContainerFlowerJei.requirement(recipe).description(),
                    ContainerFlower.fromData(data.get(id)).description());
            data.remove(id);
            BeeReloadListener.INSTANCE.setData(data);
            var ordinary = mock(IRecipeLayoutBuilder.class);
            assertFalse(ContainerFlowerJei.setRecipe(ordinary, recipe));
            verifyNoInteractions(ordinary);
        } finally {
            BeeReloadListener.INSTANCE.setData(original);
        }
    }
}
