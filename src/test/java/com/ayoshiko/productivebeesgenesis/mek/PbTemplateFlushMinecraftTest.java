package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.List;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class PbTemplateFlushMinecraftTest {
	@org.junit.jupiter.api.BeforeAll
	static void loadConfigs() {
		net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@org.junit.jupiter.api.AfterAll
	static void unloadConfigs() {
		net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
		PbRecipeCompleter.invalidateRecipeOutputsCache();
	}

	@Test
	void partialDirectAcceptanceCommitsInputOnceAndPreservesRemainder() {
		var context = mock(PbRecipeContext.class);
		var input = BasicInventorySlot.at(null, 0, 0);
		input.setStack(new ItemStack(Items.HONEYCOMB, 5));
		var output = BasicInventorySlot.at(null, 0, 0);
		output.setStack(new ItemStack(Items.STONE, 64));
		when(context.inputSlot(0)).thenReturn(input);
		when(context.primaryOutputSlot(0)).thenReturn(output);
		when(context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
		var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB),
				List.of(new ChancedOutput(Ingredient.of(Items.DIAMOND), 10, 10, 1)),
				SizedFluidIngredient.of(Fluids.WATER, 1), 20);
		var completer = new PbRecipeCompleter(context);
		completer.accumulatePbRecipeOutputsBatch(recipe, 0, 1, 1);
		completer.consumePendingFluid(Long.MAX_VALUE);
		ItemStack template = completer.getPendingOutputs().keySet().iterator().next();
		template.set(DataComponents.CUSTOM_NAME, Component.literal("preserve exact output"));
		when(context.productivebeesgenesis$pushGeneratedItemToAe(same(template), eq(10))).thenReturn(3);
		when(context.productivebeesgenesis$pushGeneratedItemToNeighbors(same(template), eq(7))).thenReturn(2);
		assertFalse(completer.flushPendingPbOutputs(0));
		assertEquals(4, input.getCount());
		assertEquals(5, completer.pendingItemCount());
		assertEquals(5, completer.getPendingOutputs().get(template));
		assertEquals(1, template.getCount());
		assertEquals(64, output.getCount());
		when(context.productivebeesgenesis$pushGeneratedItemToAe(same(template), eq(5))).thenReturn(5);
		assertTrue(completer.flushPendingPbOutputs(0));
		assertEquals(4, input.getCount(), "pending drain must never charge the input a second time");
		assertFalse(completer.hasPendingOutputs());
		verify(context, never()).productivebeesgenesis$pushGeneratedItemToAe(any(ItemStack.class));
		verify(context, never()).productivebeesgenesis$pushGeneratedItemToNeighbors(any(ItemStack.class));
	}

	@Test
	void legacyContextReceivesAnIndependentCountedStack() {
		var context = mock(PbRecipeContext.class, CALLS_REAL_METHODS);
		var template = new ItemStack(Items.DIAMOND);
		doAnswer(call -> {
			ItemStack request = call.getArgument(0);
			assertNotSame(template, request);
			assertEquals(4096, request.getCount());
			request.setCount(0);
			return 23;
		}).when(context).productivebeesgenesis$pushGeneratedItemToAe(any(ItemStack.class));
		assertEquals(23, context.productivebeesgenesis$pushGeneratedItemToAe(template, 4096));
		assertEquals(1, template.getCount());
	}
}
