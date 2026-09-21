package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.*;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModTags;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class PbRecipeOutputsMinecraftTest {

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@AfterEach
	void clearCache() {
		PbRecipeCompleter.invalidateRecipeOutputsCache();
	}

	@Test
	void checksAndAccumulationShareOneParsedOutputTableUntilInvalidated() {
		var recipe = recipe(new ItemStack(Items.DIAMOND), 1, 1);
		var context = context(new AtomicBoolean());
		var completer = new PbRecipeCompleter(context);
		var slot = BasicInventorySlot.at(null, 0, 0);
		for (int i = 0; i < 256; i++) {
			assertTrue(PbRecipeOutputChecker.hasItemOutput(context, recipe));
			assertTrue(PbRecipeOutputChecker.isPbOutputCompatible(recipe, slot, null));
			completer.accumulatePbRecipeOutputs(recipe, 0, 1);
			assertEquals(1, completer.pendingItemCount());
			completer.resetPendingRecipe();
		}
		assertEquals(1, recipe.outputQueries);
		assertEquals(1, recipe.calculatedItemOutput.size());
		assertEquals(1, recipe.calculatedItemOutput.keySet().iterator().next().getCount());

		var other = recipe(new ItemStack(Items.EMERALD), 1, 1);
		assertTrue(PbRecipeOutputChecker.hasItemOutput(context, other));
		assertEquals(1, other.outputQueries);
		PbRecipeCompleter.invalidateRecipeOutputsCache();
		assertTrue(PbRecipeOutputChecker.hasItemOutput(context, recipe));
		assertEquals(2, recipe.outputQueries);
	}

	@Test
	void cachedTemplatesStillRespectWaxUpgradeChanges() {
		var originalTags = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
				pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
		try {
			BuiltInRegistries.ITEM.bindTags(Map.of(ModTags.Common.WAXES,
					List.of(BuiltInRegistries.ITEM.wrapAsHolder(ModItems.WAX.get()))));
			var wax = recipe(new ItemStack(ModItems.WAX.get()), 1, 1);
			var discardWax = new AtomicBoolean();
			var context = context(discardWax);
			var blocked = BasicInventorySlot.at(null, 0, 0);
			blocked.setStack(new ItemStack(Items.DIAMOND));
			assertTrue(PbRecipeOutputChecker.hasItemOutput(context, wax));
			assertFalse(PbRecipeOutputChecker.isPbOutputCompatible(wax, blocked, null, null, false));
			discardWax.set(true);
			assertFalse(PbRecipeOutputChecker.hasItemOutput(context, wax));
			assertTrue(PbRecipeOutputChecker.isPbOutputCompatible(wax, blocked, null, null, true));
			discardWax.set(false);
			assertTrue(PbRecipeOutputChecker.hasItemOutput(context, wax));
			assertEquals(1, wax.outputQueries);
		} finally {
			BuiltInRegistries.ITEM.bindTags(originalTags);
		}
	}

	@Test
	void zeroChanceAndZeroQuantityRemainAbsent() {
		var context = context(new AtomicBoolean());
		for (var recipe : List.of(recipe(new ItemStack(Items.DIAMOND), 1, 0),
				recipe(new ItemStack(Items.DIAMOND), 0, 1))) {
			assertFalse(PbRecipeOutputChecker.hasItemOutput(context, recipe));
			assertFalse(PbRecipeOutputChecker.hasItemOutput(context, recipe));
			assertEquals(1, recipe.outputQueries);
		}
	}

	@Test
	void repeatedEmptyResetClearsAmountsAndAllowsFurtherProduction() {
		var completer = new PbRecipeCompleter(context(new AtomicBoolean()));
		var recipe = recipe(new ItemStack(Items.DIAMOND), 1, 1);
		completer.accumulatePbRecipeOutputsBatch(recipe, 0, 1, 64);
		assertEquals(64, completer.pendingItemCount());
		completer.consumeAllPendingItems();
		completer.consumeAllPendingItems();
		assertEquals(0, completer.pendingItemCount());
		assertTrue(completer.getPendingOutputs().isEmpty());
		assertEquals(6_400, completer.getPendingFluidAmount());
		assertEquals(64, completer.pendingInputShrink());
		for (int i = 0; i < 256; i++) completer.resetPendingRecipe();
		assertFalse(completer.hasPendingOutputs());
		assertEquals(0, completer.pendingInputShrink());
		assertNull(completer.getPendingRecipe());
		completer.accumulatePbRecipeOutputs(recipe, 0, 1);
		assertEquals(1, completer.pendingItemCount());
		assertEquals(100, completer.getPendingFluidAmount());
		assertEquals(1, completer.pendingInputShrink());
	}

	private static PbRecipeContext context(AtomicBoolean discardWax) {
		return (PbRecipeContext) Proxy.newProxyInstance(PbRecipeContext.class.getClassLoader(),
				new Class<?>[]{PbRecipeContext.class}, (proxy, method, args) -> switch (method.getName()) {
					case "suppressesUselessByproducts" -> discardWax.get();
					case "stabilityBonus" -> 0.0f;
					case "productivebeesgenesis$markForSave" -> null;
					default -> throw new AssertionError("Unexpected context call: " + method.getName());
				});
	}

	private static CountingRecipe recipe(ItemStack output, int amount, float chance) {
		return new CountingRecipe(List.of(new ChancedOutput(Ingredient.of(output), amount, amount, chance)));
	}

	private static final class CountingRecipe extends CentrifugeRecipe {
		private int outputQueries;

		private CountingRecipe(List<ChancedOutput> outputs) {
			super(Ingredient.of(Items.HONEYCOMB), outputs, SizedFluidIngredient.of(Fluids.WATER, 100), 20);
		}

		@Override
		public Map<ItemStack, ChancedOutput> getRecipeOutputs() {
			outputQueries++;
			return super.getRecipeOutputs();
		}
	}
}
